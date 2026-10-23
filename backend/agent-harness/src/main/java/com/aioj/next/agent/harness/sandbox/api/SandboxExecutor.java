package com.aioj.next.agent.harness.sandbox.api;

/**
 * Runs code for execution tools in a remote process (plan §3.3). It fails
 * closed: when the executor is unavailable the call fails, and it never falls
 * back to running code locally or on another sandbox.
 */
@FunctionalInterface
public interface SandboxExecutor {
    ExecutionFeedback execute(ExecutionRequest request);
}
