package com.aioj.next.ai.agent.model;

import java.util.List;

/**
 * Provider-neutral chat message for the agent loop.
 *
 * <p>{@code reasoningContent} is deliberately an in-memory transport field. It
 * is needed by providers such as DeepSeek when an assistant tool call is sent
 * back in the same model/tool loop, but must never be copied to conversation
 * messages, manifests, audit rows, logs, or user-facing responses.</p>
 */
public record GatewayMessage(String role, String content, String reasoningContent,
                             String toolCallId, List<GatewayToolCall> toolCalls) {

    /** Compatibility constructor for callers that do not carry provider reasoning. */
    public GatewayMessage(String role, String content, String toolCallId, List<GatewayToolCall> toolCalls) {
        this(role, content, null, toolCallId, toolCalls);
    }

    public static GatewayMessage system(String content) {
        return new GatewayMessage("system", content, null, null, null);
    }

    public static GatewayMessage user(String content) {
        return new GatewayMessage("user", content, null, null, null);
    }

    public static GatewayMessage assistant(String content, List<GatewayToolCall> toolCalls) {
        return assistant(content, null, toolCalls);
    }

    /** Assistant message retained only inside one provider tool loop. */
    public static GatewayMessage assistant(String content, String reasoningContent, List<GatewayToolCall> toolCalls) {
        return new GatewayMessage("assistant", content, reasoningContent, null,
                toolCalls == null ? List.of() : List.copyOf(toolCalls));
    }

    public static GatewayMessage toolResult(String callId, String content) {
        return new GatewayMessage("tool", content, null, callId, null);
    }
}
