package com.aioj.next.contract.ai;

/**
 * A confirmed weakness of one student, as problem recommendations use it.
 * {@code profileKey} is the normalized knowledge node (snake_case, CJK kept).
 */
public record AiRecommendationProfileSignal(
        String profileKey,
        String label,
        double confidence,
        int evidenceCount
) {
}
