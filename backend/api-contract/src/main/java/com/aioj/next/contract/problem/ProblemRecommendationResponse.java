package com.aioj.next.contract.problem;

import java.util.List;
import java.util.Map;

public record ProblemRecommendationResponse(
        Long problemId,
        String title,
        Difficulty difficulty,
        List<String> tags,
        double score,
        ProblemRecommendationReason reason,
        Map<String, String> reasonParams
) {
}
