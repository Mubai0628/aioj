package com.aioj.next.agent.harness.runtime.api;

import java.util.List;

/** The typed end of a run: exceptions inside the loop become a status and a failure, never a throw. */
public record RunResult(
        RunStatus status,
        String content,
        int steps,
        int toolCallCount,
        TokenUsage usage,
        List<String> warnings,
        boolean missedRequiredTool,
        boolean completionSatisfied,
        RunFailure failure,
        String provider,
        String model
) {
    public RunResult {
        content = content == null ? "" : content;
        usage = usage == null ? TokenUsage.ZERO : usage;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean completed() {
        return status == RunStatus.COMPLETED;
    }
}
