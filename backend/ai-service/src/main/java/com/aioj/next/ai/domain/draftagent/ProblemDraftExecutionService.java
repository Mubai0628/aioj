package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.aioj.next.ai.persistence.entity.ProblemDraftExecutionEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftExecutionMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import tools.jackson.databind.JsonNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProblemDraftExecutionService {
    private static final int MAX_SOURCE_BYTES = 256 * 1024;
    private static final int MAX_INPUT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CAPTURE_BYTES = 512 * 1024;
    private static final Set<String> LANGUAGES = Set.of("cpp", "java", "python");

    private final ProblemDraftExecutionMapper mapper;
    private final DraftSandboxClient sandboxClient;

    public ProblemDraftExecutionService(ProblemDraftExecutionMapper mapper, DraftSandboxClient sandboxClient) {
        this.mapper = mapper;
        this.sandboxClient = sandboxClient;
    }

    @Transactional
    public ProblemDraftExecutionEntity execute(String workspaceId, long userId, Long agentRunId,
                                               String toolCallId, String purpose, String language,
                                               String sourceCode, String stdin,
                                               Integer timeLimitMillis, Integer memoryLimitKb) {
        String normalizedLanguage = normalizeLanguage(language);
        requireBounded(sourceCode, MAX_SOURCE_BYTES, "sourceCode");
        requireBounded(stdin, MAX_INPUT_BYTES, "stdin");
        LocalDateTime now = LocalDateTime.now();
        ProblemDraftExecutionEntity entity = new ProblemDraftExecutionEntity();
        entity.setId(UUID.randomUUID().toString().replace("-", ""));
        entity.setWorkspaceId(workspaceId);
        entity.setCreatorUserId(userId);
        entity.setAgentRunId(agentRunId);
        entity.setToolCallId(toolCallId);
        entity.setPurpose(purpose == null || purpose.isBlank() ? "PROBE" : purpose.trim().toUpperCase(Locale.ROOT));
        entity.setLanguage(normalizedLanguage);
        entity.setStatus("RUNNING");
        entity.setStdout("");
        entity.setStderr("");
        entity.setStdoutBytes(0L);
        entity.setStderrBytes(0L);
        entity.setOutputTruncated(false);
        entity.setExpiresAt(now.plusHours(24));
        entity.setCreatedAt(now);
        mapper.insert(entity);
        try {
            DraftSandboxClient.CodeExecutionResult result = sandboxClient.executeCode(
                    normalizedLanguage, sourceCode, stdin,
                    clamp(timeLimitMillis, 50, 30_000, 2000),
                    clamp(memoryLimitKb, 16 * 1024, sandboxClient.maxMemoryLimitKb(),
                            Math.min(262_144, sandboxClient.maxMemoryLimitKb())),
                    MAX_CAPTURE_BYTES
            );
            String stdout = result.stdout() == null ? "" : result.stdout();
            String stderr = result.stderr() == null ? "" : result.stderr();
            entity.setStatus(result.status());
            entity.setStdout(truncateBytes(stdout, MAX_CAPTURE_BYTES));
            entity.setStderr(truncateBytes(stderr, MAX_CAPTURE_BYTES));
            entity.setStdoutBytes((long) stdout.getBytes(StandardCharsets.UTF_8).length);
            entity.setStderrBytes((long) stderr.getBytes(StandardCharsets.UTF_8).length);
            entity.setOutputTruncated(!stdout.equals(entity.getStdout()) || !stderr.equals(entity.getStderr()));
            entity.setResultHash(sha256(result.status() + "\n" + entity.getStdout() + "\n" + entity.getStderr()));
            entity.setTimeMillis(result.timeMillis());
            entity.setMemoryKb(result.memoryKb());
            entity.setProcPeak(result.procPeak() == null ? null : result.procPeak().longValue());
            entity.setExitStatus(result.exitStatus());
            entity.setRunTimeMillis(result.runTimeMillis());
            entity.setCompletedAt(LocalDateTime.now());
            mapper.updateById(entity);
            return entity;
        } catch (RuntimeException ex) {
            entity.setStatus("FAILED");
            entity.setStderr("Sandbox execution failed");
            entity.setResultHash(sha256("FAILED"));
            entity.setCompletedAt(LocalDateTime.now());
            mapper.updateById(entity);
            throw ex;
        }
    }

    public Map<String, Object> result(String workspaceId, long userId, String executionId,
                                      Integer offset, Integer limit) {
        ProblemDraftExecutionEntity execution = mapper.selectOne(new QueryWrapper<ProblemDraftExecutionEntity>()
                .eq("id", executionId)
                .eq("workspace_id", workspaceId)
                .eq("creator_user_id", userId)
                .gt("expires_at", LocalDateTime.now())
                .last("LIMIT 1"));
        if (execution == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft execution result not found");
        }
        int safeOffset = Math.max(0, offset == null ? 0 : offset);
        int safeLimit = Math.min(64 * 1024, Math.max(1, limit == null ? 16 * 1024 : limit));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("executionId", execution.getId());
        response.put("status", execution.getStatus());
        response.put("stdout", segment(execution.getStdout(), safeOffset, safeLimit));
        response.put("stderr", segment(execution.getStderr(), safeOffset, safeLimit));
        response.put("nextOffset", Math.min(maxLength(execution), safeOffset + safeLimit));
        response.put("hasMore", maxLength(execution) > safeOffset + safeLimit);
        response.put("outputTruncated", Boolean.TRUE.equals(execution.getOutputTruncated()));
        response.put("timeMillis", execution.getTimeMillis());
        response.put("memoryKb", execution.getMemoryKb());
        response.put("procPeak", execution.getProcPeak());
        response.put("exitStatus", execution.getExitStatus());
        response.put("runTimeMillis", execution.getRunTimeMillis());
        response.put("resultHash", execution.getResultHash());
        return response;
    }

    @Scheduled(fixedDelayString = "${aioj.ai.problem-draft.execution-cleanup-ms:3600000}")
    @Transactional
    public void cleanupExpired() {
        mapper.delete(new QueryWrapper<ProblemDraftExecutionEntity>().le("expires_at", LocalDateTime.now()));
    }

    private String normalizeLanguage(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "c++", "c++17", "cpp17" -> "cpp";
            case "py", "python3" -> "python";
            default -> normalized;
        };
        if (!LANGUAGES.contains(normalized)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported problem draft execution language");
        }
        return normalized;
    }

    private void requireBounded(String value, int maxBytes, String field) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new DomainException(ErrorCode.BAD_REQUEST, field + " exceeds the configured limit");
        }
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        int resolved = value == null ? fallback : value;
        return Math.min(max, Math.max(min, resolved));
    }

    private String truncateBytes(String value, int maxBytes) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value == null ? "" : value;
        }
        int end = Math.min(value.length(), maxBytes);
        while (end > 0 && value.substring(0, end).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            end--;
        }
        return value.substring(0, end);
    }

    private String segment(String value, int offset, int limit) {
        String source = value == null ? "" : value;
        int start = Math.min(source.length(), offset);
        return source.substring(start, Math.min(source.length(), start + limit));
    }

    private int maxLength(ProblemDraftExecutionEntity execution) {
        return Math.max(execution.getStdout() == null ? 0 : execution.getStdout().length(),
                execution.getStderr() == null ? 0 : execution.getStderr().length());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
