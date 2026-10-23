package com.aioj.next.agent.harness.sandbox.api;

/** One program run: compile if the language needs it, then run once with {@code stdin}. */
public record ExecutionRequest(String language, String source, String stdin, ExecutionLimits limits) {
    public ExecutionRequest {
        if (language == null || language.isBlank() || source == null) {
            throw new IllegalArgumentException("language and source are required");
        }
        stdin = stdin == null ? "" : stdin;
        if (limits == null) {
            throw new IllegalArgumentException("limits are required");
        }
    }
}
