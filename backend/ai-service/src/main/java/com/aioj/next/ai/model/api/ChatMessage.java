package com.aioj.next.ai.model.api;

import java.util.List;

/**
 * Provider-neutral chat message.
 *
 * <p>{@code reasoningContent} is an in-memory transport field. Providers such as
 * DeepSeek require it on every assistant tool call sent back within one tool loop,
 * but it must never be copied to conversation messages, manifests, audit rows,
 * logs or user-facing responses.</p>
 */
public record ChatMessage(ChatRole role, String content, String reasoningContent,
                          String toolCallId, List<ToolCall> toolCalls) {

    public ChatMessage {
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content, null, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, null, toolCalls);
    }

    /** An assistant turn kept only inside one tool loop, with the reasoning that produced it. */
    public static ChatMessage assistant(String content, String reasoningContent, List<ToolCall> toolCalls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, reasoningContent, null, toolCalls);
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(ChatRole.TOOL, content, null, toolCallId, null);
    }
}
