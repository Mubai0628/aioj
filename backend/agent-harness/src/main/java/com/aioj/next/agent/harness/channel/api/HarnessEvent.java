package com.aioj.next.agent.harness.channel.api;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * The envelope of everything published on the channel (plan §3.3).
 * {@code idempotencyKey} lets a subscriber that sees an event twice handle it
 * once.
 */
public record HarnessEvent(String id, String type, Instant occurredAt, String idempotencyKey, JsonNode payload) {
    public HarnessEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type must not be blank");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
    }
}
