package com.aioj.next.contract.ai;

import java.time.Instant;

public record ProblemDraftWorkspaceMessageResponse(
        Long id,
        String workspaceId,
        String role,
        String content,
        String status,
        String model,
        Instant createdAt,
        Instant completedAt
) {
}
