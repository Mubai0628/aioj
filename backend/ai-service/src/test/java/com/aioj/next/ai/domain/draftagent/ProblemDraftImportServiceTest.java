package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.domain.ProblemDraftStore;
import com.aioj.next.ai.domain.ProblemDraftTestcaseArtifactService;
import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.ai.persistence.entity.ProblemDraftEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftImportSagaEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftImportSagaMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseArtifactMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.ProblemDraftImportRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.ProblemVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemDraftImportServiceTest {

    @Mock private ProblemDraftWorkspaceService workspaceService;
    @Mock private ProblemDraftStore draftStore;
    @Mock private ProblemDraftTestcaseArtifactService artifactService;
    @Mock private ProblemServiceClient problemServiceClient;
    @Mock private ProblemDraftImportSagaMapper sagaMapper;
    @Mock private ProblemDraftMapper draftMapper;
    @Mock private ProblemDraftTestcaseArtifactMapper artifactMapper;
    @Mock private PlatformTransactionManager transactionManager;

    private ProblemDraftImportService service;

    @BeforeEach
    void setUp() {
        service = new ProblemDraftImportService(workspaceService, draftStore, artifactService,
                problemServiceClient, sagaMapper, draftMapper, artifactMapper,
                new ProblemDraftReviewRiskService(), transactionManager);
    }

    @Test
    void manualUnverifiedImportRequiresExplicitSecondAcknowledgement() {
        arrangeCurrent("NOT_RUN");
        ProblemDraftImportRequest request = new ProblemDraftImportRequest(
                30L, ProblemVisibility.PRIVATE, "MANUAL_UNVERIFIED", false, "manual-1");

        assertThatThrownBy(() -> service.importWorkspace("ws-1", 7L, request, "Bearer staff"))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).contains("UNVERIFIED_ACKNOWLEDGEMENT_REQUIRED");
                });
        verify(sagaMapper, never()).insert(any(ProblemDraftImportSagaEntity.class));
        verify(problemServiceClient, never()).prepareAiProblemDraftImport(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void completedIdempotentImportIsReusedWithoutRepeatingRemoteSteps() {
        arrangeCurrent("PASSED");
        ProblemDraftResponse draftResponse = org.mockito.Mockito.mock(ProblemDraftResponse.class);
        when(draftStore.get(20L)).thenReturn(draftResponse);
        ProblemDraftImportSagaEntity completed = saga("COMPLETED", "AGENT_VERIFIED");
        when(sagaMapper.selectOne(any())).thenReturn(completed);

        var response = service.importWorkspace("ws-1", 7L, new ProblemDraftImportRequest(
                30L, ProblemVisibility.PRIVATE, "AGENT_VERIFIED", false, "verified-1"), "Bearer staff");

        assertThat(response.state()).isEqualTo("COMPLETED");
        assertThat(response.problemId()).isEqualTo(40L);
        assertThat(response.problemTestcasePackageId()).isEqualTo(50L);
        verify(problemServiceClient, never()).prepareAiProblemDraftImport(
                any(), any(), any(), any(), any(), any(), any());
        verify(problemServiceClient, never()).commitAiProblemDraftImport(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void metadataOnlyDraftVersionCanReuseAWorkspaceArtifactWithTheSameVerificationFingerprint() {
        arrangeCurrent("PASSED", 19L);
        when(draftStore.get(20L)).thenReturn(org.mockito.Mockito.mock(ProblemDraftResponse.class));
        when(sagaMapper.selectOne(any())).thenReturn(saga("COMPLETED", "AGENT_VERIFIED"));

        var response = service.importWorkspace("ws-1", 7L, new ProblemDraftImportRequest(
                30L, ProblemVisibility.PRIVATE, "AGENT_VERIFIED", false, "verified-1"), "Bearer staff");

        assertThat(response.state()).isEqualTo("COMPLETED");
        assertThat(response.artifactId()).isEqualTo(30L);
    }

    @Test
    void updateTargetIsDerivedFromEarlierImportedWorkspaceVersion() {
        ProblemDraftWorkspaceEntity workspace = new ProblemDraftWorkspaceEntity();
        workspace.setId("ws-1");
        workspace.setSourceDraftId(10L);
        ProblemDraftEntity current = new ProblemDraftEntity();
        current.setId(20L);
        ProblemDraftEntity source = new ProblemDraftEntity();
        source.setId(10L);
        source.setImportedProblemId(99L);
        when(draftMapper.selectById(10L)).thenReturn(source);

        Long targetProblemId = service.resolveTargetProblemId(workspace, current);

        assertThat(targetProblemId).isEqualTo(99L);
    }

    private void arrangeCurrent(String executionStatus) {
        arrangeCurrent(executionStatus, 20L);
    }

    private void arrangeCurrent(String executionStatus, Long artifactDraftId) {
        ProblemDraftWorkspaceEntity workspace = new ProblemDraftWorkspaceEntity();
        workspace.setId("ws-1");
        workspace.setCreatorUserId(7L);
        workspace.setCurrentDraftId(20L);
        workspace.setVerificationFingerprint("f".repeat(64));
        when(workspaceService.requireOwned("ws-1", 7L)).thenReturn(workspace);

        ProblemDraftEntity draft = new ProblemDraftEntity();
        draft.setId(20L);
        draft.setWorkspaceId("ws-1");
        draft.setStatus("APPROVED");
        draft.setValidationStatus("VALID");
        draft.setVerificationFingerprint("f".repeat(64));
        draft.setDifficulty("MEDIUM");
        draft.setRequestedCfRating(1600);
        when(draftMapper.selectById(20L)).thenReturn(draft);

        ProblemDraftTestcaseArtifactEntity artifact = new ProblemDraftTestcaseArtifactEntity();
        artifact.setId(30L);
        artifact.setDraftId(artifactDraftId);
        artifact.setWorkspaceId("ws-1");
        artifact.setStatus("READY");
        artifact.setFormatStatus("READY");
        artifact.setExecutionStatus(executionStatus);
        artifact.setVerificationFingerprint("f".repeat(64));
        artifact.setSha256("a".repeat(64));
        when(artifactService.requireWorkspaceArtifact(30L, "ws-1", 7L)).thenReturn(artifact);
    }

    private ProblemDraftImportSagaEntity saga(String state, String mode) {
        ProblemDraftImportSagaEntity saga = new ProblemDraftImportSagaEntity();
        saga.setId(60L);
        saga.setWorkspaceId("ws-1");
        saga.setDraftId(20L);
        saga.setArtifactId(30L);
        saga.setCreatorUserId(7L);
        saga.setIdempotencyKey("verified-1");
        saga.setState(state);
        saga.setImportMode(mode);
        saga.setTargetVisibility("PRIVATE");
        saga.setRequestedProblemId(null);
        saga.setArtifactSha256("a".repeat(64));
        saga.setVerificationFingerprint("f".repeat(64));
        saga.setProblemId(40L);
        saga.setTestcasePackageId(50L);
        saga.setCreatedAt(LocalDateTime.now());
        saga.setUpdatedAt(LocalDateTime.now());
        saga.setCompletedAt(LocalDateTime.now());
        return saga;
    }
}
