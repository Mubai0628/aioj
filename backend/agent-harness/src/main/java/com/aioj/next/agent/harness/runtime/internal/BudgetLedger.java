package com.aioj.next.agent.harness.runtime.internal;

import com.aioj.next.agent.harness.runtime.api.RunBudget;

import java.util.HashMap;
import java.util.Map;

/** Counts tool calls per budget category, charging each call to its category and the category's parents. */
final class BudgetLedger {
    private final RunBudget budget;
    private final Map<String, Integer> used = new HashMap<>();

    BudgetLedger(RunBudget budget) {
        this.budget = budget;
    }

    boolean exhausted(String category) {
        for (String charged : budget.chargedCategories(category)) {
            Integer limit = budget.categoryLimits().get(charged);
            if (limit != null && used.getOrDefault(charged, 0) >= limit) {
                return true;
            }
        }
        return false;
    }

    void charge(String category) {
        for (String charged : budget.chargedCategories(category)) {
            used.merge(charged, 1, Integer::sum);
        }
    }
}
