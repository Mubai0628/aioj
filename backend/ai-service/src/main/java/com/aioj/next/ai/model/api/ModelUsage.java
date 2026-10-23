package com.aioj.next.ai.model.api;

/**
 * Provider-reported token usage of one call.
 *
 * @param cacheHitTokens prompt tokens served from the provider's prefix cache
 *                       (DeepSeek {@code prompt_cache_hit_tokens}, Kimi {@code cached_tokens})
 * @param reported       whether the provider sent usage at all; a missing report is
 *                       not the same as zero tokens
 */
public record ModelUsage(long promptTokens, long completionTokens, long cacheHitTokens, boolean reported) {

    public static final ModelUsage NONE = new ModelUsage(0, 0, 0, false);

    public ModelUsage {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        cacheHitTokens = Math.max(0, cacheHitTokens);
    }

    /** Usage of two calls together, for example a call and its retry without thinking. */
    public ModelUsage plus(ModelUsage other) {
        if (other == null) {
            return this;
        }
        return new ModelUsage(promptTokens + other.promptTokens, completionTokens + other.completionTokens,
                cacheHitTokens + other.cacheHitTokens, reported || other.reported);
    }
}
