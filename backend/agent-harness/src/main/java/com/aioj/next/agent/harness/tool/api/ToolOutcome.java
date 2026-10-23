package com.aioj.next.agent.harness.tool.api;

/**
 * The pipeline's answer to one call: the result, the payload the model gets,
 * and whether the call failed a {@link FailurePolicy#FAIL_CLOSED} tool.
 */
public record ToolOutcome(
        String callId,
        String toolName,
        ToolResult result,
        String modelPayload,
        String decisionId,
        long durationMillis,
        boolean failedClosed
) {
}
