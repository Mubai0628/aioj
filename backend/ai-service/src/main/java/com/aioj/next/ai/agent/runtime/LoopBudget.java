package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.config.AiProperties;

import java.time.Duration;

/**
 * Per-run loop budget (design doc §4.2). Bounds total model steps, total tool
 * calls, and per-category calls (search/fetch/sandbox) so a model cannot loop
 * forever or hammer one tool category. A tool's category is in its descriptor.
 */
public record LoopBudget(
        int maxAgentSteps,
        int maxToolCalls,
        int maxSearchCalls,
        int maxFetchCalls,
        int maxSandboxCalls,
        int maxTestcaseGenerations,
        int maxTestcasePackageRuns,
        long maxRunDurationMillis
) {

    /** Existing chat callers retain their four-dimensional budget unchanged. */
    public LoopBudget(int maxAgentSteps, int maxToolCalls, int maxSearchCalls, int maxFetchCalls) {
        this(maxAgentSteps, maxToolCalls, maxSearchCalls, maxFetchCalls,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0L);
    }

    public LoopBudget(int maxAgentSteps, int maxToolCalls, int maxSearchCalls, int maxFetchCalls,
                      int maxSandboxCalls, int maxTestcaseGenerations, int maxTestcasePackageRuns,
                      Duration maxRunDuration) {
        this(maxAgentSteps, maxToolCalls, maxSearchCalls, maxFetchCalls, maxSandboxCalls,
                maxTestcaseGenerations, maxTestcasePackageRuns,
                maxRunDuration == null || maxRunDuration.isNegative() ? 0L : maxRunDuration.toMillis());
    }

    public LoopBudget {
        maxAgentSteps = Math.max(1, maxAgentSteps);
        maxToolCalls = Math.max(0, maxToolCalls);
        maxSearchCalls = Math.max(0, maxSearchCalls);
        maxFetchCalls = Math.max(0, maxFetchCalls);
        maxSandboxCalls = Math.max(0, maxSandboxCalls);
        maxTestcaseGenerations = Math.max(0, maxTestcaseGenerations);
        maxTestcasePackageRuns = Math.max(0, maxTestcasePackageRuns);
        maxRunDurationMillis = Math.max(0L, maxRunDurationMillis);
    }

    public static LoopBudget from(AiProperties.AgentCore config) {
        return new LoopBudget(config.getMaxAgentSteps(), config.getMaxToolCalls(),
                config.getMaxSearchCalls(), config.getMaxFetchCalls());
    }

    public boolean hasDeadline() {
        return maxRunDurationMillis > 0L;
    }

    public Duration maxRunDuration() {
        return Duration.ofMillis(maxRunDurationMillis);
    }
}
