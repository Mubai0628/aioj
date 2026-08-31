package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.config.AiProperties;

import java.time.Duration;

/**
 * Per-run loop budget (design doc §4.2). Bounds total model steps, total tool
 * calls, and per-category calls (search/fetch) so a model cannot loop forever
 * or hammer one tool category.
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

    public String categoryOf(String toolName) {
        if ("draft.testcase.generate".equals(toolName)) {
            return "testcase_generate";
        }
        if ("draft.testcase.run_package".equals(toolName)) {
            return "testcase_run";
        }
        if ("draft.code.execute".equals(toolName) || "draft.samples.run".equals(toolName)) {
            return "sandbox";
        }
        if (toolName != null && (toolName.startsWith("context.search") || toolName.startsWith("memory.search")
                || toolName.startsWith("profile.search"))) {
            return "search";
        }
        if (toolName != null && (toolName.startsWith("context.fetch") || toolName.startsWith("memory.fetch"))) {
            return "fetch";
        }
        return "other";
    }

    /** Category-level cap; a call over its category cap is rejected as data, not executed. */
    public boolean categoryExhausted(String toolName, int searchCalls, int fetchCalls) {
        return categoryExhausted(toolName, searchCalls, fetchCalls, 0, 0, 0);
    }

    /** Category-level caps including draft Sandbox/package operations. */
    public boolean categoryExhausted(String toolName, int searchCalls, int fetchCalls,
                                     int sandboxCalls, int testcaseGenerations,
                                     int testcasePackageRuns) {
        return switch (categoryOf(toolName)) {
            case "search" -> searchCalls >= maxSearchCalls;
            case "fetch" -> fetchCalls >= maxFetchCalls;
            case "sandbox" -> sandboxCalls >= maxSandboxCalls;
            case "testcase_generate" -> sandboxCalls >= maxSandboxCalls
                    || testcaseGenerations >= maxTestcaseGenerations;
            case "testcase_run" -> sandboxCalls >= maxSandboxCalls
                    || testcasePackageRuns >= maxTestcasePackageRuns;
            default -> false;
        };
    }

    public boolean hasDeadline() {
        return maxRunDurationMillis > 0L;
    }

    public Duration maxRunDuration() {
        return Duration.ofMillis(maxRunDurationMillis);
    }
}
