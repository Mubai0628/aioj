package com.aioj.next.judge.domain;

import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.contract.submission.JudgePhase;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.judge.persistence.entity.JudgeAuditLogEntity;
import com.aioj.next.judge.persistence.entity.SubmissionCaseResultEntity;
import com.aioj.next.judge.persistence.entity.SubmissionEntity;
import com.aioj.next.judge.persistence.mapper.JudgeAuditLogMapper;
import com.aioj.next.judge.persistence.mapper.SubmissionCaseResultMapper;
import com.aioj.next.judge.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionJudgingServiceTest {
    private final SubmissionMapper submissionMapper = mock(SubmissionMapper.class);
    private final SubmissionCaseResultMapper caseResultMapper = mock(SubmissionCaseResultMapper.class);
    private final JudgeAuditLogMapper auditLogMapper = mock(JudgeAuditLogMapper.class);
    private final JudgedSubmissionEventClient eventClient = mock(JudgedSubmissionEventClient.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final SubmissionJudgingService service = new SubmissionJudgingService(
            submissionMapper,
            caseResultMapper,
            auditLogMapper,
            new JudgeWorkerProperties(),
            eventClient,
            new org.springframework.transaction.support.TransactionTemplate(transactions)
    );

    @BeforeEach
    void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SubmissionEntity.class);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void finishNotifiesJudgedSubmissionAfterTransactionCleanup() {
        when(submissionMapper.update(any(SubmissionEntity.class), any())).thenReturn(1);
        JudgeTaskMessage task = task();
        JudgeResult result = new JudgeResult(
                SubmissionStatus.WRONG_ANSWER,
                "Wrong answer",
                10L,
                1024L,
                Instant.parse("2026-06-25T00:00:00Z"),
                "stdout that must not be sent",
                "stderr that must not be sent",
                0,
                10L,
                null,
                null,
                List.of()
        );
        transactions.onCommit = () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            verify(eventClient, never()).notifyJudgedSubmission(any());
        };
        when(eventClient.notifyJudgedSubmission(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(transactions.cleaned).isTrue();
            return true;
        });

        boolean updated = service.finish(task, result);

        assertThat(updated).isTrue();
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isZero();
        ArgumentCaptor<AiJudgedSubmissionEventRequest> captor =
                ArgumentCaptor.forClass(AiJudgedSubmissionEventRequest.class);
        verify(eventClient).notifyJudgedSubmission(captor.capture());
        assertThat(captor.getValue().submissionId()).isEqualTo(123L);
        assertThat(captor.getValue().problemId()).isEqualTo(99L);
        assertThat(captor.getValue().userId()).isEqualTo(7L);
        assertThat(captor.getValue().status()).isEqualTo(SubmissionStatus.WRONG_ANSWER);
        assertThat(captor.getValue().language()).isEqualTo("cpp");
    }

    @Test
    @SuppressWarnings("unchecked")
    void finishStoresDiagnosticsAndWritesCaseResultsInOneBatch() {
        when(submissionMapper.update(any(SubmissionEntity.class), any())).thenReturn(1);
        List<JudgeCaseResult> cases = List.of(
                new JudgeCaseResult(5L, 50L, 1, "1", null, SubmissionStatus.ACCEPTED, BigDecimal.ONE, BigDecimal.ONE,
                        3L, 512L, "Accepted", JudgePhase.RUN, "Accepted", true),
                new JudgeCaseResult(5L, 51L, 2, "2", null, SubmissionStatus.TIME_LIMIT_EXCEEDED, BigDecimal.ZERO,
                        BigDecimal.ONE, 1001L, 512L, "Time Limit Exceeded", JudgePhase.RUN, "Time Limit Exceeded", false));
        JudgeResult result = new JudgeResult(SubmissionStatus.TIME_LIMIT_EXCEEDED, "Time Limit Exceeded", 1001L, 512L,
                Instant.parse("2026-06-25T00:00:00Z"), null, null, null, 1001L, null, null, cases)
                .withDiagnostics(JudgePhase.RUN, "Time Limit Exceeded", 4);

        service.finish(task(), result);

        ArgumentCaptor<SubmissionEntity> update = ArgumentCaptor.forClass(SubmissionEntity.class);
        verify(submissionMapper).update(update.capture(), any());
        assertThat(update.getValue().getJudgePhase()).isEqualTo(JudgePhase.RUN);
        assertThat(update.getValue().getSandboxStatus()).isEqualTo("Time Limit Exceeded");
        assertThat(update.getValue().getCaseTotal()).isEqualTo(4);
        ArgumentCaptor<List<SubmissionCaseResultEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(caseResultMapper).insertBatch(rows.capture());
        verify(caseResultMapper, never()).insert(any(SubmissionCaseResultEntity.class));
        assertThat(rows.getValue()).hasSize(2);
        assertThat(rows.getValue()).allSatisfy(row -> assertThat(row.getId()).isNotNull());
        assertThat(rows.getValue().get(0).getSample()).isTrue();
        assertThat(rows.getValue().get(1).getJudgePhase()).isEqualTo(JudgePhase.RUN);
        assertThat(rows.getValue().get(1).getSandboxStatus()).isEqualTo("Time Limit Exceeded");
        ArgumentCaptor<JudgeAuditLogEntity> audit = ArgumentCaptor.forClass(JudgeAuditLogEntity.class);
        verify(auditLogMapper).insert(audit.capture());
        assertThat(audit.getValue().getJudgePhase()).isEqualTo(JudgePhase.RUN);
    }

    @Test
    void finishSkippedUpdateDoesNotNotify() {
        when(submissionMapper.update(any(SubmissionEntity.class), any())).thenReturn(0);

        boolean updated = service.finish(task(), JudgeResult.systemError("Sandbox unavailable"));

        assertThat(updated).isFalse();
        verify(eventClient, never()).notifyJudgedSubmission(any());
    }

    @Test
    void systemErrorNotifiesWithDatabaseSubmissionFields() {
        when(submissionMapper.update(any(SubmissionEntity.class), any())).thenReturn(1);
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(123L);
        submission.setProblemId(99L);
        submission.setUserId(7L);
        submission.setLanguage("python");
        submission.setContestId(1L);
        submission.setContestRunId(2L);
        submission.setContestProblemId(3L);
        when(submissionMapper.selectById(123L)).thenReturn(submission);

        service.markSystemError(123L, "sandbox error");

        ArgumentCaptor<AiJudgedSubmissionEventRequest> captor =
                ArgumentCaptor.forClass(AiJudgedSubmissionEventRequest.class);
        verify(eventClient).notifyJudgedSubmission(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
        assertThat(captor.getValue().contestRunId()).isEqualTo(2L);
        verify(auditLogMapper).insert(any(JudgeAuditLogEntity.class));
    }

    @Test
    void rollbackDoesNotSendAnEvent() {
        when(submissionMapper.update(any(SubmissionEntity.class), any())).thenThrow(new IllegalStateException("write failed"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.finish(task(), JudgeResult.systemError("failure")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
        verify(eventClient, never()).notifyJudgedSubmission(any());
    }

    @Test
    void notificationFailureCannotRollBackTheCommittedResult() {
        when(submissionMapper.update(any(SubmissionEntity.class), any())).thenReturn(1);
        when(eventClient.notifyJudgedSubmission(any())).thenThrow(new org.springframework.web.client.ResourceAccessException("down"));
        assertThat(service.finish(task(), JudgeResult.systemError("sandbox error"))).isTrue();
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isZero();
    }

    private static final class RecordingTransactions extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;
        private boolean cleaned;
        private Runnable onCommit = () -> { };
        @Override
        protected Object doGetTransaction() { return new Object(); }
        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { cleaned = false; }
        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { onCommit.run(); commits++; }
        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { rollbacks++; }
        @Override
        protected void doCleanupAfterCompletion(Object transaction) { cleaned = true; }
    }

    private JudgeTaskMessage task() {
        return new JudgeTaskMessage(
                123L,
                99L,
                7L,
                1L,
                2L,
                3L,
                4L,
                null,
                "cpp",
                "trace",
                1000,
                262144L
        );
    }
}
