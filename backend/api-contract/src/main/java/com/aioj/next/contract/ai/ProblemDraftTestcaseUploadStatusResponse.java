package com.aioj.next.contract.ai;

import java.time.Instant;

public record ProblemDraftTestcaseUploadStatusResponse(
        String uploadId,
        String workspaceId,
        String status,
        Integer receivedChunkCount,
        Integer chunkCount,
        Long uploadedSizeBytes,
        Long expectedSizeBytes,
        Long artifactId,
        String errorCode,
        String errorMessage,
        Instant expiresAt,
        Instant updatedAt,
        Instant completedAt
) {
}
