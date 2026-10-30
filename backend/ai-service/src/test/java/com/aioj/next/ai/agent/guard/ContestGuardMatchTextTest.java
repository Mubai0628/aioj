package com.aioj.next.ai.agent.guard;

import com.aioj.next.contract.contest.ContestAiPolicyMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContestGuardMatchTextTest {

    /** NO_AI denies at least what STRICT denies; its own participants are refused earlier, at L1. */
    @Test
    void noAiRunsRefuseProblemQuestionsLikeStrictOnes() {
        assertThat(ContestGuardMatchText.ruleOf("PUBLIC", "NO_AI"))
                .isEqualTo("refuse any question materially about this problem (NO_AI run policy)");
        assertThat(ContestGuardMatchText.ruleOf("PUBLIC", "STRICT"))
                .isEqualTo("refuse any question materially about this problem (STRICT run policy)");
        assertThat(ContestGuardMatchText.ruleOf("PUBLIC", "DEFAULT")).startsWith("public contest problem");
        assertThat(ContestGuardMatchText.ruleOf("PRIVATE", "DEFAULT")).startsWith("private contest problem");
    }

    @Test
    void onlyStrictAndNoAiRefuseProblemQuestions() {
        assertThat(ContestAiPolicyMode.NO_AI.refusesProblemQuestions()).isTrue();
        assertThat(ContestAiPolicyMode.STRICT.refusesProblemQuestions()).isTrue();
        assertThat(ContestAiPolicyMode.DEFAULT.refusesProblemQuestions()).isFalse();
        assertThat(ContestAiPolicyMode.DISABLED.refusesProblemQuestions()).isFalse();
        assertThat(ContestAiPolicyMode.refusesProblemQuestions((String) null)).isFalse();
    }
}
