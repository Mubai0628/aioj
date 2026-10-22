package com.aioj.next.ai.agent.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoopBudgetTest {

    private final LoopBudget chatBudget = new LoopBudget(8, 6, 3, 3);
    private final LoopBudget budget = new LoopBudget(20, 16, 0, 0,
            8, 2, 2, Duration.ofMinutes(15));

    @Test
    void contextToolsKeepTheirCategories() {
        assertThat(chatBudget.categoryOf("context.search_exact")).isEqualTo("search");
        assertThat(chatBudget.categoryOf("context.fetch_thread")).isEqualTo("fetch");
    }

    @Test
    void memoryToolsShareContextCategories() {
        assertThat(chatBudget.categoryOf("memory.search_claims")).isEqualTo("search");
        assertThat(chatBudget.categoryOf("memory.fetch_evidence")).isEqualTo("fetch");
    }

    @Test
    void memoryToolsConsumeTheSameCategoryBudget() {
        assertThat(chatBudget.categoryExhausted("memory.search_claims", 3, 0)).isTrue();
        assertThat(chatBudget.categoryExhausted("memory.search_claims", 2, 0)).isFalse();
        assertThat(chatBudget.categoryExhausted("memory.fetch_evidence", 0, 3)).isTrue();
        assertThat(chatBudget.categoryExhausted("memory.fetch_evidence", 0, 2)).isFalse();
    }

    @Test
    void profileSearchSharesTheSearchCategory() {
        assertThat(chatBudget.categoryOf("profile.search")).isEqualTo("search");
        assertThat(chatBudget.categoryExhausted("profile.search", 3, 0)).isTrue();
        assertThat(chatBudget.categoryExhausted("profile.search", 2, 0)).isFalse();
    }

    @Test
    void unknownAndNullToolsAreOther() {
        assertThat(chatBudget.categoryOf("judge.run")).isEqualTo("other");
        assertThat(chatBudget.categoryOf(null)).isEqualTo("other");
    }

    @Test
    void draftSandboxAndPackageLimitsAreIndependentAndCumulative() {
        assertThat(budget.categoryOf("draft.code.execute")).isEqualTo("sandbox");
        assertThat(budget.categoryOf("draft.testcase.generate")).isEqualTo("testcase_generate");
        assertThat(budget.categoryOf("draft.testcase.run_package")).isEqualTo("testcase_run");

        assertThat(budget.categoryExhausted("draft.testcase.generate", 0, 0, 1, 2, 0)).isTrue();
        assertThat(budget.categoryExhausted("draft.testcase.run_package", 0, 0, 1, 0, 2)).isTrue();
        assertThat(budget.categoryExhausted("draft.code.execute", 0, 0, 8, 0, 0)).isTrue();
        assertThat(budget.categoryExhausted("draft.samples.run", 0, 0, 7, 0, 0)).isFalse();
    }
}
