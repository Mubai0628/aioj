package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.problem.AiProblemDraftImportCommitRequest;
import com.aioj.next.contract.problem.AiProblemDraftImportPrepareRequest;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemCreateRequest;
import com.aioj.next.contract.problem.ProblemUpdateRequest;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.contract.problem.TestCaseDto;
import com.aioj.next.contract.problem.TestcasePackageResponse;
import com.aioj.next.contract.problem.TestcasePackageStatus;
import com.aioj.next.problem.domain.testcase.TestcasePackageService;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiProblemDraftImportServiceTest {
    @Mock
    private ProblemCatalog problemCatalog;
    @Mock
    private TestcasePackageService testcasePackageService;

    private AiProblemDraftImportService service;

    @BeforeEach
    void setUp() {
        service = new AiProblemDraftImportService(problemCatalog, testcasePackageService);
    }

    @Test
    void prepareCreatesPrivateAiTargetWithExplicitActor() {
        AiProblemDraftImportPrepareRequest request = prepareRequest(" workspace-1 ", 31L, 88L);
        ProblemEntity created = target(101L, "workspace-1", 31L, ProblemVisibility.PRIVATE);
        when(problemCatalog.findByAiOriginWorkspaceId("workspace-1")).thenReturn(Optional.empty());
        when(problemCatalog.createAiDraftImportTarget(request.problem(), 88L, "workspace-1", 31L,
                "STANDARD", "AGENT_VERIFIED")).thenReturn(created);

        var response = service.prepare(request);

        assertEquals(101L, response.problemId());
        assertTrue(response.created());
        assertEquals(ProblemVisibility.PRIVATE, response.visibility());
        verify(problemCatalog).createAiDraftImportTarget(request.problem(), 88L, "workspace-1", 31L,
                "STANDARD", "AGENT_VERIFIED");
    }

    @Test
    void prepareReusesWorkspaceAndPinsNewDraftWithoutCreatingProblem() {
        AiProblemDraftImportPrepareRequest request = prepareRequest("workspace-1", 32L, 88L);
        ProblemEntity existing = target(101L, "workspace-1", 31L, ProblemVisibility.PUBLIC);
        ProblemEntity prepared = target(101L, "workspace-1", 32L, ProblemVisibility.PUBLIC);
        when(problemCatalog.findByAiOriginWorkspaceId("workspace-1")).thenReturn(Optional.of(existing));
        when(problemCatalog.prepareExistingAiDraftImport(101L, "workspace-1", 32L,
                "STANDARD", "AGENT_VERIFIED")).thenReturn(prepared);

        var response = service.prepare(request);

        assertFalse(response.created());
        assertEquals(ProblemVisibility.PUBLIC, response.visibility());
        assertEquals(32L, response.draftId());
        verify(problemCatalog, never()).createAiDraftImportTarget(any(), any(), any(), any(), any(), any());
    }

    @Test
    void prepareClaimsServerDerivedExistingProblemWithoutCreatingDuplicate() {
        AiProblemDraftImportPrepareRequest request = prepareRequest("workspace-1", 32L, 88L, 202L);
        ProblemEntity claimed = target(202L, "workspace-1", 31L, ProblemVisibility.PUBLIC);
        ProblemEntity prepared = target(202L, "workspace-1", 32L, ProblemVisibility.PUBLIC);
        when(problemCatalog.findByAiOriginWorkspaceId("workspace-1")).thenReturn(Optional.empty());
        when(problemCatalog.claimAiDraftImportTarget(202L, "workspace-1")).thenReturn(claimed);
        when(problemCatalog.prepareExistingAiDraftImport(202L, "workspace-1", 32L,
                "STANDARD", "AGENT_VERIFIED")).thenReturn(prepared);

        var response = service.prepare(request);

        assertFalse(response.created());
        assertEquals(202L, response.problemId());
        verify(problemCatalog, never()).createAiDraftImportTarget(any(), any(), any(), any(), any(), any());
    }

    @Test
    void prepareReusesWinnerOfWorkspaceUniqueKeyRace() {
        AiProblemDraftImportPrepareRequest request = prepareRequest("workspace-1", 31L, 88L);
        ProblemEntity winner = target(101L, "workspace-1", 31L, ProblemVisibility.PRIVATE);
        when(problemCatalog.findByAiOriginWorkspaceId("workspace-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(problemCatalog.createAiDraftImportTarget(any(), eq(88L), eq("workspace-1"), eq(31L),
                eq("STANDARD"), eq("AGENT_VERIFIED")))
                .thenThrow(new DuplicateKeyException("workspace already exists"));
        when(problemCatalog.prepareExistingAiDraftImport(101L, "workspace-1", 31L,
                "STANDARD", "AGENT_VERIFIED")).thenReturn(winner);

        var response = service.prepare(request);

        assertFalse(response.created());
        assertEquals(101L, response.problemId());
    }

    @Test
    void commitPreflightsPackageThenAtomicallyReplacesAndActivates() {
        AiProblemDraftImportCommitRequest request = commitRequest("workspace-1", 31L, 88L, 501L);
        ProblemEntity target = target(101L, "workspace-1", 31L, ProblemVisibility.PRIVATE);
        ProblemEntity updated = target(101L, "workspace-1", 31L, ProblemVisibility.PUBLIC);
        updated.setAiReviewRisk("HIGH_DIFFICULTY_AI");
        updated.setAiTestcaseVerificationMode("AGENT_VERIFIED");
        when(problemCatalog.findByAiOriginWorkspaceId("workspace-1")).thenReturn(Optional.of(target));
        when(problemCatalog.commitAiDraftImport(101L, request.problem(), 88L, "workspace-1", 31L,
                "HIGH_DIFFICULTY_AI", "AGENT_VERIFIED", ProblemVisibility.PUBLIC)).thenReturn(updated);
        when(testcasePackageService.activate(101L, 501L)).thenReturn(packageResponse(501L, 101L));

        var response = service.commit(101L, request);

        assertEquals(501L, response.activeTestcasePackageId());
        assertEquals(ProblemVisibility.PUBLIC, response.visibility());
        assertTrue(response.aiGenerated());
        InOrder order = inOrder(testcasePackageService, problemCatalog);
        order.verify(testcasePackageService).findActivatableOrThrow(101L, 501L);
        order.verify(problemCatalog).commitAiDraftImport(101L, request.problem(), 88L, "workspace-1", 31L,
                "HIGH_DIFFICULTY_AI", "AGENT_VERIFIED", ProblemVisibility.PUBLIC);
        order.verify(testcasePackageService).activate(101L, 501L);
    }

    @Test
    void commitDoesNotMutateProblemWhenPackageIsNotActivatable() {
        AiProblemDraftImportCommitRequest request = commitRequest("workspace-1", 31L, 88L, 501L);
        when(problemCatalog.findByAiOriginWorkspaceId("workspace-1"))
                .thenReturn(Optional.of(target(101L, "workspace-1", 31L, ProblemVisibility.PUBLIC)));
        when(testcasePackageService.findActivatableOrThrow(101L, 501L))
                .thenThrow(new DomainException(ErrorCode.BAD_REQUEST, "not ready"));

        DomainException error = assertThrows(DomainException.class, () -> service.commit(101L, request));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        verify(problemCatalog, never()).commitAiDraftImport(any(), any(), any(), any(), any(), any(), any(), any());
        verify(testcasePackageService, never()).activate(any(), any());
    }

    @Test
    void commitRejectsDraftThatWasSupersededByAnotherPrepare() {
        AiProblemDraftImportCommitRequest request = commitRequest("workspace-1", 31L, 88L, 501L);
        when(problemCatalog.findByAiOriginWorkspaceId("workspace-1"))
                .thenReturn(Optional.of(target(101L, "workspace-1", 32L, ProblemVisibility.PUBLIC)));

        DomainException error = assertThrows(DomainException.class, () -> service.commit(101L, request));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        verify(testcasePackageService, never()).findActivatableOrThrow(any(), any());
    }

    @Test
    void commitRejectsWorkspaceOwnedByAnotherProblem() {
        AiProblemDraftImportCommitRequest request = commitRequest("workspace-1", 31L, 88L, 501L);
        when(problemCatalog.findByAiOriginWorkspaceId("workspace-1"))
                .thenReturn(Optional.of(target(102L, "workspace-1", 31L, ProblemVisibility.PUBLIC)));

        DomainException error = assertThrows(DomainException.class, () -> service.commit(101L, request));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        verify(testcasePackageService, never()).findActivatableOrThrow(any(), any());
    }

    private AiProblemDraftImportPrepareRequest prepareRequest(String workspaceId, Long draftId, Long actorUserId) {
        return prepareRequest(workspaceId, draftId, actorUserId, null);
    }

    private AiProblemDraftImportPrepareRequest prepareRequest(String workspaceId, Long draftId, Long actorUserId,
                                                               Long targetProblemId) {
        return new AiProblemDraftImportPrepareRequest(workspaceId, draftId, actorUserId, "STANDARD",
                "AGENT_VERIFIED", targetProblemId, createRequest());
    }

    private AiProblemDraftImportCommitRequest commitRequest(String workspaceId, Long draftId, Long actorUserId,
                                                             Long packageId) {
        return new AiProblemDraftImportCommitRequest(workspaceId, draftId, actorUserId, packageId,
                ProblemVisibility.PUBLIC, "HIGH_DIFFICULTY_AI", "AGENT_VERIFIED", updateRequest());
    }

    private ProblemCreateRequest createRequest() {
        return new ProblemCreateRequest("AI problem", Difficulty.HARD, "statement", List.of("dp"),
                List.of(new TestCaseDto("1\n", "1\n", true)), 1000, 262144, "notes",
                "cpp", "int main(){}", null, null, "print('generator')", ProblemVisibility.PUBLIC);
    }

    private ProblemUpdateRequest updateRequest() {
        return new ProblemUpdateRequest("AI problem v2", Difficulty.HARD, "updated statement", List.of("dp"),
                List.of(new TestCaseDto("2\n", "2\n", true)), 1500, 262144, "updated notes",
                "cpp", "int main(){return 0;}", null, null, "print('generator v2')", ProblemVisibility.PRIVATE);
    }

    private ProblemEntity target(Long id, String workspaceId, Long draftId, ProblemVisibility visibility) {
        ProblemEntity entity = new ProblemEntity();
        entity.setId(id);
        entity.setAiGenerated(true);
        entity.setAiOriginWorkspaceId(workspaceId);
        entity.setAiOriginDraftId(draftId);
        entity.setVisibility(visibility);
        entity.setDeleted(false);
        return entity;
    }

    private TestcasePackageResponse packageResponse(Long id, Long problemId) {
        return new TestcasePackageResponse(id, problemId, "v1", "cases.zip", 100L, "sha",
                TestcasePackageStatus.READY, true, 1, 1, "local", null, null, null,
                null, null, null, null, List.of(), List.of());
    }
}
