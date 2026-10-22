package com.aioj.next.contract.problem;

import java.util.List;

/**
 * Recommended practice problems for the current student. {@code solvedProblems}
 * counts distinct problems solved in practice; contest submissions never count,
 * so hidden contest verdicts cannot leak through recommendations.
 */
public record ProblemRecommendationsResponse(
        List<ProblemRecommendationResponse> items,
        long solvedProblems,
        boolean coldStart
) {
}
