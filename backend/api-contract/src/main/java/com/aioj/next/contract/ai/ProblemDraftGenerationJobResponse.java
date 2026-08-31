package com.aioj.next.contract.ai;

import java.time.Instant;

public record ProblemDraftGenerationJobResponse(
        Long id,
        Long creatorUserId,
        String jobType,
        Long sourceDraftId,
        String status,
        String stage,
        String topicSnapshot,
        Integer progressCurrent,
        Integer progressTotal,
        String progressMessage,
        Long draftId,
        Integer errorCode,
        String errorKey,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        String workspaceId,
        Long agentRunId,
        Long stateVersion,
        Integer requestedCfRating,
        ProblemDraftReviewRisk reviewRisk,
        String agentMode,
        String contentFingerprint,
        String verificationFingerprint
) {
    public ProblemDraftGenerationJobResponse(
            Long id, Long creatorUserId, String jobType, Long sourceDraftId, String status, String stage,
            String topicSnapshot, Integer progressCurrent, Integer progressTotal, String progressMessage,
            Long draftId, Integer errorCode, String errorKey, String errorMessage, Instant startedAt,
            Instant completedAt, Instant createdAt, Instant updatedAt
    ) {
        this(id, creatorUserId, jobType, sourceDraftId, status, stage, topicSnapshot, progressCurrent,
                progressTotal, progressMessage, draftId, errorCode, errorKey, errorMessage, startedAt,
                completedAt, createdAt, updatedAt, null, null, 0L, null, null, null, null, null);
    }
}
