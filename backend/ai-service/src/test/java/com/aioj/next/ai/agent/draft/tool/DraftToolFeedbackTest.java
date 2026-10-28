package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.ai.persistence.entity.ProblemDraftExecutionEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T1.11 (A5.1): the draft execution tools add the same feedback structure to what they returned. */
class DraftToolFeedbackTest {

    @Test
    void aCompileErrorIsLocated() {
        Map<String, Object> feedback = DraftToolSupport.feedback(
                execution("COMPILE_ERROR", "main.cpp:3:7: error: 'x' was not declared"));

        assertThat(feedback.get("verdict")).isEqualTo("CE");
        assertThat(feedback.get("executionId")).isEqualTo("exec-9");
        assertThat(feedback.get("diagnostics").toString()).contains("line=3", "column=7", "'x' was not declared");
    }

    @Test
    void aNormalFinishHasNoVerdictAndARuntimeErrorItsStderrSummary() {
        assertThat(DraftToolSupport.feedback(execution("ACCEPTED", "")).get("verdict")).isNull();

        Map<String, Object> crashed = DraftToolSupport.feedback(execution("RUNTIME_ERROR",
                "Exception in thread \"main\" java.lang.ArithmeticException: / by zero\n\tat Main.main(Main.java:5)"));
        assertThat(crashed.get("verdict")).isEqualTo("RE");
        assertThat(String.valueOf(crashed.get("stderrSummary"))).contains("ArithmeticException");
    }

    @Test
    void hiddenCasesGiveCountsFailureKindsAndResourcesOnly() {
        Map<String, Object> feedback = DraftToolSupport.casesFeedback(18, 20,
                Map.of("WRONG_ANSWER", 1, "TIME_LIMIT_EXCEEDED", 1), 950L, 65_536L, 1);

        assertThat(feedback).containsEntry("passed", 18).containsEntry("total", 20).containsEntry("maxTimeMillis", 950L);
        assertThat(feedback.get("verdict")).isNull();
        assertThat(feedback.get("failures")).isEqualTo(Map.of("WA", 1, "TLE", 1));
        assertThat(DraftToolSupport.casesFeedback(3, 3, null, 1L, 1L, 1).get("verdict")).isEqualTo("AC");
    }

    private static ProblemDraftExecutionEntity execution(String status, String stderr) {
        ProblemDraftExecutionEntity execution = new ProblemDraftExecutionEntity();
        execution.setId("exec-9");
        execution.setStatus(status);
        execution.setStderr(stderr);
        execution.setTimeMillis(12L);
        execution.setMemoryKb(2048L);
        execution.setProcPeak(1L);
        return execution;
    }
}
