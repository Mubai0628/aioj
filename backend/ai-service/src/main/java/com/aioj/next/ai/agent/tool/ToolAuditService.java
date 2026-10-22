package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.persistence.entity.AiToolCallEntity;
import com.aioj.next.ai.persistence.mapper.AiToolCallMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Writes the ai_tool_calls audit row for every brokered call (design doc §4.1).
 * Audit failure must not break a chat turn, so it is logged and swallowed.
 */
@Service
public class ToolAuditService {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditService.class);
    private static final int MAX_AUDIT_ARGUMENTS_CHARS = 2_000;
    private static final Set<String> SAFE_METRIC_FIELDS = Set.of(
            "timeMillis", "memoryKb", "procPeak", "runTimeMillis", "outputTruncated",
            "passedCaseCount", "totalCaseCount", "maxTimeMillis", "maxMemoryKb", "maxProcessCount",
            "caseCount", "fileSizeBytes", "totalInputBytes", "totalOutputBytes", "largestCaseBytes",
            "executedCaseCount", "failedCaseCount", "maxRunTimeMillis", "maxProcPeak",
            "referenceExecuted", "referencePassedCaseCount", "stateVersion"
    );

    private final AiToolCallMapper mapper;
    private final ObjectMapper objectMapper;

    public ToolAuditService(AiToolCallMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** Durable pre-execution marker. A null handle means audit persistence was unavailable. */
    public AuditHandle start(ToolExecutionContext context, long agentRunId, int callSeq,
                             ToolDescriptor descriptor, String toolName, String callId,
                             String argumentsJson, String policyDecisionId) {
        try {
            AiToolCallEntity entity = baseEntity(context, agentRunId, callSeq, descriptor,
                    toolName, callId, argumentsJson, policyDecisionId);
            entity.setStatus("RUNNING");
            mapper.insert(entity);
            return new AuditHandle(entity.getId());
        } catch (RuntimeException ex) {
            log.warn("AI tool call pre-audit failed turn={} tool={} error={}",
                    context.turnId(), toolName, ex.toString());
            return null;
        }
    }

    /** Completes the exact RUNNING row created before execution. */
    public void complete(AuditHandle handle, ToolStatus status,
                         DataClassification resultClassification, String resultPayload,
                         long latencyMs, String errorCode) {
        if (handle == null) {
            return;
        }
        try {
            AiToolCallEntity entity = new AiToolCallEntity();
            entity.setId(handle.id());
            entity.setStatus(status.name());
            entity.setResultClassification(resultClassification == null ? null : resultClassification.name());
            entity.setResultHash(sha256(resultPayload));
            entity.setResultTokens(estimateTokens(resultPayload));
            applySafeResultDetails(entity, status, resultClassification, resultPayload);
            entity.setLatencyMs((int) Math.max(0, latencyMs));
            entity.setErrorCode(errorCode);
            entity.setCompletedAt(LocalDateTime.now());
            mapper.updateById(entity);
        } catch (RuntimeException ex) {
            log.warn("AI tool call completion audit failed id={} error={}", handle.id(), ex.toString());
        }
    }

    public void record(ToolExecutionContext context, long agentRunId, int callSeq,
                       ToolDescriptor descriptor, String toolName, String callId,
                       String argumentsJson, String policyDecisionId, ToolStatus status,
                       DataClassification resultClassification, String resultPayload,
                       long latencyMs, String errorCode) {
        try {
            AiToolCallEntity entity = baseEntity(context, agentRunId, callSeq, descriptor,
                    toolName, callId, argumentsJson, policyDecisionId);
            entity.setStatus(status.name());
            entity.setResultClassification(resultClassification == null ? null : resultClassification.name());
            entity.setResultHash(sha256(resultPayload));
            entity.setResultTokens(estimateTokens(resultPayload));
            applySafeResultDetails(entity, status, resultClassification, resultPayload);
            entity.setLatencyMs((int) Math.max(0, latencyMs));
            entity.setErrorCode(errorCode);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setStartedAt(entity.getCreatedAt());
            entity.setCompletedAt(entity.getCreatedAt());
            mapper.insert(entity);
        } catch (RuntimeException ex) {
            log.warn("AI tool call audit failed turn={} tool={} error={}", context.turnId(), toolName, ex.toString());
        }
    }

    private AiToolCallEntity baseEntity(ToolExecutionContext context, long agentRunId, int callSeq,
                                        ToolDescriptor descriptor, String toolName, String callId,
                                        String argumentsJson, String policyDecisionId) {
        AiToolCallEntity entity = new AiToolCallEntity();
        entity.setId(IdWorker.getId());
        entity.setAgentRunId(agentRunId);
        entity.setTurnId(context.turnId());
        entity.setUserId(context.userId());
        entity.setCallId(callId == null ? "" : callId);
        entity.setCallSeq(callSeq);
        entity.setToolName(toolName);
        entity.setToolVersion(descriptor == null ? "-" : descriptor.version());
        boolean auditArguments = descriptor == null || descriptor.auditLevel() != ToolAuditLevel.NO_ARGUMENTS;
        entity.setArgumentsRedacted(auditArguments ? redactArguments(argumentsJson) : null);
        entity.setPolicyDecisionId(policyDecisionId);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setStartedAt(entity.getCreatedAt());
        return entity;
    }

    private void applySafeResultDetails(AiToolCallEntity entity, ToolStatus status,
                                        DataClassification classification, String payload) {
        try {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("status", status == null ? "UNKNOWN" : status.name());
            if (classification != null) {
                summary.put("classification", classification.name());
            }
            summary.put("resultHash", sha256(payload));
            summary.put("resultTokens", estimateTokens(payload));
            JsonNode envelope = payload == null ? null : objectMapper.readTree(payload);
            JsonNode data = envelope == null ? null : envelope.path("data");
            if (data != null && data.isObject()) {
                JsonNode toolStatus = data.get("status");
                if (toolStatus != null && toolStatus.isTextual()) {
                    summary.put("toolStatus", truncateSafeText(toolStatus.asText(), 64));
                }
                ObjectNode metrics = objectMapper.createObjectNode();
                for (String field : SAFE_METRIC_FIELDS) {
                    JsonNode value = data.get(field);
                    if (value != null && (value.isNumber() || value.isBoolean())) {
                        metrics.set(field, value);
                    }
                }
                if (!metrics.isEmpty()) {
                    entity.setResourceMetricsJson(metrics.toString());
                    summary.set("resourceMetrics", metrics);
                }
                JsonNode executionId = data.get("executionId");
                JsonNode artifactId = data.get("artifactId");
                if (executionId != null && executionId.isValueNode()) {
                    entity.setResourceRefType("DRAFT_EXECUTION");
                    entity.setResourceRefId(truncateSafeText(executionId.asText(), 128));
                } else if (artifactId != null && artifactId.isValueNode()) {
                    entity.setResourceRefType("DRAFT_TESTCASE_ARTIFACT");
                    entity.setResourceRefId(truncateSafeText(artifactId.asText(), 128));
                }
            }
            entity.setSafeSummaryJson(summary.toString());
        } catch (RuntimeException ex) {
            entity.setSafeSummaryJson(null);
        } catch (Exception ex) {
            entity.setSafeSummaryJson(null);
        }
    }

    private String truncateSafeText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record AuditHandle(long id) {
    }

    private String redactArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.readTree(argumentsJson);
            JsonNode redacted = redact(tree);
            String serialized = redacted.toString();
            return serialized.length() <= MAX_AUDIT_ARGUMENTS_CHARS
                    ? serialized
                    : serialized.substring(0, MAX_AUDIT_ARGUMENTS_CHARS);
        } catch (Exception ex) {
            return "[unparseable arguments]";
        }
    }

    private JsonNode redact(JsonNode node) {
        if (node == null) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            node.forEach(value -> copy.add(redact(value)));
            return copy;
        }
        if (!node.isObject()) {
            return node;
        }
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (key.contains("key") || key.contains("token") || key.contains("secret") || key.contains("password")) {
                copy.put(entry.getKey(), "***");
            } else if (isRawExecutionContent(key)) {
                // Source, generators, testcase bytes and process I/O belong in
                // protected resource stores, never the generic audit payload.
                copy.put(entry.getKey(), "[redacted content]");
            } else if (entry.getValue().isContainerNode()) {
                copy.set(entry.getKey(), redact(entry.getValue()));
            }
        }
        return copy;
    }

    private boolean isRawExecutionContent(String key) {
        return key.contains("code")
                || key.contains("source")
                || key.contains("generator")
                || key.contains("solution")
                || key.equals("stdin")
                || key.equals("stdout")
                || key.equals("stderr")
                || key.equals("input")
                || key.equals("output")
                || key.contains("testcasecontent");
    }

    private String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private int estimateTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return (int) Math.max(1, (value.length() + 3L) / 4L);
    }
}
