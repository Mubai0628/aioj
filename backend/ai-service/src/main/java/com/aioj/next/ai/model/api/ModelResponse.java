package com.aioj.next.ai.model.api;

import java.util.List;

/**
 * One model answer. {@code content} is never {@code null}: a tool-call answer with
 * {@code content: null} comes back as empty text. {@code reasoningContent} is for
 * the tool loop only (see {@link ChatMessage}).
 */
public record ModelResponse(String content, String reasoningContent, List<ToolCall> toolCalls,
                            String finishReason, ModelUsage usage, ProviderId provider, String model) {

    public ModelResponse {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        finishReason = finishReason == null ? "" : finishReason;
        usage = usage == null ? ModelUsage.NONE : usage;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
