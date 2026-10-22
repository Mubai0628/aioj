package com.aioj.next.contract.ai;

/** Server-derived review risk. Clients must not infer or override these flags. */
public record ProblemDraftReviewRisk(
        String code,
        String severity,
        boolean highDifficulty,
        boolean manualUnverified,
        String message
) {
}
