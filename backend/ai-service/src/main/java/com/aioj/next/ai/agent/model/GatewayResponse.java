package com.aioj.next.ai.agent.model;

import java.util.List;

public record GatewayResponse(
        String content,
        String reasoningContent,
        List<GatewayToolCall> toolCalls,
        String finishReason,
        long promptTokens,
        long completionTokens,
        long cacheHitTokens,
        String provider,
        String model
) {
    public GatewayResponse {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** Compatibility constructor for providers/tests that do not expose reasoning. */
    public GatewayResponse(String content, List<GatewayToolCall> toolCalls, String finishReason,
                           long promptTokens, long completionTokens, long cacheHitTokens,
                           String provider, String model) {
        this(content, null, toolCalls, finishReason, promptTokens, completionTokens,
                cacheHitTokens, provider, model);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
