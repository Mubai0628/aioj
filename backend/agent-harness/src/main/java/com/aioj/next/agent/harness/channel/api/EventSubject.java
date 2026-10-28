package com.aioj.next.agent.harness.channel.api;

/** Whom an event is about; any of the identifiers may be absent. */
public record EventSubject(String userId, String conversationId, String turnId, String workspaceId) {
    public static final EventSubject NONE = new EventSubject(null, null, null, null);

    public static EventSubject user(String userId) {
        return new EventSubject(userId, null, null, null);
    }
}
