package com.aioj.next.ai.agent.port;

/**
 * A memory or profile read did not come back in time, could not be scheduled or failed
 * (plan §3.5: such ports time out and fail open). Callers degrade: a context section is
 * skipped, a tool answers SERVICE_UNAVAILABLE.
 */
public final class PortUnavailableException extends RuntimeException {
    private final String port;
    private final String reason;

    public PortUnavailableException(String port, String reason, Throwable cause) {
        super(port + " port unavailable: " + reason, cause);
        this.port = port;
        this.reason = reason;
    }

    public String port() {
        return port;
    }

    /** {@code timeout}, {@code rejected}, {@code interrupted} or {@code error}. */
    public String reason() {
        return reason;
    }
}
