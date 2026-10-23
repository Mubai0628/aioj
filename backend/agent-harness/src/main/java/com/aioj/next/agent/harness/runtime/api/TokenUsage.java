package com.aioj.next.agent.harness.runtime.api;

public record TokenUsage(long inputTokens, long outputTokens, long cacheHitTokens) {
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                cacheHitTokens + other.cacheHitTokens);
    }
}
