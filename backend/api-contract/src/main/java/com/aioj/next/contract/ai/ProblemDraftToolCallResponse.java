package com.aioj.next.contract.ai;

import java.time.Instant;
import java.util.Map;

public record ProblemDraftToolCallResponse(
        Long id,
        Long agentRunId,
        Integer sequence,
        String toolName,
        String status,
        String resultClassification,
        String safeSummary,
        String resourceRefType,
        String resourceRefId,
        Map<String, Object> resourceMetrics,
        Integer latencyMillis,
        String errorCode,
        Instant startedAt,
        Instant completedAt
) {
    public ProblemDraftToolCallResponse {
        resourceMetrics = resourceMetrics == null ? Map.of() : Map.copyOf(resourceMetrics);
    }
}
