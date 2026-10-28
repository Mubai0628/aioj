package com.aioj.next.agent.harness.context.api;

/**
 * A policy contributor failed, ran out of time or could not be scheduled. The context it
 * would have added carries rules, so the run must stop (fail-closed); the caller decides how
 * the refusal reaches the user and what it records.
 */
public final class ContextPolicyException extends RuntimeException {
    private final String contributor;
    private final String reason;

    public ContextPolicyException(String contributor, String reason, Throwable cause) {
        super("policy context contributor " + contributor + " failed: " + reason, cause);
        this.contributor = contributor;
        this.reason = reason;
    }

    public String contributor() {
        return contributor;
    }

    /** {@code error}, {@code timeout}, {@code rejected} or {@code interrupted}. */
    public String reason() {
        return reason;
    }
}
