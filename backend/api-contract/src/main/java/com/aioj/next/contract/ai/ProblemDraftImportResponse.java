package com.aioj.next.contract.ai;

import java.time.Instant;

public record ProblemDraftImportResponse(
        Long sagaId,
        String workspaceId,
        Long draftId,
        Long artifactId,
        String state,
        String importMode,
        Long problemId,
        Long problemTestcasePackageId,
        ProblemDraftImportReadiness readiness,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
