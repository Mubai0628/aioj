package com.aioj.next.contract.ai;

import java.time.Instant;

public record ProblemDraftAgentRunResponse(
        Long id,
        String workspaceId,
        String status,
        String executionMode,
        String provider,
        String model,
        Integer stepCount,
        Integer toolCallCount,
        String safeSummary,
        String errorCode,
        Instant startedAt,
        Instant completedAt
) {
}
