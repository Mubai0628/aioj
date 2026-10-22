package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.aioj.next.ai.domain.problem.ProblemDraftStaticValidator;
import com.aioj.next.ai.domain.problem.VerificationError;
import com.aioj.next.ai.domain.problem.VerificationReport;
import com.aioj.next.ai.persistence.entity.ProblemDraftEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.ProblemDraftApprovalRequest;
import com.aioj.next.contract.ai.ProblemDraftRefineRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemDraftStoreLifecycleTest {
    @Mock
    private AiProvider aiProvider;
    @Mock
    private ProblemDraftMapper problemDraftMapper;
    @Mock
    private ProblemServiceClient problemServiceClient;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private OperationAuditWriter auditWriter;
    @Mock
    private DraftSandboxClient draftSandboxClient;
    @Mock
    private ProblemDraftTestcaseArtifactService testcaseArtifactService;

    private ProblemDraftStore store;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        store = new ProblemDraftStore(aiProvider, problemDraftMapper, problemServiceClient,
                new ObjectMapper(), transactionTemplate, auditWriter,
                new ProblemDraftStaticValidator(properties), draftSandboxClient);
        store.setProblemDraftTestcaseArtifactService(testcaseArtifactService);
        lenient().when(draftSandboxClient.verifyDraft(any(ProblemDraftResponse.class), any()))
                .thenReturn(new VerificationReport("EXECUTION_VERIFIED", List.of(), List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void restoreClearsArchivedAtWithExplicitNullUpdate() {
        ProblemDraftEntity draft = draft(1L);
        draft.setArchivedAt(LocalDateTime.parse("2026-06-02T00:00:00"));
        when(problemDraftMapper.selectById(1L)).thenReturn(draft);

        var response = store.restore(1L);

        assertNull(response.archivedAt());
        verify(problemDraftMapper).update(any(ProblemDraftEntity.class), any(UpdateWrapper.class));
    }

    @Test
    void deleteAllowsArchivedImportedDraftWithoutTouchingImportedProblem() {
        authenticateAsAdmin();
        ProblemDraftEntity draft = draft(2L);
        draft.setArchivedAt(LocalDateTime.parse("2026-06-02T00:00:00"));
        draft.setImportedProblemId(200L);
        when(problemDraftMapper.selectById(2L)).thenReturn(draft);

        store.delete(2L);

        assertNotNull(draft.getDeletedAt());
        assertEquals(99L, draft.getDeletedBy());
        verify(problemDraftMapper).updateById(draft);
        verify(auditWriter).record(eq("SOFT_DELETE"), eq("PROBLEM_DRAFT"), eq(2L), eq("SUCCESS"), any());
        verifyNoInteractions(problemServiceClient);
    }

    @Test
    void deleteStillRequiresArchivedDraft() {
        ProblemDraftEntity draft = draft(3L);
        when(problemDraftMapper.selectById(3L)).thenReturn(draft);

        assertThrows(DomainException.class, () -> store.delete(3L));

        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void importRequiresApprovedDraft() {
        ProblemDraftEntity draft = draft(4L);
        when(problemDraftMapper.selectById(4L)).thenReturn(draft);

        assertThrows(DomainException.class,
                () -> store.approve(4L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token"));

        verifyNoInteractions(problemServiceClient);
        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void importRejectsApprovedInvalidDraftWithoutCallingProblemService() {
        ProblemDraftEntity draft = draft(14L);
        draft.setStatus("APPROVED");
        draft.setValidationStatus("INVALID");
        when(problemDraftMapper.selectById(14L)).thenReturn(draft);

        assertThrows(DomainException.class,
                () -> store.approve(14L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token"));

        verifyNoInteractions(problemServiceClient);
        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void importRejectsApprovedDraftWithFailedVerificationWithoutCallingProblemService() {
        ProblemDraftEntity draft = draft(15L);
        draft.setStatus("APPROVED");
        draft.setValidationStatus("VALID");
        draft.setVerificationStatus("FAILED");
        when(problemDraftMapper.selectById(15L)).thenReturn(draft);

        assertThrows(DomainException.class,
                () -> store.approve(15L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token"));

        verifyNoInteractions(problemServiceClient);
        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void importRejectsApprovedDraftWithoutExecutionVerificationWithoutCallingProblemService() {
        ProblemDraftEntity draft = draft(16L);
        draft.setStatus("APPROVED");
        draft.setValidationStatus("VALID");
        draft.setVerificationStatus("NOT_RUN");
        when(problemDraftMapper.selectById(16L)).thenReturn(draft);

        assertThrows(DomainException.class,
                () -> store.approve(16L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token"));

        verifyNoInteractions(problemServiceClient);
        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void manualApproveAllowsDraftWithFailedAutoTests() {
        ProblemDraftEntity draft = draft(17L);
        draft.setStatus("PENDING_REVIEW");
        draft.setValidationStatus("INVALID");
        draft.setVerificationStatus("FAILED");
        when(problemDraftMapper.selectById(17L)).thenReturn(draft);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        store.approve(17L, 99L, new ProblemDraftApprovalRequest(false, null), "Bearer token");

        verifyNoInteractions(problemServiceClient);
        assertEquals("APPROVED", draft.getStatus());
        verify(problemDraftMapper).updateById(draft);
    }

    @Test
    void manualReviewReplacesFailedVerificationWithManualRecord() {
        ProblemDraftEntity draft = draft(18L);
        draft.setStatus("PENDING_REVIEW");
        draft.setValidationStatus("INVALID");
        draft.setValidationErrors("[\"testCases: invalid\"]");
        draft.setVerificationStatus("FAILED");
        draft.setVerificationReportJson("{\"status\":\"FAILED\",\"sandboxReport\":{\"errors\":[{\"code\":\"SANDBOX_UNAVAILABLE\"}]}}");
        when(problemDraftMapper.selectById(18L)).thenReturn(draft);

        ProblemDraftResponse response = store.manualReview(18L, 99L);

        assertEquals("APPROVED", response.status());
        assertEquals("VALID", response.validationStatus());
        assertTrue(response.validationErrors().isEmpty());
        assertEquals("MANUAL_VERIFIED", response.verificationStatus());
        assertTrue(response.verificationReportJson().contains("MANUAL_REVIEW_PASSED"));
        assertFalse(response.verificationReportJson().contains("SANDBOX_UNAVAILABLE"));
        verify(problemDraftMapper).updateById(draft);
        verifyNoInteractions(problemServiceClient);
    }

    @Test
    void manualReviewRejectsAlreadyImportedDraft() {
        ProblemDraftEntity draft = draft(19L);
        draft.setImportedProblemId(700L);
        when(problemDraftMapper.selectById(19L)).thenReturn(draft);

        assertThrows(DomainException.class, () -> store.manualReview(19L, 99L));

        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    @Test
    void manualReviewSucceedsWithoutTestcasePackage() {
        ProblemDraftEntity draft = draft(20L);
        draft.setVerificationStatus("FAILED");
        when(problemDraftMapper.selectById(20L)).thenReturn(draft);

        ProblemDraftResponse response = store.manualReview(20L, 99L);

        assertEquals("APPROVED", response.status());
        assertEquals("MANUAL_VERIFIED", response.verificationStatus());
        verify(problemDraftMapper).updateById(draft);
        verifyNoInteractions(problemServiceClient);
    }

    @Test
    void importManualVerifiedDraftWithoutArtifactSkipsTestcaseUpload() {
        ProblemDraftEntity draft = draft(21L);
        draft.setStatus("APPROVED");
        draft.setValidationStatus("VALID");
        draft.setVerificationStatus("MANUAL_VERIFIED");
        when(problemDraftMapper.selectById(21L)).thenReturn(draft);
        when(testcaseArtifactService.latestReady(21L)).thenReturn(null);
        when(problemServiceClient.createProblem(any(ProblemDraftResponse.class), eq("Bearer token"), any())).thenReturn(501L);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        ProblemDraftResponse response = store.approve(21L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token");

        assertEquals(Long.valueOf(501L), response.importedProblemId());
        verify(problemServiceClient).createProblem(any(ProblemDraftResponse.class), eq("Bearer token"), any());
        verify(problemServiceClient, never()).uploadAndActivateTestcasePackage(anyLong(), any(), any(), anyString());
        verify(testcaseArtifactService, never()).markImported(anyLong(), anyLong(), anyLong());
    }

    @Test
    void approveImportCarriesStandardSolutionAndGeneratorFields() {
        ProblemDraftEntity draft = draft(5L);
        draft.setStatus("APPROVED");
        draft.setDraftJson(draftJsonWithGeneratedBlocks());
        ProblemDraftTestcaseArtifactEntity artifact = readyArtifact(50L, 5L);
        when(problemDraftMapper.selectById(5L)).thenReturn(draft);
        when(testcaseArtifactService.latestReady(5L)).thenReturn(artifact);
        when(testcaseArtifactService.resolvePath(artifact)).thenReturn(Path.of("D:/tmp/official-hidden.zip"));
        when(problemServiceClient.createProblem(any(ProblemDraftResponse.class), eq("Bearer token"), any())).thenReturn(500L);
        when(problemServiceClient.uploadAndActivateTestcasePackage(eq(500L), eq(artifact), any(Path.class), eq("Bearer token")))
                .thenReturn(900L);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        store.approve(5L, 99L, new ProblemDraftApprovalRequest(true, null), "Bearer token");

        ArgumentCaptor<ProblemDraftResponse> captor = ArgumentCaptor.forClass(ProblemDraftResponse.class);
        verify(problemServiceClient).createProblem(captor.capture(), eq("Bearer token"), any());
        assertEquals("python", captor.getValue().standardSolutionLanguage());
        assertEquals("print(sum(map(int, input().split())))", captor.getValue().standardSolutionCode());
        assertEquals("print('generated')", captor.getValue().testcaseGeneratorPython());
        assertEquals("分阶段生成", captor.getValue().generationPlan());
        verify(problemServiceClient).uploadAndActivateTestcasePackage(eq(500L), eq(artifact), any(Path.class), eq("Bearer token"));
        verify(testcaseArtifactService).markImported(50L, 500L, 900L);
    }

    @Test
    void refinePreservesGeneratedBlocksWhenRequestOmitsThem() {
        ProblemDraftEntity draft = draft(6L);
        draft.setImportedProblemId(600L);
        draft.setDraftJson(draftJsonWithGeneratedBlocks());
        when(problemDraftMapper.selectById(6L)).thenReturn(draft);
        when(problemServiceClient.updateProblem(eq(600L), any(ProblemDraftResponse.class), eq("Bearer token"), any())).thenReturn(600L);

        store.refine(6L, 99L, new ProblemDraftRefineRequest(
                "Refined title",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "只改标题"
        ), "Bearer token");

        ArgumentCaptor<ProblemDraftResponse> captor = ArgumentCaptor.forClass(ProblemDraftResponse.class);
        verify(problemServiceClient).updateProblem(eq(600L), captor.capture(), eq("Bearer token"), any());
        assertEquals("python", captor.getValue().standardSolutionLanguage());
        assertEquals("print(sum(map(int, input().split())))", captor.getValue().standardSolutionCode());
        assertEquals("print('generated')", captor.getValue().testcaseGeneratorPython());
        assertEquals("分阶段生成", captor.getValue().generationPlan());
    }

    @Test
    void refineRejectsImportedDraftWhenExecutionVerificationFails() {
        ProblemDraftEntity draft = draft(7L);
        draft.setImportedProblemId(700L);
        draft.setDraftJson(draftJsonWithGeneratedBlocks());
        when(problemDraftMapper.selectById(7L)).thenReturn(draft);
        when(draftSandboxClient.verifyDraft(any(ProblemDraftResponse.class), any()))
                .thenReturn(new VerificationReport("FAILED", List.of(new VerificationError(
                        "SANDBOX_SAMPLE_MISMATCH",
                        "sample output mismatch",
                        "testCases[0].expectedOutput"
                )), List.of()));

        assertThrows(DomainException.class, () -> store.refine(7L, 99L, new ProblemDraftRefineRequest(
                "Refined title",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "只改标题"
        ), "Bearer token"));

        verifyNoInteractions(problemServiceClient);
        verify(problemDraftMapper, never()).updateById(any(ProblemDraftEntity.class));
    }

    private void authenticateAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityPrincipal(99L, "admin", Set.of(Role.ADMIN)),
                null
        ));
    }

    private ProblemDraftEntity draft(Long id) {
        ProblemDraftEntity draft = new ProblemDraftEntity();
        draft.setId(id);
        draft.setCreatorUserId(2L);
        draft.setTitle("Archived draft");
        draft.setDifficulty("EASY");
        draft.setDraftJson("""
                {
                  "statement": "statement",
                  "notes": "",
                  "tags": [],
                  "testCases": [],
                  "timeLimitMillis": 1000,
                  "memoryLimitKb": 262144,
                  "promptTokens": 0,
                  "completionTokens": 0
                }
                """);
        draft.setValidationStatus("VALID");
        draft.setValidationErrors("[]");
        draft.setVerificationStatus("EXECUTION_VERIFIED");
        draft.setModel("mock");
        draft.setStatus("PENDING_REVIEW");
        draft.setCreatedAt(LocalDateTime.parse("2026-06-01T00:00:00"));
        return draft;
    }

    private ProblemDraftTestcaseArtifactEntity readyArtifact(Long id, Long draftId) {
        ProblemDraftTestcaseArtifactEntity artifact = new ProblemDraftTestcaseArtifactEntity();
        artifact.setId(id);
        artifact.setDraftId(draftId);
        artifact.setStatus(ProblemDraftTestcaseArtifactService.STATUS_READY);
        artifact.setFileName("official-hidden.zip");
        artifact.setStoragePath("draft/official-hidden.zip");
        artifact.setFileSizeBytes(1024L);
        artifact.setSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        artifact.setCaseCount(3);
        artifact.setTotalInputBytes(18L);
        artifact.setTotalOutputBytes(8L);
        artifact.setLargestCaseBytes(8L);
        return artifact;
    }

    private String draftJsonWithGeneratedBlocks() {
        return """
                {
                  "statement": "statement",
                  "notes": "notes",
                  "standardSolutionLanguage": "python",
                  "standardSolutionCode": "print(sum(map(int, input().split())))",
                  "testcaseGeneratorPython": "print('generated')",
                  "generationPlan": "分阶段生成",
                  "tags": ["implementation"],
                  "testCases": [
                    {"input": "1 2\\n", "expectedOutput": "3\\n", "sample": true},
                    {"input": "2 3\\n", "expectedOutput": "5\\n", "sample": true},
                    {"input": "10 20\\n", "expectedOutput": "30\\n", "sample": true}
                  ],
                  "timeLimitMillis": 1000,
                  "memoryLimitKb": 262144,
                  "promptTokens": 11,
                  "completionTokens": 22
                }
                """;
    }

}
