package com.aioj.next.agent.harness.tool.api;

public enum ToolStatus {
    SUCCESS,
    NOT_FOUND,
    SCHEMA_ERROR,
    POLICY_DENIED,
    TIMEOUT,
    CANCELLED,
    EXECUTION_ERROR,
    SERVICE_UNAVAILABLE;

    /** Failures of the tool's infrastructure rather than of the model's call; {@link FailurePolicy} applies to these. */
    public boolean infrastructureFailure() {
        return this == TIMEOUT || this == EXECUTION_ERROR || this == SERVICE_UNAVAILABLE;
    }
}
