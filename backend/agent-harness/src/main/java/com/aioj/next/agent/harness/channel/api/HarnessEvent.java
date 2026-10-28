package com.aioj.next.agent.harness.channel.api;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * The envelope of everything published on the channel (plan §3.3, A3.3): what happened
 * ({@code type} at {@code schemaVersion}), who published it, whom it is about, how sensitive
 * its payload is, and the trace it belongs to. {@code idempotencyKey} names the occurrence:
 * publishing the same key twice stores and delivers the event once. {@code id} is assigned by
 * the channel when the event is stored. The payload must already be redacted.
 */
public record HarnessEvent(String id, String type, int schemaVersion, Instant occurredAt, String producer,
                           EventSubject subject, String sensitivity, String idempotencyKey, JsonNode payload,
                           String traceId) {
    public HarnessEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type must not be blank");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schema version starts at 1");
        }
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("event producer must not be blank");
        }
        subject = subject == null ? EventSubject.NONE : subject;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public static Builder builder(String type, String idempotencyKey, String producer) {
        return new Builder(type, idempotencyKey, producer);
    }

    /** The same event with the id the channel stored it under. */
    public HarnessEvent withId(String storedId) {
        return new HarnessEvent(storedId, type, schemaVersion, occurredAt, producer, subject, sensitivity,
                idempotencyKey, payload, traceId);
    }

    public static final class Builder {
        private final String type;
        private final String idempotencyKey;
        private final String producer;
        private int schemaVersion = 1;
        private Instant occurredAt;
        private EventSubject subject = EventSubject.NONE;
        private String sensitivity;
        private JsonNode payload;
        private String traceId;

        private Builder(String type, String idempotencyKey, String producer) {
            this.type = type;
            this.idempotencyKey = idempotencyKey;
            this.producer = producer;
        }

        public Builder schemaVersion(int value) {
            this.schemaVersion = value;
            return this;
        }

        public Builder occurredAt(Instant value) {
            this.occurredAt = value;
            return this;
        }

        public Builder subject(EventSubject value) {
            this.subject = value;
            return this;
        }

        public Builder sensitivity(String value) {
            this.sensitivity = value;
            return this;
        }

        public Builder payload(JsonNode value) {
            this.payload = value;
            return this;
        }

        public Builder traceId(String value) {
            this.traceId = value;
            return this;
        }

        public HarnessEvent build() {
            return new HarnessEvent(null, type, schemaVersion, occurredAt, producer, subject, sensitivity,
                    idempotencyKey, payload, traceId);
        }
    }
}
