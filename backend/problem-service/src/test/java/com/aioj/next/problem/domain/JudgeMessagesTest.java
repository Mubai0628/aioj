package com.aioj.next.problem.domain;

import com.aioj.next.contract.submission.JudgePhase;
import com.aioj.next.contract.submission.SubmissionStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan S7: a system error reaches the student as a key; the student's own verdict text is untouched. */
class JudgeMessagesTest {
    @Test
    void aSystemErrorIsAKeyEvenOnAnOlderRow() {
        assertThat(JudgeMessages.visible(SubmissionStatus.SYSTEM_ERROR, "judge.sandboxUnavailable"))
                .isEqualTo("judge.sandboxUnavailable");
        assertThat(JudgeMessages.visible(SubmissionStatus.SYSTEM_ERROR,
                "Sandbox execution failed: I/O error on POST request for \"http://sandbox:8090/run\" canary-5e7f"))
                .isEqualTo("judge.systemError");
        assertThat(JudgeMessages.visible(SubmissionStatus.SYSTEM_ERROR,
                "Custom checker compile failed: checker.cpp:3: error: canary-0a1b")).isEqualTo("judge.systemError");
    }

    @Test
    void otherVerdictsKeepTheirText() {
        assertThat(JudgeMessages.visible(SubmissionStatus.COMPILE_ERROR, "main.cpp:3: error: expected ';'"))
                .isEqualTo("main.cpp:3: error: expected ';'");
        assertThat(JudgeMessages.visible(SubmissionStatus.WRONG_ANSWER, "Line 3: expected 4, got 5"))
                .isEqualTo("Line 3: expected 4, got 5");
    }

    @Test
    void aBrokenCheckersStderrIsNotTheStudents() {
        assertThat(JudgeMessages.visibleStderr(SubmissionStatus.SYSTEM_ERROR, JudgePhase.CHECKER, "checker.cpp:3")).isNull();
        assertThat(JudgeMessages.visibleStderr(SubmissionStatus.RUNTIME_ERROR, JudgePhase.RUN, "Segmentation fault"))
                .isEqualTo("Segmentation fault");
    }
}
