package com.aioj.next.agent.harness.sandbox.api;

/**
 * What one execution produced, already cut to the request's output limit.
 * {@code UNAVAILABLE} means the executor could not run the program at all.
 */
public record ExecutionFeedback(Status status, String stdout, String stderr, String compileOutput, long cpuMillis,
                                long memoryBytes, boolean outputTruncated) {
    public enum Status {
        OK,
        COMPILE_ERROR,
        RUNTIME_ERROR,
        TIME_LIMIT,
        MEMORY_LIMIT,
        OUTPUT_LIMIT,
        REJECTED,
        UNAVAILABLE
    }

    public ExecutionFeedback {
        status = status == null ? Status.UNAVAILABLE : status;
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        compileOutput = compileOutput == null ? "" : compileOutput;
    }
}
