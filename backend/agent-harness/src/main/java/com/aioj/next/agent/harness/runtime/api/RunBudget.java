package com.aioj.next.agent.harness.runtime.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounds of one run (plan §3.3): model steps, tool calls, per-category tool
 * calls and wall-clock time. A tool's category comes from its descriptor's
 * {@code budgetCategory}; a category may count against a parent as well, so
 * that, say, a batch execution also uses up the plain execution budget.
 * Categories without a limit are unlimited.
 */
public record RunBudget(int maxSteps, int maxToolCalls, Map<String, Integer> categoryLimits,
                        Map<String, String> categoryParents, Duration maxRunDuration) {
    public RunBudget {
        maxSteps = Math.max(1, maxSteps);
        maxToolCalls = Math.max(0, maxToolCalls);
        categoryLimits = categoryLimits == null ? Map.of() : Map.copyOf(categoryLimits);
        categoryParents = categoryParents == null ? Map.of() : Map.copyOf(categoryParents);
        maxRunDuration = maxRunDuration == null || maxRunDuration.isNegative() ? Duration.ZERO : maxRunDuration;
        for (String category : categoryParents.keySet()) {
            chain(category, categoryParents);
        }
    }

    public static Builder builder(int maxSteps, int maxToolCalls) {
        return new Builder(maxSteps, maxToolCalls);
    }

    public boolean hasDeadline() {
        return !maxRunDuration.isZero();
    }

    /** The category and its parents, nearest first. */
    public List<String> chargedCategories(String category) {
        return chain(category, categoryParents);
    }

    private static List<String> chain(String category, Map<String, String> parents) {
        List<String> chain = new ArrayList<>();
        for (String current = category; current != null; current = parents.get(current)) {
            if (chain.contains(current)) {
                throw new IllegalArgumentException("budget category cycle at " + current);
            }
            chain.add(current);
        }
        return chain;
    }

    public static final class Builder {
        private final int maxSteps;
        private final int maxToolCalls;
        private final Map<String, Integer> limits = new LinkedHashMap<>();
        private final Map<String, String> parents = new LinkedHashMap<>();
        private Duration maxRunDuration = Duration.ZERO;

        private Builder(int maxSteps, int maxToolCalls) {
            this.maxSteps = maxSteps;
            this.maxToolCalls = maxToolCalls;
        }

        public Builder limit(String category, int maxCalls) {
            limits.put(category, Math.max(0, maxCalls));
            return this;
        }

        /** Calls in {@code category} also count against {@code parent}. */
        public Builder within(String category, String parent) {
            parents.put(category, parent);
            return this;
        }

        public Builder maxRunDuration(Duration duration) {
            this.maxRunDuration = duration;
            return this;
        }

        public RunBudget build() {
            return new RunBudget(maxSteps, maxToolCalls, limits, parents, maxRunDuration);
        }
    }
}
