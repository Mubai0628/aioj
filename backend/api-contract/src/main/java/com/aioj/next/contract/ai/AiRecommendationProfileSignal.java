package com.aioj.next.contract.ai;

public record AiRecommendationProfileSignal(
        String profileKey,
        String category,
        String polarity,
        Double confidence,
        Integer evidenceCount
) {
}