package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.RunBudget;
import com.aioj.next.ai.agent.runtime.LoopBudget;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The loop budget as the Harness enforces it; each tool declares its category in its descriptor. */
class AgentRunBudgetTest {

    @Test
    void chatLimitsBecomeCategoryLimits() {
        RunBudget budget = HarnessAgentRunner.runBudget(new LoopBudget(8, 6, 3, 3));

        assertThat(budget.maxSteps()).isEqualTo(8);
        assertThat(budget.maxToolCalls()).isEqualTo(6);
        assertThat(budget.categoryLimits()).containsEntry("search", 3).containsEntry("fetch", 3);
        assertThat(budget.categoryLimits()).doesNotContainKey("other");
        assertThat(budget.hasDeadline()).isFalse();
    }

    @Test
    void draftPackageWorkAlsoCountsAgainstTheSandboxBudget() {
        RunBudget budget = HarnessAgentRunner.runBudget(
                new LoopBudget(20, 16, 0, 0, 8, 2, 2, Duration.ofMinutes(15)));

        assertThat(budget.categoryLimits()).containsEntry("sandbox", 8)
                .containsEntry("testcase_generate", 2).containsEntry("testcase_run", 2);
        assertThat(budget.chargedCategories("testcase_generate")).containsExactly("testcase_generate", "sandbox");
        assertThat(budget.chargedCategories("testcase_run")).containsExactly("testcase_run", "sandbox");
        assertThat(budget.chargedCategories("sandbox")).containsExactly("sandbox");
        assertThat(budget.maxRunDuration()).isEqualTo(Duration.ofMinutes(15));
    }
}
