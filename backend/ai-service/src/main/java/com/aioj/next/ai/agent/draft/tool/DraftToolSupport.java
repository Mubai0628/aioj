package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedbacks;
import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.FailurePolicy;
import com.aioj.next.agent.harness.tool.api.ToolAuditLevel;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.ai.persistence.entity.ProblemDraftExecutionEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DraftToolSupport {
    private DraftToolSupport() {
    }

    static String workspaceId(ToolExecutionContext context) {
        String workspaceId = context.serverBinding("workspaceId");
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Problem draft workspace binding is missing");
        }
        return workspaceId;
    }

    static Long agentRunId(ToolExecutionContext context) {
        String value = context.serverBinding("agentRunId");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * A5.1 feedback of one draft execution: the verdict (none for a normal finish, nothing was
     * compared), resources, located compile errors or a stderr summary.
     */
    static Map<String, Object> feedback(ProblemDraftExecutionEntity execution) {
        Map<String, Object> view = new LinkedHashMap<>();
        ExecutionFeedback.Verdict verdict = "ACCEPTED".equals(execution.getStatus()) ? null
                : verdictOf(execution.getStatus());
        view.put("executionId", execution.getId());
        view.put("verdict", verdict == null ? null : verdict.name());
        view.put("timeMillis", execution.getTimeMillis());
        view.put("memoryKb", execution.getMemoryKb());
        view.put("processPeak", execution.getProcPeak());
        if (verdict == ExecutionFeedback.Verdict.CE) {
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            for (ExecutionFeedback.CompileDiagnostic diagnostic : ExecutionFeedbacks.diagnostics(execution.getStderr())) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("line", diagnostic.line());
                node.put("column", diagnostic.column());
                node.put("message", diagnostic.message());
                diagnostics.add(node);
            }
            view.put("diagnostics", diagnostics);
        } else {
            String summary = ExecutionFeedbacks.stderrSummary(execution.getStderr());
            if (!summary.isBlank()) {
                view.put("stderrSummary", summary);
            }
        }
        return view;
    }

    /** A5.1 feedback of a batch of cases: counts, failure kinds and the largest resource use only. */
    static Map<String, Object> casesFeedback(int passed, int total, Map<String, Integer> failures, Long maxTimeMillis,
                                             Long maxMemoryKb, Integer maxProcessPeak) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("verdict", total > 0 && passed == total ? ExecutionFeedback.Verdict.AC.name() : null);
        view.put("passed", passed);
        view.put("total", total);
        if (failures != null && !failures.isEmpty()) {
            Map<String, Integer> byVerdict = new LinkedHashMap<>();
            failures.forEach((kind, count) -> {
                ExecutionFeedback.Verdict mapped = verdictOf(kind);
                byVerdict.merge(mapped == null ? String.valueOf(kind) : mapped.name(), count == null ? 0 : count,
                        Integer::sum);
            });
            view.put("failures", byVerdict);
        }
        view.put("maxTimeMillis", maxTimeMillis);
        view.put("maxMemoryKb", maxMemoryKb);
        view.put("maxProcessPeak", maxProcessPeak);
        return view;
    }

    /** The executor's result codes as A5.1 verdicts; null for codes that are not one. */
    static ExecutionFeedback.Verdict verdictOf(String resultCode) {
        return switch (resultCode == null ? "" : resultCode) {
            case "ACCEPTED" -> ExecutionFeedback.Verdict.AC;
            case "WRONG_ANSWER" -> ExecutionFeedback.Verdict.WA;
            case "TIME_LIMIT_EXCEEDED" -> ExecutionFeedback.Verdict.TLE;
            case "MEMORY_LIMIT_EXCEEDED" -> ExecutionFeedback.Verdict.MLE;
            case "RUNTIME_ERROR" -> ExecutionFeedback.Verdict.RE;
            case "COMPILE_ERROR" -> ExecutionFeedback.Verdict.CE;
            case "OUTPUT_LIMIT_EXCEEDED" -> ExecutionFeedback.Verdict.OLE;
            case "SYSTEM_ERROR", "INTERNAL_ERROR" -> ExecutionFeedback.Verdict.SE;
            default -> null;
        };
    }

    static ToolResult<Object> success(Object data) {
        return ToolResult.success(null, data, List.of(), DataClassification.STAFF_ONLY,
                TrustLevel.SERVER_AUTHORITATIVE);
    }

    /** Draft tools run in the draft scope and never audit their arguments; every one fails open. */
    static ToolDescriptor descriptor(String name, String description, JsonNode schema,
                                     String budgetCategory, ExecutionClass executionClass, Duration timeout) {
        return new ToolDescriptor(name, "1.0.0", description, schema, Set.of(ProblemDraftWorkspaceService.SCOPE),
                4000, timeout, budgetCategory, executionClass, FailurePolicy.FAIL_OPEN, ToolAuditLevel.NO_ARGUMENTS);
    }

    static ObjectNode objectSchema(ObjectMapper mapper, boolean additionalProperties) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", mapper.createObjectNode());
        schema.set("required", mapper.createArrayNode());
        schema.put("additionalProperties", additionalProperties);
        return schema;
    }

    static ObjectNode property(ObjectNode schema, String name, String type, String description) {
        ObjectNode value = schema.objectNode();
        value.put("type", type);
        value.put("description", description);
        ((ObjectNode) schema.get("properties")).set(name, value);
        return value;
    }

    static void require(ObjectNode schema, String... names) {
        ArrayNode required = (ArrayNode) schema.get("required");
        for (String name : names) {
            required.add(name);
        }
    }
}
