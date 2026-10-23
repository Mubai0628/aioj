package com.aioj.next.ai.agent.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoopBudgetTest {

    @Test
    void chatBudgetsHaveNoDeadlineAndUnlimitedSandboxWork() {
        LoopBudget chat = new LoopBudget(8, 6, 3, 3);

        assertThat(chat.hasDeadline()).isFalse();
        assertThat(chat.maxSandboxCalls()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void draftBudgetsKeepTheirDeadline() {
        LoopBudget draft = new LoopBudget(20, 16, 0, 0, 8, 2, 2, Duration.ofMinutes(15));

        assertThat(draft.hasDeadline()).isTrue();
        assertThat(draft.maxRunDuration()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void negativeLimitsAreClamped() {
        LoopBudget budget = new LoopBudget(0, -1, -2, -3, -4, -5, -6, Duration.ofMinutes(-1));

        assertThat(budget.maxAgentSteps()).isEqualTo(1);
        assertThat(budget.maxToolCalls()).isZero();
        assertThat(budget.maxSearchCalls()).isZero();
        assertThat(budget.maxTestcasePackageRuns()).isZero();
        assertThat(budget.hasDeadline()).isFalse();
    }
}
