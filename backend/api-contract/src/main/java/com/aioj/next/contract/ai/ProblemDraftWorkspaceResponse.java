package com.aioj.next.contract.ai;

import java.time.Instant;
import java.util.Map;

public record ProblemDraftWorkspaceResponse(
        String id,
        Long creatorUserId,
        String conversationId,
        Long sourceDraftId,
        Long currentDraftId,
        Long currentAgentRunId,
        String title,
        String status,
        String stage,
        String agentMode,
        Long stateVersion,
        Integer requestedCfRating,
        ProblemDraftReviewRisk reviewRisk,
        String contentFingerprint,
        String verificationFingerprint,
        String safeSummary,
        Map<String, Object> resourceMetrics,
        ProblemDraftTestcaseArtifactSummary testcaseArtifact,
        ProblemDraftImportReadiness importReadiness,
        Instant createdAt,
        Instant updatedAt
) {
    public ProblemDraftWorkspaceResponse {
        resourceMetrics = resourceMetrics == null ? Map.of() : Map.copyOf(resourceMetrics);
    }
}
