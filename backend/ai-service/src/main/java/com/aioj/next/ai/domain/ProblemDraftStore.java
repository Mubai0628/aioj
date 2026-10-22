package com.aioj.next.ai.domain;

import com.aioj.next.ai.persistence.entity.ProblemDraftEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftMapper;
import com.aioj.next.ai.domain.problem.ComplexityReport;
import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.aioj.next.ai.domain.problem.CrossCheckReport;
import com.aioj.next.ai.domain.problem.DraftExecutionReport;
import com.aioj.next.ai.domain.problem.ProblemDraftStaticValidator;
import com.aioj.next.ai.domain.problem.RepairTask;
import com.aioj.next.ai.domain.problem.VerificationError;
import com.aioj.next.ai.domain.problem.VerificationFailureClassifier;
import com.aioj.next.ai.domain.problem.VerificationOptions;
import com.aioj.next.ai.domain.problem.VerificationReport;
import com.aioj.next.ai.domain.problem.VerificationWarning;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkingState;
import com.aioj.next.ai.domain.draftagent.ProblemDraftReviewRiskService;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.ProblemDraftApprovalRequest;
import com.aioj.next.contract.ai.ProblemDraftRefineRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.ai.ProblemDraftImportReadiness;
import com.aioj.next.contract.ai.ProblemDraftReviewRisk;
import com.aioj.next.contract.ai.ProblemDraftTestcaseArtifactSummary;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.contract.problem.TestCaseDto;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProblemDraftStore {
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String VERIFICATION_NOT_RUN = "NOT_RUN";
    private static final String VERIFICATION_STATIC_FAILED = "STATIC_FAILED";
    private static final String VERIFICATION_FAILED = "FAILED";
    private static final String VERIFICATION_EXECUTION_VERIFIED = "EXECUTION_VERIFIED";
    private static final String VERIFICATION_MANUAL_VERIFIED = "MANUAL_VERIFIED";
    private static final int DEFAULT_TIME_LIMIT_MILLIS = 1000;
    private static final int DEFAULT_MEMORY_LIMIT_KB = 262144;

    private final AiProvider aiProvider;
    private final ProblemDraftMapper problemDraftMapper;
    private final ProblemServiceClient problemServiceClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final OperationAuditWriter auditWriter;
    private final ProblemDraftStaticValidator problemDraftStaticValidator;
    private final DraftSandboxClient draftSandboxClient;
    private final VerificationFailureClassifier verificationFailureClassifier;
    private ProblemDraftTestcaseArtifactService testcaseArtifactService;
    private ProblemDraftReviewRiskService reviewRiskService;

    public ProblemDraftStore(
            AiProvider aiProvider,
            ProblemDraftMapper problemDraftMapper,
            ProblemServiceClient problemServiceClient,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            OperationAuditWriter auditWriter,
            ProblemDraftStaticValidator problemDraftStaticValidator,
            DraftSandboxClient draftSandboxClient
    ) {
        this.aiProvider = aiProvider;
        this.problemDraftMapper = problemDraftMapper;
        this.problemServiceClient = problemServiceClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.auditWriter = auditWriter;
        this.problemDraftStaticValidator = problemDraftStaticValidator;
        this.draftSandboxClient = draftSandboxClient;
        this.verificationFailureClassifier = new VerificationFailureClassifier(objectMapper);
    }

    @Autowired(required = false)
    public void setProblemDraftTestcaseArtifactService(ProblemDraftTestcaseArtifactService testcaseArtifactService) {
        this.testcaseArtifactService = testcaseArtifactService;
    }

    @Autowired(required = false)
    public void setProblemDraftReviewRiskService(ProblemDraftReviewRiskService reviewRiskService) {
        this.reviewRiskService = reviewRiskService;
    }

    public PageResponse<ProblemDraftResponse> list(
            long page,
            long pageSize,
            String status,
            String validationStatus,
            Long creatorUserId,
            String sortDirection,
            String lifecycleStatus
    ) {
        long current = Math.max(1, page);
        long size = Math.min(Math.max(1, pageSize), 100);
        long offset = (current - 1) * size;
        QueryWrapper<ProblemDraftEntity> countQuery = new QueryWrapper<>();
        applyListFilters(countQuery, status, validationStatus, creatorUserId, lifecycleStatus);
        long total = problemDraftMapper.selectCount(countQuery);

        QueryWrapper<ProblemDraftEntity> pageQuery = new QueryWrapper<>();
        applyListFilters(pageQuery, status, validationStatus, creatorUserId, lifecycleStatus);
        if ("ASC".equalsIgnoreCase(sortDirection)) {
            pageQuery.orderByAsc("created_at");
        } else {
            pageQuery.orderByDesc("created_at");
        }
        pageQuery.last("LIMIT " + size + " OFFSET " + offset);
        List<ProblemDraftResponse> records = problemDraftMapper.selectList(pageQuery)
                .stream()
                .map(this::toResponse)
                .toList();
        return new PageResponse<>(records, total, current, size);
    }

    private void applyListFilters(
            QueryWrapper<ProblemDraftEntity> query,
            String status,
            String validationStatus,
            Long creatorUserId,
            String lifecycleStatus
    ) {
        query.isNull("deleted_at");
        if ("ARCHIVED".equalsIgnoreCase(lifecycleStatus)) {
            query.isNotNull("archived_at");
        } else if (!"ALL".equalsIgnoreCase(lifecycleStatus)) {
            query.isNull("archived_at");
        }
        if (status != null && !status.isBlank()) {
            query.eq("status", status);
        }
        if (validationStatus != null && !validationStatus.isBlank()) {
            query.eq("validation_status", validationStatus);
        }
        if (creatorUserId != null) {
            query.eq("creator_user_id", creatorUserId);
        }
    }

    public ProblemDraftResponse get(Long id) {
        return toResponse(requireVisibleDraft(id));
    }

    @Transactional
    public ProblemDraftResponse refine(Long id, Long userId, ProblemDraftRefineRequest request, String authorization) {
        if (request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Refinement request is required");
        }
        ProblemDraftEntity draft = requireMutableDraft(id);
        DraftPayload currentPayload = readPayload(draft.getDraftJson());
        int timeLimitMillis = limitOrDefault(currentPayload.timeLimitMillis(), DEFAULT_TIME_LIMIT_MILLIS);
        int memoryLimitKb = limitOrDefault(currentPayload.memoryLimitKb(), DEFAULT_MEMORY_LIMIT_KB);
        ProblemDraftResponse updated = new ProblemDraftResponse(
                draft.getId(),
                draft.getStatus(),
                nonBlank(request.title(), draft.getTitle()),
                nonBlank(request.difficulty(), draft.getDifficulty()),
                request.statement() == null ? currentPayload.statement() : request.statement(),
                request.notes() == null ? currentPayload.notes() : request.notes(),
                request.standardSolutionLanguage() == null ? currentPayload.standardSolutionLanguage() : request.standardSolutionLanguage(),
                request.standardSolutionCode() == null ? currentPayload.standardSolutionCode() : request.standardSolutionCode(),
                request.referenceSolutionLanguage() == null ? currentPayload.referenceSolutionLanguage() : request.referenceSolutionLanguage(),
                request.referenceSolutionCode() == null ? currentPayload.referenceSolutionCode() : request.referenceSolutionCode(),
                request.testcaseGeneratorPython() == null ? currentPayload.testcaseGeneratorPython() : request.testcaseGeneratorPython(),
                request.stressTestcaseGeneratorPython() == null ? currentPayload.stressTestcaseGeneratorPython() : request.stressTestcaseGeneratorPython(),
                request.generationPlan() == null ? currentPayload.generationPlan() : request.generationPlan(),
                request.tags() == null ? currentPayload.tags() : request.tags(),
                "VALID",
                List.of(),
                normalizeTestCases(request.testCases() == null ? currentPayload.testCases() : request.testCases()),
                limitOrDefault(request.timeLimitMillis(), timeLimitMillis),
                limitOrDefault(request.memoryLimitKb(), memoryLimitKb),
                draft.getImportedProblemId(),
                nonBlank(draft.getModel(), aiProvider.model()),
                currentPayload.promptTokens(),
                currentPayload.completionTokens(),
                draft.getCreatedAt() == null ? Instant.now() : draft.getCreatedAt().atZone(ZONE).toInstant(),
                toInstant(draft.getArchivedAt()),
                toInstant(draft.getDeletedAt()),
                draft.getDeletedBy(),
                draft.getRefinedFromDraftId(),
                request.refineNote(),
                VERIFICATION_NOT_RUN,
                null,
                repairAttemptCount(draft.getRepairAttemptCount()),
                draft.getLastRepairReason()
        );
        VerificationOptions verificationOptions = verificationOptionsForDraft(updated);
        VerificationReport staticReport = staticReport(updated, verificationOptions, List.of(),
                updated.timeLimitMillis(), updated.memoryLimitKb(), List.of());
        List<String> validationErrors = new ArrayList<>(staticReport.errorMessages());
        ProblemDraftResponse finalDraft = withValidation(updated, validationErrors.isEmpty() ? "VALID" : "INVALID", validationErrors);
        finalDraft = withVerification(finalDraft, verifyFinalDraft(finalDraft, staticReport, verificationOptions));
        if (draft.getImportedProblemId() != null && !"VALID".equals(finalDraft.validationStatus())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Cannot update an imported draft with invalid content. Please fix the validation errors first.");
        }
        if (draft.getImportedProblemId() != null
                && !VERIFICATION_EXECUTION_VERIFIED.equals(finalDraft.verificationStatus())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Cannot update an imported draft without successful execution verification. Please fix it first.");
        }
        if (draft.getImportedProblemId() != null) {
            // Regeneration keeps the imported problem's existing visibility.
            problemServiceClient.updateProblem(draft.getImportedProblemId(), finalDraft, authorization, null);
        }
        applyUpdate(draft, finalDraft, userId);
        problemDraftMapper.updateById(draft);
        return toResponse(draft);
    }

    ProblemDraftResponse regenerationSource(Long id) {
        return toResponse(requireMutableDraft(id));
    }

    /**
     * Final gate used exclusively by {@code draft.finalize}. The workspace keeps
     * mutable state; this method creates one immutable PENDING_REVIEW version
     * only after the coordinator has verified the current fingerprint.
     */
    @Transactional
    public ProblemDraftResponse finalizeAgentWorkspace(
            Long userId,
            String workspaceId,
            Long candidateDraftId,
            long stateVersion,
            Long agentRunId,
            Integer requestedCfRating,
            String reviewRisk,
            String contentFingerprint,
            String verificationFingerprint,
            ProblemDraftWorkingState state,
            VerificationReport staticReport,
            String verificationReportJson,
            String model,
            long promptTokens,
            long completionTokens
    ) {
        if (workspaceId == null || workspaceId.isBlank() || candidateDraftId == null || state == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft workspace state is incomplete");
        }
        ProblemDraftEntity existing = problemDraftMapper.selectById(candidateDraftId);
        if (existing != null) {
            if (!workspaceId.equals(existing.getWorkspaceId())
                    || !verificationFingerprint.equals(existing.getVerificationFingerprint())) {
                throw new DomainException(ErrorCode.CONFLICT, "Problem draft finalize id is already in use");
            }
        }
        VerificationReport authoritativeStatic = staticReport == null
                ? VerificationReport.of(List.of(new VerificationError("STATIC_NOT_RUN", "Static validation is required", null)), List.of())
                : staticReport;
        if (!authoritativeStatic.passed()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Problem draft cannot be finalized before static validation passes");
        }
        if (existing != null) {
            existing.setStateVersion(stateVersion);
            existing.setAgentRunId(agentRunId);
            existing.setValidationStatus("VALID");
            existing.setValidationErrors(toJson(authoritativeStatic.errorMessages()));
            existing.setVerificationStatus(VERIFICATION_EXECUTION_VERIFIED);
            existing.setVerificationReportJson(verificationReportJson);
            problemDraftMapper.updateById(existing);
            return toResponse(existing);
        }
        ProblemDraftResponse response = state.toResponse(candidateDraftId, nonBlank(model, aiProvider.model()),
                Math.max(0, promptTokens), Math.max(0, completionTokens));
        response = new ProblemDraftResponse(
                response.id(), STATUS_PENDING_REVIEW, response.title(), response.difficulty(), response.statement(),
                response.notes(), response.standardSolutionLanguage(), response.standardSolutionCode(),
                response.referenceSolutionLanguage(), response.referenceSolutionCode(),
                response.testcaseGeneratorPython(), response.stressTestcaseGeneratorPython(), response.generationPlan(),
                response.tags(), "VALID", authoritativeStatic.errorMessages(), response.testCases(),
                response.timeLimitMillis(), response.memoryLimitKb(), null, response.model(), response.promptTokens(),
                response.completionTokens(), response.createdAt(), null, null, null, null, null,
                VERIFICATION_EXECUTION_VERIFIED, verificationReportJson, 0, null
        );
        persist(userId, response);
        ProblemDraftEntity entity = problemDraftMapper.selectById(candidateDraftId);
        entity.setWorkspaceId(workspaceId);
        entity.setStateVersion(stateVersion);
        entity.setContentFingerprint(contentFingerprint);
        entity.setVerificationFingerprint(verificationFingerprint);
        entity.setRequestedCfRating(requestedCfRating);
        entity.setReviewRisk(reviewRisk);
        entity.setAgentRunId(agentRunId);
        problemDraftMapper.updateById(entity);
        return toResponse(entity);
    }

    /**
     * Finalize runs cannot know aggregate model usage until the model returns after draft.finalize.
     * Bind those totals to the immutable draft only when workspace, actor and Agent run all match.
     */
    @Transactional
    public void updateFinalizedAgentUsage(Long userId, String workspaceId, Long draftId, Long agentRunId,
                                          String model, long promptTokens, long completionTokens) {
        ProblemDraftEntity entity = problemDraftMapper.selectById(draftId);
        if (entity == null || !java.util.Objects.equals(entity.getCreatorUserId(), userId)
                || !java.util.Objects.equals(entity.getWorkspaceId(), workspaceId)
                || !java.util.Objects.equals(entity.getAgentRunId(), agentRunId)) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Finalized problem draft does not match the Agent run");
        }
        DraftPayload payload = readPayload(entity.getDraftJson());
        entity.setDraftJson(toJson(new DraftPayload(
                payload.statement(), payload.notes(), payload.standardSolutionLanguage(),
                payload.standardSolutionCode(), payload.referenceSolutionLanguage(),
                payload.referenceSolutionCode(), payload.testcaseGeneratorPython(),
                payload.stressTestcaseGeneratorPython(), payload.generationPlan(), payload.tags(),
                payload.testCases(), payload.timeLimitMillis(), payload.memoryLimitKb(),
                Math.max(0, promptTokens), Math.max(0, completionTokens)
        )));
        entity.setModel(nonBlank(model, entity.getModel()));
        problemDraftMapper.updateById(entity);
    }

    /**
     * Persists a human edit as a new immutable workspace version. Execution
     * evidence is deliberately reset; the workspace must run the Agent gates
     * again before this version can be imported.
     */
    @Transactional
    public ProblemDraftResponse createHumanWorkspaceVersion(
            Long userId,
            String workspaceId,
            Long candidateDraftId,
            long stateVersion,
            Integer requestedCfRating,
            String reviewRisk,
            String contentFingerprint,
            String verificationFingerprint,
            ProblemDraftWorkingState state,
            VerificationReport staticReport,
            Long refinedFromDraftId,
            String refineNote,
            String model
    ) {
        if (workspaceId == null || workspaceId.isBlank() || candidateDraftId == null || state == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft workspace edit is incomplete");
        }
        if (problemDraftMapper.selectById(candidateDraftId) != null) {
            throw new DomainException(ErrorCode.CONFLICT, "Problem draft workspace version is already in use");
        }
        VerificationReport report = staticReport == null
                ? VerificationReport.of(List.of(new VerificationError(
                        "STATIC_NOT_RUN", "Static validation is required", null)), List.of())
                : staticReport;
        ProblemDraftResponse base = state.toResponse(candidateDraftId, nonBlank(model, aiProvider.model()), 0, 0);
        ProblemDraftResponse response = new ProblemDraftResponse(
                base.id(), STATUS_PENDING_REVIEW, base.title(), base.difficulty(), base.statement(), base.notes(),
                base.standardSolutionLanguage(), base.standardSolutionCode(), base.referenceSolutionLanguage(),
                base.referenceSolutionCode(), base.testcaseGeneratorPython(), base.stressTestcaseGeneratorPython(),
                base.generationPlan(), base.tags(), report.passed() ? "VALID" : "INVALID", report.errorMessages(),
                base.testCases(), base.timeLimitMillis(), base.memoryLimitKb(), null, base.model(), 0, 0,
                base.createdAt(), null, null, null, refinedFromDraftId, refineNote, VERIFICATION_NOT_RUN,
                toJson(Map.of(
                        "status", "HUMAN_EDIT_REQUIRES_AGENT_VALIDATION",
                        "verificationFingerprint", verificationFingerprint,
                        "staticPassed", report.passed()
                )), 0, null
        );
        persist(userId, response);
        ProblemDraftEntity entity = problemDraftMapper.selectById(candidateDraftId);
        entity.setWorkspaceId(workspaceId);
        entity.setStateVersion(stateVersion);
        entity.setContentFingerprint(contentFingerprint);
        entity.setVerificationFingerprint(verificationFingerprint);
        entity.setRequestedCfRating(requestedCfRating);
        entity.setReviewRisk(reviewRisk);
        entity.setAgentRunId(null);
        problemDraftMapper.updateById(entity);
        return toResponse(entity);
    }

    public ProblemDraftResponse approve(Long id, Long reviewerUserId, ProblemDraftApprovalRequest request, String authorization) {
        ProblemDraftEntity draft = requireMutableDraft(id);
        boolean importProblem = request != null && Boolean.TRUE.equals(request.importProblem());
        if (importProblem && draft.getWorkspaceId() != null) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Agent workspace drafts must be approved first, then imported through the recoverable import workflow");
        }
        if (importProblem && !STATUS_APPROVED.equals(draft.getStatus())) {
            throw new DomainException(ErrorCode.CONFLICT, "Problem draft must be approved before import");
        }
        if (importProblem && !"VALID".equals(draft.getValidationStatus())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Cannot import an invalid draft. Please regenerate or fix the validation errors first.");
        }
        if (importProblem && !VERIFICATION_EXECUTION_VERIFIED.equals(draft.getVerificationStatus())
                && !VERIFICATION_MANUAL_VERIFIED.equals(draft.getVerificationStatus())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Cannot import a draft without successful execution verification. Please regenerate or fix it first.");
        }
        ProblemDraftTestcaseArtifactEntity testcaseArtifact = null;
        Path testcaseArtifactPath = null;
        if (importProblem) {
            boolean manualVerification = VERIFICATION_MANUAL_VERIFIED.equals(draft.getVerificationStatus());
            testcaseArtifact = manualVerification && testcaseArtifactService != null
                    ? testcaseArtifactService.latestReady(draft.getId())
                    : requireReadyTestcaseArtifact(draft.getId());
            if (testcaseArtifact != null) {
                testcaseArtifactPath = testcaseArtifactService.resolvePath(testcaseArtifact);
            }
        }
        Long importedProblemId = draft.getImportedProblemId();
        ProblemVisibility requestedVisibility = request == null ? null : request.visibility();
        if (importProblem && importedProblemId == null) {
            importedProblemId = problemServiceClient.createProblem(toResponse(draft), authorization, requestedVisibility);
        } else if (importProblem) {
            importedProblemId = problemServiceClient.updateProblem(importedProblemId, toResponse(draft), authorization, requestedVisibility);
        }
        if (importProblem && testcaseArtifact != null) {
            Long packageId = problemServiceClient.uploadAndActivateTestcasePackage(importedProblemId, testcaseArtifact,
                    testcaseArtifactPath, authorization);
            testcaseArtifactService.markImported(testcaseArtifact.getId(), importedProblemId, packageId);
        }
        return finalizeApproval(id, reviewerUserId, importedProblemId);
    }

    public ProblemDraftResponse manualReview(Long id, Long reviewerUserId) {
        ProblemDraftEntity draft = requireMutableDraft(id);
        if (draft.getImportedProblemId() != null) {
            throw new DomainException(ErrorCode.CONFLICT, "Problem draft is already imported");
        }
        LocalDateTime now = LocalDateTime.now();
        draft.setStatus(STATUS_APPROVED);
        draft.setReviewedAt(now);
        draft.setReviewedBy(reviewerUserId);
        draft.setValidationStatus("VALID");
        draft.setValidationErrors(toJson(List.of()));
        draft.setVerificationStatus(VERIFICATION_MANUAL_VERIFIED);
        draft.setVerificationReportJson(manualVerificationReportJson(reviewerUserId, now));
        draft.setLastRepairReason(null);
        problemDraftMapper.updateById(draft);
        return toResponse(draft);
    }

    private String manualVerificationReportJson(Long reviewerUserId, LocalDateTime reviewedAt) {
        Map<String, Object> manualReview = new LinkedHashMap<>();
        manualReview.put("reviewerUserId", reviewerUserId);
        manualReview.put("reviewedAt", reviewedAt.atZone(ZONE).toInstant().toString());
        manualReview.put("note", "Manual review passed; automated verification results were superseded.");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "MANUAL_REVIEW_PASSED");
        report.put("manualReview", manualReview);
        return toJson(report);
    }

    private ProblemDraftTestcaseArtifactEntity requireReadyTestcaseArtifact(Long draftId) {
        if (testcaseArtifactService == null) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft testcase artifact service is unavailable");
        }
        ProblemDraftTestcaseArtifactEntity artifact = testcaseArtifactService.latestReady(draftId);
        if (artifact == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Cannot import without a verified official testcase package. Please rerun verification.");
        }
        return artifact;
    }

    private ProblemDraftResponse finalizeApproval(Long id, Long reviewerUserId, Long importedProblemId) {
        ProblemDraftResponse response = transactionTemplate.execute(status -> {
            ProblemDraftEntity draft = problemDraftMapper.selectById(id);
            if (draft == null || draft.getDeletedAt() != null || draft.getArchivedAt() != null) {
                throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft not found");
            }
            if (importedProblemId != null) {
                draft.setImportedProblemId(importedProblemId);
            }
            draft.setStatus(STATUS_APPROVED);
            draft.setReviewedAt(LocalDateTime.now());
            draft.setReviewedBy(reviewerUserId);
            problemDraftMapper.updateById(draft);
            return toResponse(draft);
        });
        if (response == null) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft approval failed");
        }
        return response;
    }

    @Transactional
    public ProblemDraftResponse reject(Long id, Long reviewerUserId, String reasonNote) {
        ProblemDraftEntity draft = requireMutableDraft(id);
        if (STATUS_APPROVED.equals(draft.getStatus()) && draft.getImportedProblemId() != null) {
            throw new DomainException(ErrorCode.CONFLICT, "Cannot reject an already-imported draft");
        }
        draft.setStatus(STATUS_REJECTED);
        draft.setReviewedAt(LocalDateTime.now());
        draft.setReviewedBy(reviewerUserId);
        if (reasonNote != null && !reasonNote.isBlank()) {
            draft.setValidationErrors(toJson(List.of("REJECT_REASON: " + reasonNote.trim())));
        }
        problemDraftMapper.updateById(draft);
        return toResponse(draft);
    }

    @Transactional
    public void delete(Long id) {
        ProblemDraftEntity draft = requireVisibleDraft(id);
        if (draft.getArchivedAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived problem drafts can be deleted");
        }
        draft.setDeletedAt(LocalDateTime.now());
        draft.setDeletedBy(SecuritySupport.currentUserId());
        problemDraftMapper.updateById(draft);
        auditWriter.record("SOFT_DELETE", "PROBLEM_DRAFT", draft.getId(), "SUCCESS",
                java.util.Map.of("title", draft.getTitle()));
    }

    @Transactional
    public ProblemDraftResponse archive(Long id) {
        ProblemDraftEntity draft = requireVisibleDraft(id);
        if (draft.getArchivedAt() == null) {
            draft.setArchivedAt(LocalDateTime.now());
            problemDraftMapper.updateById(draft);
        }
        auditWriter.record("ARCHIVE", "PROBLEM_DRAFT", draft.getId(), "SUCCESS",
                java.util.Map.of("title", draft.getTitle()));
        return toResponse(draft);
    }

    @Transactional
    public ProblemDraftResponse restore(Long id) {
        ProblemDraftEntity draft = requireVisibleDraft(id);
        if (draft.getArchivedAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived problem drafts can be restored");
        }
        problemDraftMapper.update(new ProblemDraftEntity(), new UpdateWrapper<ProblemDraftEntity>()
                .eq("id", draft.getId())
                .set("archived_at", null));
        draft.setArchivedAt(null);
        auditWriter.record("RESTORE", "PROBLEM_DRAFT", draft.getId(), "SUCCESS",
                java.util.Map.of("title", draft.getTitle()));
        return toResponse(draft);
    }

    private void persist(Long userId, ProblemDraftResponse response) {
        ProblemDraftEntity entity = new ProblemDraftEntity();
        entity.setId(response.id());
        entity.setCreatorUserId(userId);
        entity.setTitle(response.title());
        entity.setDifficulty(response.difficulty());
        entity.setDraftJson(toJson(new DraftPayload(
                response.statement(),
                response.notes(),
                response.standardSolutionLanguage(),
                response.standardSolutionCode(),
                response.referenceSolutionLanguage(),
                response.referenceSolutionCode(),
                response.testcaseGeneratorPython(),
                response.stressTestcaseGeneratorPython(),
                response.generationPlan(),
                response.tags(),
                response.testCases(),
                response.timeLimitMillis(),
                response.memoryLimitKb(),
                response.promptTokens(),
                response.completionTokens()
        )));
        entity.setValidationStatus(response.validationStatus());
        entity.setValidationErrors(toJson(response.validationErrors()));
        entity.setVerificationStatus(nonBlank(response.verificationStatus(), VERIFICATION_NOT_RUN));
        entity.setVerificationReportJson(response.verificationReportJson());
        entity.setRepairAttemptCount(repairAttemptCount(response.repairAttemptCount()));
        entity.setLastRepairReason(response.lastRepairReason());
        entity.setModel(response.model());
        entity.setStatus(STATUS_PENDING_REVIEW);
        entity.setImportedProblemId(response.importedProblemId());
        entity.setRefinedFromDraftId(response.refinedFromDraftId());
        entity.setRefineNote(response.refineNote());
        entity.setCreatedAt(LocalDateTime.now());
        problemDraftMapper.insert(entity);
    }

    private void applyUpdate(ProblemDraftEntity entity, ProblemDraftResponse response, Long userId) {
        if (entity.getCreatorUserId() == null) {
            entity.setCreatorUserId(userId);
        }
        entity.setTitle(response.title());
        entity.setDifficulty(response.difficulty());
        entity.setDraftJson(toJson(new DraftPayload(
                response.statement(),
                response.notes(),
                response.standardSolutionLanguage(),
                response.standardSolutionCode(),
                response.referenceSolutionLanguage(),
                response.referenceSolutionCode(),
                response.testcaseGeneratorPython(),
                response.stressTestcaseGeneratorPython(),
                response.generationPlan(),
                response.tags(),
                response.testCases(),
                response.timeLimitMillis(),
                response.memoryLimitKb(),
                response.promptTokens(),
                response.completionTokens()
        )));
        entity.setValidationStatus(response.validationStatus());
        entity.setValidationErrors(toJson(response.validationErrors()));
        entity.setVerificationStatus(nonBlank(response.verificationStatus(), VERIFICATION_NOT_RUN));
        entity.setVerificationReportJson(response.verificationReportJson());
        entity.setRepairAttemptCount(repairAttemptCount(response.repairAttemptCount()));
        entity.setLastRepairReason(response.lastRepairReason());
        entity.setModel(response.model());
        entity.setRefineNote(response.refineNote());
        if (entity.getImportedProblemId() == null) {
            entity.setStatus(STATUS_PENDING_REVIEW);
            entity.setReviewedAt(null);
            entity.setReviewedBy(null);
        } else {
            entity.setStatus(STATUS_APPROVED);
        }
    }

    private ProblemDraftResponse withValidation(ProblemDraftResponse source, String status, List<String> errors) {
        return new ProblemDraftResponse(source.id(), source.status(), source.title(), source.difficulty(), source.statement(),
                source.notes(), source.standardSolutionLanguage(), source.standardSolutionCode(),
                source.referenceSolutionLanguage(), source.referenceSolutionCode(),
                source.testcaseGeneratorPython(), source.stressTestcaseGeneratorPython(), source.generationPlan(), source.tags(), status, errors,
                source.testCases(), source.timeLimitMillis(), source.memoryLimitKb(),
                source.importedProblemId(), source.model(), source.promptTokens(), source.completionTokens(),
                source.createdAt(), source.archivedAt(), source.deletedAt(), source.deletedBy(),
                source.refinedFromDraftId(), source.refineNote(), source.verificationStatus(),
                source.verificationReportJson(), source.repairAttemptCount(), source.lastRepairReason());
    }

    private ProblemDraftResponse withVerification(ProblemDraftResponse source, VerificationOutcome verification) {
        return new ProblemDraftResponse(source.id(), source.status(), source.title(), source.difficulty(), source.statement(),
                source.notes(), source.standardSolutionLanguage(), source.standardSolutionCode(),
                source.referenceSolutionLanguage(), source.referenceSolutionCode(),
                source.testcaseGeneratorPython(), source.stressTestcaseGeneratorPython(), source.generationPlan(), source.tags(), source.validationStatus(),
                source.validationErrors(), source.testCases(), source.timeLimitMillis(), source.memoryLimitKb(),
                source.importedProblemId(), source.model(), source.promptTokens(), source.completionTokens(),
                source.createdAt(), source.archivedAt(), source.deletedAt(), source.deletedBy(),
                source.refinedFromDraftId(), source.refineNote(), verification.status(),
                verification.reportJson(), source.repairAttemptCount(), source.lastRepairReason());
    }

    private ProblemDraftResponse toResponse(ProblemDraftEntity entity) {
        DraftPayload payload = readPayload(entity.getDraftJson());
        ProblemDraftTestcaseArtifactEntity artifact = testcaseArtifactService == null
                ? null : testcaseArtifactService.latestReady(entity.getId());
        ProblemDraftTestcaseArtifactSummary artifactSummary = testcaseArtifactSummary(
                artifact, entity.getVerificationFingerprint());
        ProblemDraftImportReadiness readiness = importReadiness(entity, artifact);
        boolean manualUnverified = readiness != null && readiness.requiresUnverifiedAcknowledgement();
        ProblemDraftReviewRisk risk = reviewRiskService == null ? null
                : reviewRiskService.derive(entity.getDifficulty(), entity.getRequestedCfRating(), manualUnverified);
        return new ProblemDraftResponse(
                entity.getId(),
                entity.getStatus(),
                entity.getTitle(),
                entity.getDifficulty(),
                payload.statement(),
                payload.notes(),
                payload.standardSolutionLanguage(),
                payload.standardSolutionCode(),
                payload.referenceSolutionLanguage(),
                payload.referenceSolutionCode(),
                payload.testcaseGeneratorPython(),
                payload.stressTestcaseGeneratorPython(),
                payload.generationPlan(),
                payload.tags(),
                entity.getValidationStatus(),
                readErrors(entity.getValidationErrors()),
                normalizeTestCases(payload.testCases()),
                limitOrDefault(payload.timeLimitMillis(), DEFAULT_TIME_LIMIT_MILLIS),
                limitOrDefault(payload.memoryLimitKb(), DEFAULT_MEMORY_LIMIT_KB),
                entity.getImportedProblemId(),
                entity.getModel(),
                payload.promptTokens(),
                payload.completionTokens(),
                entity.getCreatedAt().atZone(ZONE).toInstant(),
                toInstant(entity.getArchivedAt()),
                toInstant(entity.getDeletedAt()),
                entity.getDeletedBy(),
                entity.getRefinedFromDraftId(),
                entity.getRefineNote(),
                nonBlank(entity.getVerificationStatus(), VERIFICATION_NOT_RUN),
                entity.getVerificationReportJson(),
                repairAttemptCount(entity.getRepairAttemptCount()), entity.getLastRepairReason(),
                entity.getWorkspaceId(), entity.getStateVersion(), entity.getContentFingerprint(),
                entity.getVerificationFingerprint(), entity.getRequestedCfRating(), risk,
                artifactSummary, readiness
        );
    }

    private ProblemDraftTestcaseArtifactSummary testcaseArtifactSummary(
            ProblemDraftTestcaseArtifactEntity artifact, String currentFingerprint
    ) {
        if (artifact == null) {
            return null;
        }
        boolean stale = !java.util.Objects.equals(currentFingerprint, artifact.getVerificationFingerprint())
                || "STALE".equals(artifact.getExecutionStatus());
        return new ProblemDraftTestcaseArtifactSummary(
                artifact.getId(), nonBlank(artifact.getSource(), "AGENT_GENERATED"), artifact.getStatus(),
                nonBlank(artifact.getFormatStatus(), "READY"),
                nonBlank(artifact.getExecutionStatus(), "NOT_RUN"), artifact.getVerificationMode(),
                artifact.getVerificationFingerprint(), stale, artifact.getCaseCount(),
                artifact.getFileSizeBytes(), artifact.getSha256(), artifact.getFailureSummary(),
                artifact.getAgentRunId(), toInstant(artifact.getGeneratedAt()), toInstant(artifact.getValidatedAt()),
                artifact.getImportedProblemId(), artifact.getProblemTestcasePackageId()
        );
    }

    private ProblemDraftImportReadiness importReadiness(ProblemDraftEntity draft,
                                                         ProblemDraftTestcaseArtifactEntity artifact) {
        if (draft.getWorkspaceId() == null) {
            return null;
        }
        List<String> blockers = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (!STATUS_APPROVED.equals(draft.getStatus())) {
            blockers.add("DRAFT_NOT_APPROVED");
            actions.add("APPROVE_CONTENT");
        }
        if (!"VALID".equals(draft.getValidationStatus())) {
            blockers.add("STATIC_CONTENT_INVALID");
            actions.add("FIX_STATIC_VALIDATION");
        }
        if (artifact == null) {
            blockers.add("TESTCASE_PACKAGE_MISSING");
            actions.add("GENERATE_OR_UPLOAD_PACKAGE");
        } else {
            if (!STATUS_PENDING_REVIEW.equals(draft.getStatus()) && !STATUS_APPROVED.equals(draft.getStatus())) {
                blockers.add("DRAFT_LIFECYCLE_BLOCKED");
            }
            if (!"READY".equals(artifact.getFormatStatus())) {
                blockers.add("TESTCASE_FORMAT_NOT_READY");
                actions.add("FIX_PACKAGE_FORMAT");
            }
            if (!java.util.Objects.equals(draft.getVerificationFingerprint(), artifact.getVerificationFingerprint())) {
                blockers.add("TESTCASE_ARTIFACT_STALE");
                actions.add("REGENERATE_OR_REUPLOAD_PACKAGE");
            }
        }
        boolean verified = artifact != null && "PASSED".equals(artifact.getExecutionStatus());
        boolean requiresAck = artifact != null && !verified;
        String mode = verified ? "AGENT_VERIFIED" : requiresAck ? "MANUAL_UNVERIFIED" : null;
        return new ProblemDraftImportReadiness(blockers.isEmpty(), requiresAck, mode, blockers, actions);
    }

    private ProblemDraftEntity requireVisibleDraft(Long id) {
        ProblemDraftEntity draft = problemDraftMapper.selectById(id);
        if (draft == null || draft.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft not found");
        }
        return draft;
    }

    private ProblemDraftEntity requireMutableDraft(Long id) {
        ProblemDraftEntity draft = requireVisibleDraft(id);
        if (draft.getArchivedAt() != null) {
            throw new DomainException(ErrorCode.CONFLICT, "Archived problem drafts cannot be modified");
        }
        return draft;
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private VerificationReport staticReport(ProblemDraftResponse response, VerificationOptions options,
                                            List<String> providerErrors, Integer rawTime, Integer rawMem,
                                            List<VerificationError> additionalErrors) {
        List<VerificationError> errors = new ArrayList<>();
        List<VerificationWarning> warnings = new ArrayList<>();
        if (providerErrors != null) {
            providerErrors.stream()
                    .filter(error -> error != null && !error.isBlank())
                    .map(error -> new VerificationError("PROVIDER_VALIDATION_ERROR", error, null))
                    .forEach(errors::add);
        }
        if (additionalErrors != null) {
            errors.addAll(additionalErrors.stream()
                    .filter(error -> error != null)
                    .toList());
        }
        VerificationReport report = problemDraftStaticValidator.verify(response,
                options == null ? VerificationOptions.defaults(List.of()) : options);
        errors.addAll(report.errors());
        warnings.addAll(report.warnings());
        if (rawTime == null || rawTime <= 0) {
            errors.add(new VerificationError("TIME_LIMIT_DEFAULTED",
                    "timeLimitMillis missing — defaulted to " + DEFAULT_TIME_LIMIT_MILLIS,
                    "timeLimitMillis"));
        }
        if (rawMem == null || rawMem <= 0) {
            errors.add(new VerificationError("MEMORY_LIMIT_DEFAULTED",
                    "memoryLimitKb missing — defaulted to " + DEFAULT_MEMORY_LIMIT_KB,
                    "memoryLimitKb"));
        }
        return new VerificationReport(errors.isEmpty() ? "PASSED" : "FAILED", errors, warnings);
    }

    private VerificationOutcome verifyFinalDraft(ProblemDraftResponse response, VerificationReport staticReport,
                                                 VerificationOptions options) {
        VerificationReport sandboxReport = null;
        CrossCheckReport crossCheckReport = null;
        ComplexityReport complexityReport = null;
        com.aioj.next.ai.domain.problem.OfficialTestcasePackageReport officialPackageReport = null;
        String status = VERIFICATION_STATIC_FAILED;
        if (staticReport.passed()) {
            try {
                DraftExecutionReport executionReport = draftSandboxClient.verifyDraftDetailed(response, options);
                if (executionReport == null) {
                    sandboxReport = draftSandboxClient.verifyDraft(response, options);
                } else {
                    sandboxReport = executionReport.sandboxReport();
                    crossCheckReport = executionReport.crossCheckReport();
                    complexityReport = executionReport.complexityReport();
                    officialPackageReport = executionReport.officialPackageReport();
                }
            } catch (RuntimeException ex) {
                sandboxReport = new VerificationReport(VERIFICATION_FAILED, List.of(new VerificationError(
                        "SANDBOX_UNAVAILABLE",
                        "sandbox verification failed: " + nonBlank(ex.getMessage(), ex.getClass().getSimpleName()),
                        null
                )), List.of());
            }
            if (sandboxReport == null) {
                sandboxReport = new VerificationReport(VERIFICATION_FAILED, List.of(new VerificationError(
                        "SANDBOX_NO_REPORT",
                        "sandbox verification returned no report",
                        null
                )), List.of());
            }
            boolean executionPassed = sandboxReport.passed()
                    && (crossCheckReport == null || crossCheckReport.passed())
                    && (complexityReport == null || complexityReport.passed());
            status = executionPassed ? VERIFICATION_EXECUTION_VERIFIED : VERIFICATION_FAILED;
        }
        boolean passed = staticReport.passed()
                && sandboxReport != null
                && sandboxReport.passed()
                && (crossCheckReport == null || crossCheckReport.passed())
                && (complexityReport == null || complexityReport.passed());
        StoredVerificationReport report = new StoredVerificationReport(status, staticReport, sandboxReport,
                crossCheckReport, complexityReport, officialPackageReport, null);
        RepairTask failureClassification = passed ? null : verificationFailureClassifier.classify(toJson(report));
        return new VerificationOutcome(status, toJson(new StoredVerificationReport(status, staticReport, sandboxReport,
                crossCheckReport, complexityReport, officialPackageReport, failureClassification)), passed);
    }

    private VerificationOptions verificationOptionsForDraft(ProblemDraftResponse draft) {
        boolean enableReferenceCheck = draft != null
                && (hasText(draft.referenceSolutionCode()) || hasText(draft.stressTestcaseGeneratorPython()));
        return new VerificationOptions(List.of(), draft == null ? null : draft.difficulty(), null, null, enableReferenceCheck);
    }

    private int repairAttemptCount(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private DraftPayload readPayload(String json) {
        try {
            DraftPayload payload = objectMapper.readValue(json, DraftPayload.class);
            return new DraftPayload(
                    payload.statement(),
                    payload.notes(),
                    defaultSolutionLanguage(payload.standardSolutionLanguage()),
                    payload.standardSolutionCode() == null ? "" : payload.standardSolutionCode(),
                    defaultReferenceSolutionLanguage(payload.referenceSolutionLanguage(), payload.referenceSolutionCode(), payload.standardSolutionLanguage()),
                    payload.referenceSolutionCode(),
                    payload.testcaseGeneratorPython() == null ? "" : payload.testcaseGeneratorPython(),
                    payload.stressTestcaseGeneratorPython(),
                    payload.generationPlan() == null ? "" : payload.generationPlan(),
                    payload.tags() == null ? List.of() : payload.tags(),
                    normalizeTestCases(payload.testCases()),
                    limitOrDefault(payload.timeLimitMillis(), DEFAULT_TIME_LIMIT_MILLIS),
                    limitOrDefault(payload.memoryLimitKb(), DEFAULT_MEMORY_LIMIT_KB),
                    Math.max(0, payload.promptTokens()),
                    Math.max(0, payload.completionTokens())
            );
        } catch (Exception ex) {
            return new DraftPayload("", null, "cpp", "", null, null, "", null, "", List.of(), List.of(),
                    DEFAULT_TIME_LIMIT_MILLIS, DEFAULT_MEMORY_LIMIT_KB, 0, 0);
        }
    }

    private List<String> readErrors(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of("Unable to parse validation errors");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Unable to serialize problem draft");
        }
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private List<TestCaseDto> normalizeTestCases(List<TestCaseDto> testCases) {
        return testCases == null ? List.of() : testCases;
    }

    private int limitOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private String defaultSolutionLanguage(String value) {
        if (value == null || value.isBlank()) {
            return "cpp";
        }
        String normalized = value.trim().toLowerCase();
        if ("c++".equals(normalized) || "c++17".equals(normalized) || "cpp17".equals(normalized)) {
            return "cpp";
        }
        if ("py".equals(normalized) || "python3".equals(normalized)) {
            return "python";
        }
        return normalized;
    }

    private String defaultReferenceSolutionLanguage(String language, String code, String standardLanguage) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return defaultSolutionLanguage(nonBlank(language, standardLanguage));
    }

    private record DraftPayload(
            String statement,
            String notes,
            String standardSolutionLanguage,
            String standardSolutionCode,
            String referenceSolutionLanguage,
            String referenceSolutionCode,
            String testcaseGeneratorPython,
            String stressTestcaseGeneratorPython,
            String generationPlan,
            List<String> tags,
            List<TestCaseDto> testCases,
            Integer timeLimitMillis,
            Integer memoryLimitKb,
            long promptTokens,
            long completionTokens
    ) {
    }

    private record StoredVerificationReport(
            String status,
            VerificationReport staticReport,
            VerificationReport sandboxReport,
            CrossCheckReport crossCheckReport,
            ComplexityReport complexityReport,
            com.aioj.next.ai.domain.problem.OfficialTestcasePackageReport officialPackageReport,
            RepairTask failureClassification
    ) {
    }

    private record VerificationOutcome(
            String status,
            String reportJson,
            boolean passed
    ) {
    }

}
