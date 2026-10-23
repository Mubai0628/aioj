package com.aioj.next.agent.harness.runtime.api;

/**
 * How a {@link ModelPort} reports a failed call. The message must be safe to
 * log: no Provider response body, no prompt text, no keys.
 */
public class ModelCallException extends RuntimeException {
    public enum Kind {
        TIMEOUT,
        RATE_LIMITED,
        UNAVAILABLE,
        REJECTED,
        AUTHENTICATION,
        INVALID_RESPONSE,
        CANCELLED
    }

    private final Kind kind;

    public ModelCallException(Kind kind, String safeMessage) {
        super(safeMessage);
        this.kind = kind == null ? Kind.UNAVAILABLE : kind;
    }

    public Kind kind() {
        return kind;
    }
}
