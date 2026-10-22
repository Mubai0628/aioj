package com.aioj.next.contract.ai;

import java.time.Instant;

public record ProblemDraftTestcaseUploadInitResponse(
        String uploadId,
        String workspaceId,
        String status,
        Integer chunkSizeBytes,
        Integer chunkCount,
        Instant expiresAt
) {
}
