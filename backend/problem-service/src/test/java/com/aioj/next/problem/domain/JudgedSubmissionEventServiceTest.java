package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.domain.recommendation.ProblemRecommendationCache;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JudgedSubmissionEventServiceTest {
    private final SubmissionMapper submissionMapper = mock(SubmissionMapper.class);
    private final JudgedSubmissionAiEventClient aiEventClient = mock(JudgedSubmissionAiEventClient.class);
    private final ProblemRecommendationCache recommendationCache = mock(ProblemRecommendationCache.class);
    private final ContestRunMapper contestRunMapper = mock(ContestRunMapper.class);
    private final JudgedSubmissionEventService service =
            new JudgedSubmissionEventService(submissionMapper, aiEventClient, recommendationCache, contestRunMapper);

    @Test
    void terminalSubmissionIsForwardedUsingDatabaseFields() {
        SubmissionEntity submission = submission(123L, SubmissionStatus.WRONG_ANSWER);
        submission.setContestId(1L);
        submission.setContestRunId(2L);
        submission.setContestProblemId(3L);
        when(submissionMapper.selectById(123L)).thenReturn(submission);

        service.handle(new AiJudgedSubmissionEventRequest(
                123L,
                999L,
                888L,
                SubmissionStatus.ACCEPTED,
                "python",
                null,
                null,
                null,
                Instant.parse("2026-06-25T00:00:00Z")
        ));

        ArgumentCaptor<AiJudgedSubmissionEventRequest> captor =
                ArgumentCaptor.forClass(AiJudgedSubmissionEventRequest.class);
        verify(aiEventClient).notifyJudgedSubmission(captor.capture());
        assertThat(captor.getValue().problemId()).isEqualTo(99L);
        assertThat(captor.getValue().userId()).isEqualTo(7L);
        assertThat(captor.getValue().status()).isEqualTo(SubmissionStatus.WRONG_ANSWER);
        assertThat(captor.getValue().contestRunId()).isEqualTo(2L);
        verify(recommendationCache, never()).evict(any());
    }

    @Test
    void aSubmissionInARunWithAiTurnedOffIsNotSentForAiAnalysis() {
        SubmissionEntity submission = submission(123L, SubmissionStatus.WRONG_ANSWER);
        submission.setContestId(1L);
        submission.setContestRunId(2L);
        when(submissionMapper.selectById(123L)).thenReturn(submission);
        when(contestRunMapper.selectById(2L)).thenReturn(run(ContestAiPolicyMode.NO_AI));

        service.handle(new AiJudgedSubmissionEventRequest(123L, 99L, 7L, SubmissionStatus.WRONG_ANSWER, "cpp",
                1L, 2L, 3L, null));

        verify(aiEventClient, never()).notifyJudgedSubmission(any());
        when(contestRunMapper.selectById(2L)).thenReturn(run(ContestAiPolicyMode.STRICT));
        service.handle(new AiJudgedSubmissionEventRequest(123L, 99L, 7L, SubmissionStatus.WRONG_ANSWER, "cpp",
                1L, 2L, 3L, null));
        verify(aiEventClient).notifyJudgedSubmission(any());
    }

    @Test
    void practiceVerdictClearsTheStudentsRecommendations() {
        when(submissionMapper.selectById(123L)).thenReturn(submission(123L, SubmissionStatus.ACCEPTED));

        service.handle(new AiJudgedSubmissionEventRequest(123L, 99L, 7L, SubmissionStatus.ACCEPTED, "cpp",
                null, null, null, null));

        verify(recommendationCache).evict(7L);
    }

    @Test
    void runningSubmissionIsRejected() {
        when(submissionMapper.selectById(123L)).thenReturn(submission(123L, SubmissionStatus.RUNNING));

        assertThatThrownBy(() -> service.handle(new AiJudgedSubmissionEventRequest(
                123L,
                99L,
                7L,
                SubmissionStatus.RUNNING,
                "cpp",
                null,
                null,
                null,
                null
        ))).isInstanceOf(DomainException.class)
                .hasMessageContaining("still being judged");
        verify(aiEventClient, never()).notifyJudgedSubmission(any());
    }

    private ContestRunEntity run(ContestAiPolicyMode mode) {
        ContestRunEntity run = new ContestRunEntity();
        run.setId(2L);
        run.setAiPolicyModeSnapshot(mode);
        return run;
    }

    private SubmissionEntity submission(Long id, SubmissionStatus status) {
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(id);
        submission.setProblemId(99L);
        submission.setUserId(7L);
        submission.setStatus(status);
        submission.setLanguage("cpp");
        submission.setJudgedAt(Instant.parse("2026-06-25T00:00:00Z"));
        return submission;
    }
}
