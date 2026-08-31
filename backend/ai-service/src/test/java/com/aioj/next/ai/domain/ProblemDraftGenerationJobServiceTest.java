package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.draftagent.ProblemDraftAgentCoordinator;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.draftagent.ProblemDraftReviewRiskService;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkingState;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftGenerationJobEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftGenerationJobMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.ProblemDraftAgentTurnRequest;
import com.aioj.next.contract.ai.ProblemDraftRegenerateRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemDraftGenerationJobServiceTest {
    @Mock private ProblemDraftGenerationJobMapper jobMapper;
    @Mock private ProblemDraftStore problemDraftStore;
    @Mock private ProblemDraftAgentCoordinator coordinator;
    @Mock private ProblemDraftWorkspaceService workspaceService;
    @Mock private OperationAuditWriter auditWriter;

    private ProblemDraftGenerationJobService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(new AiProperties());
    }

    private ProblemDraftGenerationJobService serviceWith(AiProperties properties) {
        Executor directExecutor = Runnable::run;
        return new ProblemDraftGenerationJobService(jobMapper, problemDraftStore, coordinator,
                workspaceService, new ProblemDraftReviewRiskService(),
                new ObjectMapper().findAndRegisterModules(), auditWriter, directExecutor,
                transactionManager(), properties);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPersistsWorkspaceAndTurnBoundQueuedJob() {
        ProblemDraftRequest request = request("Segment tree sorting");
        when(coordinator.prepareInitial(7L, request)).thenReturn(prepared("ws-1", "turn-1", false));
        assignId(100L);

        var response = service.create(7L, request);

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor =
                ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper).insert(captor.capture());
        ProblemDraftGenerationJobEntity saved = captor.getValue();
        assertThat(saved.getWorkspaceId()).isEqualTo("ws-1");
        assertThat(saved.getTurnId()).isEqualTo("turn-1");
        assertThat(saved.getStatus()).isEqualTo("QUEUED");
        assertThat(saved.getStateVersion()).isEqualTo(3L);
        assertThat(response.workspaceId()).isEqualTo("ws-1");
        assertThat(response.reviewRisk().code()).isEqualTo("HIGH_DIFFICULTY_AI");
    }

    @Test
    void regenerationAlwaysInsertsANewAppendOnlyJob() {
        ProblemDraftResponse source = org.mockito.Mockito.mock(ProblemDraftResponse.class);
        when(source.title()).thenReturn("Existing draft");
        ProblemDraftRegenerateRequest request = new ProblemDraftRegenerateRequest("repair package coverage");
        when(problemDraftStore.regenerationSource(200L)).thenReturn(source);
        when(coordinator.prepareRegeneration(7L, source, request))
                .thenReturn(prepared("ws-2", "turn-2", false));
        assignId(101L);

        var response = service.createRegeneration(7L, 200L, request);

        verify(jobMapper).insert(any(ProblemDraftGenerationJobEntity.class));
        verify(jobMapper, never()).update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class));
        assertThat(response.id()).isEqualTo(101L);
        assertThat(response.jobType()).isEqualTo("REGENERATE");
        assertThat(response.workspaceId()).isEqualTo("ws-2");
    }

    @Test
    void continuationReplayReturnsExistingJobWithoutDuplicateInsert() {
        ProblemDraftAgentTurnRequest request = new ProblemDraftAgentTurnRequest(
                "client-1", 900L, "repair the boundary cases", "repair");
        ProblemDraftAgentCoordinator.PreparedTurn prepared = prepared("ws-3", "turn-3", true);
        when(coordinator.prepareContinuation(7L, "ws-3", request)).thenReturn(prepared);
        ProblemDraftGenerationJobEntity existing = job(103L, 7L, "SUCCEEDED");
        existing.setWorkspaceId("ws-3");
        existing.setTurnId("turn-3");
        existing.setAgentRunId(303L);
        when(jobMapper.selectOne(any())).thenReturn(existing);
        when(workspaceService.requireOwned("ws-3", 7L)).thenReturn(prepared.workspace());

        var response = service.continueOptimization(7L, "ws-3", request);

        assertThat(response.idempotentReplay()).isTrue();
        assertThat(response.generationJobId()).isEqualTo(103L);
        assertThat(response.agentRunId()).isEqualTo(303L);
        verify(jobMapper, never()).insert(any(ProblemDraftGenerationJobEntity.class));
    }

    @Test
    void continuationJobKeepsTheWorkspaceProblemTitle() {
        ProblemDraftAgentTurnRequest request = new ProblemDraftAgentTurnRequest(
                "client-2", 900L, "re-run package validation", "revalidate");
        ProblemDraftAgentCoordinator.PreparedTurn prepared = prepared("ws-4", "turn-4", false);
        when(coordinator.prepareContinuation(7L, "ws-4", request)).thenReturn(prepared);
        when(workspaceService.state(prepared.workspace())).thenReturn(
                ProblemDraftWorkingState.fromRequest(request("Dynamic range maximum profit")));
        assignId(104L);

        service.continueOptimization(7L, "ws-4", request);

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor =
                ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper).insert(captor.capture());
        assertThat(captor.getValue().getTopicSnapshot()).isEqualTo("Dynamic range maximum profit");
    }

    @Test
    void processUsesCoordinatorAndCompletesOnlyAfterFinalize() {
        ProblemDraftGenerationJobEntity job = job(100L, 7L, "QUEUED");
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        when(jobMapper.selectById(100L)).thenReturn(job);
        when(coordinator.execute(job)).thenReturn(outcome(true));

        service.process(100L);

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor =
                ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper, atLeast(2)).update(captor.capture(), any(UpdateWrapper.class));
        ProblemDraftGenerationJobEntity completed = captor.getAllValues().stream()
                .filter(value -> "SUCCEEDED".equals(value.getStatus())).findFirst().orElseThrow();
        assertThat(completed.getDraftId()).isEqualTo(900L);
        assertThat(completed.getAgentRunId()).isEqualTo(300L);
        verify(problemDraftStore, never()).generate(any(), any(), any(), any());
        verify(problemDraftStore, never()).regenerate(any(), any(), any(), any(), any());
    }

    @Test
    void unfinishedOrBudgetLimitedRunMovesJobToNeedsAttention() {
        ProblemDraftGenerationJobEntity job = job(100L, 7L, "QUEUED");
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        when(jobMapper.selectById(100L)).thenReturn(job);
        when(coordinator.execute(job)).thenReturn(outcome(false));

        service.process(100L);

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor =
                ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper, atLeast(2)).update(captor.capture(), any(UpdateWrapper.class));
        assertThat(captor.getAllValues()).anyMatch(value -> "NEEDS_ATTENTION".equals(value.getStatus()));
    }

    @Test
    void providerFailurePreservesWorkspaceAndMovesJobToNeedsAttention() {
        ProblemDraftGenerationJobEntity job = job(100L, 7L, "QUEUED");
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        when(jobMapper.selectById(100L)).thenReturn(job);
        when(coordinator.execute(job)).thenThrow(
                new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "provider timeout"));
        when(coordinator.runIdForTurn("turn-100")).thenReturn(301L);

        service.process(100L);

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor =
                ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper, atLeast(2)).update(captor.capture(), any(UpdateWrapper.class));
        ProblemDraftGenerationJobEntity attention = captor.getAllValues().stream()
                .filter(value -> "NEEDS_ATTENTION".equals(value.getStatus())).findFirst().orElseThrow();
        assertThat(attention.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.code());
        assertThat(attention.getAgentRunId()).isEqualTo(301L);
    }

    @Test
    void teacherCannotReadAnotherUsersJobButAdminCan() {
        ProblemDraftGenerationJobEntity job = job(100L, 8L, "SUCCEEDED");
        when(jobMapper.selectById(100L)).thenReturn(job);
        authenticate(7L, Role.TEACHER);
        assertThatThrownBy(() -> service.get(100L))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);

        authenticate(99L, Role.ADMIN);
        assertThat(service.get(100L).creatorUserId()).isEqualTo(8L);
    }

    @Test
    void claimDerivesLeaseFromRunBudgetPlusGraceMargin() {
        ProblemDraftGenerationJobEntity job = job(100L, 7L, "QUEUED");
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        when(jobMapper.selectById(100L)).thenReturn(job);
        when(coordinator.execute(job)).thenReturn(outcome(true));

        service.process(100L);

        ProblemDraftGenerationJobEntity claimed = claimUpdate();
        // Default maxRunMinutes=15 plus a 5-minute grace margin.
        assertThat(Duration.between(claimed.getStartedAt(), claimed.getLeaseExpiresAt()).toMinutes())
                .isEqualTo(20);
    }

    @Test
    void claimUsesExplicitJobLeaseOverride() {
        AiProperties properties = new AiProperties();
        properties.getProblemDraft().setMaxRunMinutes(15);
        properties.getProblemDraft().setJobLeaseMinutes(45);
        ProblemDraftGenerationJobService custom = serviceWith(properties);
        ProblemDraftGenerationJobEntity job = job(100L, 7L, "QUEUED");
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        when(jobMapper.selectById(100L)).thenReturn(job);
        when(coordinator.execute(job)).thenReturn(outcome(true));

        custom.process(100L);

        ProblemDraftGenerationJobEntity claimed = claimUpdate();
        assertThat(Duration.between(claimed.getStartedAt(), claimed.getLeaseExpiresAt()).toMinutes())
                .isEqualTo(45);
    }

    @Test
    void inFlightJobIsNotRecoveredEvenIfLeaseAppearsExpired() throws Exception {
        ProblemDraftGenerationJobEntity job = job(100L, 7L, "RUNNING");
        job.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        when(jobMapper.selectById(100L)).thenReturn(job);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(coordinator.execute(job)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return outcome(true);
        });
        // Recovery sees the lease-expired row; the queued poll sees nothing.
        when(jobMapper.selectList(any())).thenReturn(List.of(job), List.of());
        Thread worker = new Thread(() -> service.process(100L));
        worker.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            service.pollQueuedJobs();

            // Only the claim update ran; no terminal NEEDS_ATTENTION write while in flight.
            ArgumentCaptor<ProblemDraftGenerationJobEntity> captor =
                    ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
            verify(jobMapper, times(1)).update(captor.capture(), any(UpdateWrapper.class));
            assertThat(captor.getValue().getStatus()).isEqualTo("RUNNING");
        } finally {
            release.countDown();
            worker.join(5000);
        }
    }

    @Test
    void expiredRunningJobWithoutLocalExecutionMovesToNeedsAttention() {
        ProblemDraftGenerationJobEntity job = job(100L, 7L, "RUNNING");
        job.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(jobMapper.selectList(any())).thenReturn(List.of(job), List.of());
        when(jobMapper.selectById(100L)).thenReturn(job);
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);

        service.pollQueuedJobs();

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor =
                ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper).update(captor.capture(), any(UpdateWrapper.class));
        assertThat(captor.getValue().getStatus()).isEqualTo("NEEDS_ATTENTION");
        assertThat(captor.getValue().getErrorKey()).isEqualTo("agent.workerInterrupted");
    }

    @Test
    void terminalUpdateMatchingNoRowsIsToleratedAndNotAudited() {
        ProblemDraftGenerationJobEntity job = job(100L, 7L, "QUEUED");
        when(jobMapper.selectById(100L)).thenReturn(job);
        // The claim succeeds; the terminal completion silently matches no row.
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class)))
                .thenReturn(1, 0);
        when(coordinator.execute(job)).thenReturn(outcome(true));

        assertThatCode(() -> service.process(100L)).doesNotThrowAnyException();

        // Only the claim lifecycle audit was written; the lost terminal write is logged, not audited.
        verify(auditWriter, times(1)).replaceProblemDraftGenerationJobLifecycle(any(), any(), any(), any());
    }

    private ProblemDraftGenerationJobEntity claimUpdate() {
        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor =
                ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper, atLeast(1)).update(captor.capture(), any(UpdateWrapper.class));
        return captor.getAllValues().stream()
                .filter(value -> "RUNNING".equals(value.getStatus()) && value.getLeaseExpiresAt() != null)
                .findFirst().orElseThrow();
    }

    private void assignId(long id) {
        doAnswer(invocation -> {
            ProblemDraftGenerationJobEntity job = invocation.getArgument(0);
            job.setId(id);
            return 1;
        }).when(jobMapper).insert(any(ProblemDraftGenerationJobEntity.class));
    }

    private static ProblemDraftAgentCoordinator.PreparedTurn prepared(String workspaceId, String turnId,
                                                                       boolean replay) {
        ProblemDraftWorkspaceEntity workspace = new ProblemDraftWorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setCreatorUserId(7L);
        workspace.setConversationId("conversation-" + workspaceId);
        workspace.setCurrentDraftId(900L);
        workspace.setStateVersion(3L);
        workspace.setRequestedCfRating(2000);
        workspace.setReviewRisk("HIGH_DIFFICULTY_AI");
        workspace.setAgentMode("THINKING_TOOL_LOOP");
        workspace.setContentFingerprint("content-fingerprint");
        workspace.setVerificationFingerprint("verification-fingerprint");
        AiTurnEntity turn = new AiTurnEntity();
        turn.setId(turnId);
        turn.setConversationId(workspace.getConversationId());
        return new ProblemDraftAgentCoordinator.PreparedTurn(workspace, turn, replay, "message");
    }

    private static ProblemDraftAgentCoordinator.AgentOutcome outcome(boolean succeeded) {
        return new ProblemDraftAgentCoordinator.AgentOutcome(300L, succeeded ? 900L : null, 4L,
                "HIGH_DIFFICULTY_AI", "THINKING_TOOL_LOOP", "content", "verification", succeeded,
                succeeded ? "FINALIZED" : "BUDGET_EXHAUSTED", "safe summary",
                succeeded ? List.of() : List.of("step_budget_exhausted"));
    }

    private static ProblemDraftRequest request(String topic) {
        return new ProblemDraftRequest(topic, "HARD", 2000, null, "segment tree",
                List.of("trees"), null, null, null, null, "cpp", null, null, null,
                12, null, null, true, true);
    }

    private static ProblemDraftGenerationJobEntity job(Long id, Long creatorUserId, String status) {
        ProblemDraftGenerationJobEntity job = new ProblemDraftGenerationJobEntity();
        job.setId(id);
        job.setCreatorUserId(creatorUserId);
        job.setJobType("GENERATE");
        job.setWorkspaceId("ws-100");
        job.setTurnId("turn-100");
        job.setStatus(status);
        job.setStage(status);
        job.setProgressCurrent(0);
        job.setProgressTotal(20);
        job.setReviewRisk("STANDARD");
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        return job;
    }

    private static void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "tester", Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    private static PlatformTransactionManager transactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
    }
}
