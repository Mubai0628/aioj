package com.aioj.next.agent.harness.runtime.api;

import com.aioj.next.agent.harness.tool.api.ToolCallRequest;

import java.util.List;

/**
 * A message of the loop's conversation with the model.
 *
 * <p>{@code reasoningContent} only travels inside one run: some Providers need
 * the full reasoning of every assistant tool call sent back (DeepSeek answers
 * HTTP 400 otherwise, plan appendix G). It must never be copied to stored
 * messages, manifests, audit rows, logs or user-facing output.</p>
 */
public record HarnessMessage(Role role, String content, String reasoningContent, String toolCallId,
                             List<ToolCallRequest> toolCalls) {
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public HarnessMessage {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static HarnessMessage system(String content) {
        return new HarnessMessage(Role.SYSTEM, content, null, null, List.of());
    }

    public static HarnessMessage user(String content) {
        return new HarnessMessage(Role.USER, content, null, null, List.of());
    }

    public static HarnessMessage assistant(String content, String reasoningContent, List<ToolCallRequest> toolCalls) {
        return new HarnessMessage(Role.ASSISTANT, content, reasoningContent, null, toolCalls);
    }

    public static HarnessMessage toolResult(String toolCallId, String payload) {
        return new HarnessMessage(Role.TOOL, payload, null, toolCallId, List.of());
    }
}
