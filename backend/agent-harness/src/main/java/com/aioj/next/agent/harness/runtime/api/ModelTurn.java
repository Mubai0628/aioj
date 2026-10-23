package com.aioj.next.agent.harness.runtime.api;

import com.aioj.next.agent.harness.tool.api.ToolCallRequest;

import java.util.List;

/**
 * The model's answer to one call. {@code warnings} carry what the adapter did
 * on its own, for example switching off thinking after the Provider refused
 * it; the loop copies them into the run's warnings.
 */
public record ModelTurn(String content, String reasoningContent, List<ToolCallRequest> toolCalls, String finishReason,
                        TokenUsage usage, String provider, String model, List<String> warnings) {
    public ModelTurn {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? TokenUsage.ZERO : usage;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
