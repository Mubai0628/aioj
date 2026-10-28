package com.aioj.next.agent.harness.context.api;

/** One earlier message of the conversation, carried by a conversational section. */
public record ContextTurn(Role role, String content) {
    public enum Role {
        USER,
        ASSISTANT
    }

    public ContextTurn {
        if (role == null) {
            throw new IllegalArgumentException("turn role must not be null");
        }
        content = content == null ? "" : content;
    }
}
