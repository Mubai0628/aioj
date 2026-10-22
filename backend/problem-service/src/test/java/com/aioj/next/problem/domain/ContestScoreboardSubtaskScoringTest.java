package com.aioj.next.problem.domain;

import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ProblemSubtaskEntity;
import com.aioj.next.problem.persistence.entity.SubmissionCaseResultEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContestScoreboardSubtaskScoringTest {
    @Test
    void zeroPointSampleCaseDoesNotZeroItsSubtask() {
        List<ProblemSubtaskEntity> subtasks = List.of(subtask("s1", 40), subtask("s2", 60));
        List<SubmissionCaseResultEntity> cases = List.of(
                testcase("s1", SubmissionStatus.ACCEPTED, "0", "0"),
                testcase("s1", SubmissionStatus.ACCEPTED, "20", "20"),
                testcase("s2", SubmissionStatus.ACCEPTED, "30", "30"),
                testcase("s2", SubmissionStatus.WRONG_ANSWER, "0", "30"));

        Map<String, BigDecimal> scores = ContestScoreboardService.scoreSubmissionSubtasks(subtasks, cases);

        assertThat(scores.get("s1")).isEqualByComparingTo("40");
        assertThat(scores.get("s2")).isEqualByComparingTo("0");
    }

    @Test
    void failedZeroPointCaseStillFailsItsSubtask() {
        List<ProblemSubtaskEntity> subtasks = List.of(subtask("s1", 40));
        List<SubmissionCaseResultEntity> cases = List.of(
                testcase("s1", SubmissionStatus.WRONG_ANSWER, "0", "0"),
                testcase("s1", SubmissionStatus.ACCEPTED, "20", "20"));

        assertThat(ContestScoreboardService.scoreSubmissionSubtasks(subtasks, cases).get("s1"))
                .isEqualByComparingTo("0");
    }

    @Test
    void subtaskWithoutRecordedCasesScoresZero() {
        Map<String, BigDecimal> scores = ContestScoreboardService.scoreSubmissionSubtasks(
                List.of(subtask("s1", 40)), List.of());

        assertThat(scores.get("s1")).isEqualByComparingTo("0");
    }

    private static ProblemSubtaskEntity subtask(String key, int score) {
        ProblemSubtaskEntity subtask = new ProblemSubtaskEntity();
        subtask.setSubtaskKey(key);
        subtask.setScore(BigDecimal.valueOf(score));
        return subtask;
    }

    private static SubmissionCaseResultEntity testcase(String subtask, SubmissionStatus status, String score,
                                                       String maxScore) {
        SubmissionCaseResultEntity testcase = new SubmissionCaseResultEntity();
        testcase.setSubtaskKey(subtask);
        testcase.setStatus(status);
        testcase.setScore(new BigDecimal(score));
        testcase.setMaxScore(new BigDecimal(maxScore));
        return testcase;
    }
}
