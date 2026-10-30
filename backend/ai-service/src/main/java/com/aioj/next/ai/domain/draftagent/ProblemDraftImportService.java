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
import com.aioj.next.common.error.SafeErrors;
import com.aioj.next.common.error.UserErrorFeedback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aioj.next.contract.ai.ProblemDraftImportReadiness;
import com.aioj.next.contract.ai.ProblemDraftImportRequest;
import com.aioj.next.contract.ai.ProblemDraftImportResponse;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.ai.ProblemDraftReviewRisk;
import com.aioj.next.contract.problem.AiProblemDraftImportCommitResponse;
import com.aioj.next.contract.problem.AiProblemDraftImportPrepareResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Recoverable cross-service import saga for reviewed AI draft workspaces.
 *
 * <p>The model never receives this operation as a tool. A staff request chooses
 * an already-owned artifact, while workspace, draft, provenance, risk and the
 * effective verification mode are re-derived on the server. Each remote step
 * is idempotent so an interrupted request can be resumed with the same key.</p>
 */
@Service
public class ProblemDraftImportService {
    private static final Logger log = LoggerFactory.getLogger(ProblemDraftImportService.class);
    public static final String MODE_AGENT_VERIFIED = "AGENT_VERIFIED";
    public static final String MODE_MANUAL_UNVERIFIED = "MANUAL_UNVERIFIED";
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final ProblemDraftWorkspaceService workspaceService;
    private final ProblemDraftStore draftStore;
    private final ProblemDraftTestcaseArtifactService artifactService;
    private final ProblemServiceClient problemServiceClient;
    private final ProblemDraftImportSagaMapper sagaMapper;
    private final ProblemDraftMapper draftMapper;
    private final ProblemDraftTestcaseArtifactMapper artifactMapper;
    private final ProblemDraftReviewRiskService riskService;
    private final TransactionTemplate transactionTemplate;

    public ProblemDraftImportService(
            ProblemDraftWorkspaceService workspaceService,
            ProblemDraftStore draftStore,
            ProblemDraftTestcaseArtifactService artifactService,
            ProblemServiceClient problemServiceClient,
            ProblemDraftImportSagaMapper sagaMapper,
            ProblemDraftMapper draftMapper,
            ProblemDraftTestcaseArtifactMapper artifactMapper,
            ProblemDraftReviewRiskService riskService,
            PlatformTransactionManager transactionManager
    ) {
        this.workspaceService = workspaceService;
        this.draftStore = draftStore;
        this.artifactService = artifactService;
        this.problemServiceClient = problemServiceClient;
        this.sagaMapper = sagaMapper;
        this.draftMapper = draftMapper;
        this.artifactMapper = artifactMapper;
        this.riskService = riskService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ProblemDraftImportResponse importWorkspace(String workspaceId, long userId,
                                                       ProblemDraftImportRequest request,
                                                       String authorization) {
        ImportContext context = validate(workspaceId, userId, request, authorization);
        ProblemDraftImportSagaEntity saga = findOrCreateSaga(context);
        assertSameRequest(saga, context);
        if ("COMPLETED".equals(saga.getState())) {
            return response(saga, context.readiness());
        }
        try {
            resume(saga, context, authorization);
            return response(requireSaga(saga.getId()), context.readiness());
        } catch (DomainException ex) {
            recordFailure(saga.getId(), ex.errorCode().name(), UserErrorFeedback.storedKey(ex));
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("AI problem draft import {} was interrupted: {}", saga.getId(), SafeErrors.describe(ex));
            recordFailure(saga.getId(), ErrorCode.INTERNAL_ERROR.name(), "draft.importInterrupted");
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "AI problem draft import was interrupted; retry with the same idempotency key");
        }
    }

    private ImportContext validate(String workspaceId, long userId, ProblemDraftImportRequest request,
                                   String authorization) {
        if (request == null || request.artifactId() == null || request.visibility() == null
                || !StringUtils.hasText(request.idempotencyKey())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft import request is incomplete");
        }
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new DomainException(ErrorCode.UNAUTHORIZED,
                    HttpHeaders.AUTHORIZATION + " Bearer token is required to upload the testcase package");
        }
        ProblemDraftWorkspaceEntity workspace = workspaceService.requireOwned(workspaceId, userId);
        if (workspace.getCurrentDraftId() == null) {
            throw blocked("DRAFT_NOT_FINALIZED", "Finalize the current problem draft before importing it");
        }
        ProblemDraftEntity draftEntity = draftMapper.selectById(workspace.getCurrentDraftId());
        if (draftEntity == null || !workspaceId.equals(draftEntity.getWorkspaceId())) {
            throw blocked("DRAFT_WORKSPACE_MISMATCH", "The finalized draft does not belong to this workspace");
        }
        if (!"APPROVED".equals(draftEntity.getStatus())) {
            throw blocked("DRAFT_NOT_APPROVED", "Approve the problem content before importing it");
        }
        if (!"VALID".equals(draftEntity.getValidationStatus())) {
            throw blocked("STATIC_CONTENT_INVALID", "Fix the draft validation errors before importing it");
        }
        if (!Objects.equals(workspace.getVerificationFingerprint(), draftEntity.getVerificationFingerprint())) {
            throw blocked("DRAFT_CONTENT_STALE", "Finalize the current workspace content before importing it");
        }
        ProblemDraftTestcaseArtifactEntity artifact = artifactService.requireWorkspaceArtifact(
                request.artifactId(), workspaceId, userId);
        if (!"READY".equals(artifact.getStatus()) || !"READY".equals(artifact.getFormatStatus())) {
            throw blocked("TESTCASE_FORMAT_NOT_READY", "The testcase package format validation has not passed");
        }
        if (!Objects.equals(workspace.getVerificationFingerprint(), artifact.getVerificationFingerprint())) {
            throw blocked("TESTCASE_ARTIFACT_STALE", "The testcase package is stale for the current draft content");
        }
        boolean executionPassed = "PASSED".equals(artifact.getExecutionStatus());
        String effectiveMode = executionPassed ? MODE_AGENT_VERIFIED : MODE_MANUAL_UNVERIFIED;
        if (StringUtils.hasText(request.mode()) && !effectiveMode.equals(request.mode().trim().toUpperCase())) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Testcase verification mode changed; refresh the draft import readiness before continuing");
        }
        boolean acknowledgementRequired = !executionPassed;
        if (acknowledgementRequired && !Boolean.TRUE.equals(request.acknowledgeUnverified())) {
            throw blocked("UNVERIFIED_ACKNOWLEDGEMENT_REQUIRED",
                    "Confirm that this testcase package has not passed Agent execution acceptance");
        }
        if (!executionPassed) {
            artifact.setVerificationMode(MODE_MANUAL_UNVERIFIED);
            artifact.setUpdatedAt(LocalDateTime.now());
            artifactMapper.updateById(artifact);
        }
        ProblemDraftReviewRisk risk = riskService.derive(draftEntity.getDifficulty(),
                draftEntity.getRequestedCfRating(), acknowledgementRequired);
        ProblemDraftImportReadiness readiness = new ProblemDraftImportReadiness(true,
                acknowledgementRequired, effectiveMode, List.of(), List.of());
        ProblemDraftResponse draft = draftStore.get(draftEntity.getId());
        Long targetProblemId = resolveTargetProblemId(workspace, draftEntity);
        return new ImportContext(workspace, draftEntity, draft, artifact, effectiveMode, risk, readiness,
                request, userId, targetProblemId);
    }

    /**
     * The update target is provenance owned by ai-service. It is never accepted
     * from the browser or model. Older imported versions remain authoritative
     * when a later immutable workspace version has not itself been imported yet.
     */
    Long resolveTargetProblemId(ProblemDraftWorkspaceEntity workspace, ProblemDraftEntity currentDraft) {
        if (currentDraft != null && currentDraft.getImportedProblemId() != null) {
            return currentDraft.getImportedProblemId();
        }
        if (workspace != null && workspace.getSourceDraftId() != null) {
            ProblemDraftEntity source = draftMapper.selectById(workspace.getSourceDraftId());
            if (source != null && source.getImportedProblemId() != null) {
                return source.getImportedProblemId();
            }
        }
        if (workspace == null || !StringUtils.hasText(workspace.getId())) {
            return null;
        }
        ProblemDraftEntity priorImportedVersion = draftMapper.selectOne(
                new QueryWrapper<ProblemDraftEntity>()
                        .eq("workspace_id", workspace.getId())
                        .isNotNull("imported_problem_id")
                        .orderByDesc("state_version")
                        .orderByDesc("id")
                        .last("LIMIT 1"));
        return priorImportedVersion == null ? null : priorImportedVersion.getImportedProblemId();
    }

    private ProblemDraftImportSagaEntity findOrCreateSaga(ImportContext context) {
        ProblemDraftImportSagaEntity existing = sagaMapper.selectOne(
                new QueryWrapper<ProblemDraftImportSagaEntity>()
                        .eq("creator_user_id", context.userId())
                        .eq("idempotency_key", context.request().idempotencyKey())
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        ProblemDraftImportSagaEntity created = new ProblemDraftImportSagaEntity();
        created.setId(IdWorker.getId());
        created.setWorkspaceId(context.workspace().getId());
        created.setDraftId(context.draftEntity().getId());
        created.setArtifactId(context.artifact().getId());
        created.setCreatorUserId(context.userId());
        created.setIdempotencyKey(context.request().idempotencyKey().trim());
        created.setState("PREPARING");
        created.setImportMode(context.mode());
        created.setTargetVisibility(context.request().visibility().name());
        created.setAcknowledgeUnverified(Boolean.TRUE.equals(context.request().acknowledgeUnverified()));
        created.setRequestedProblemId(context.targetProblemId());
        created.setArtifactSha256(context.artifact().getSha256());
        created.setVerificationFingerprint(context.workspace().getVerificationFingerprint());
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            sagaMapper.insert(created);
            return created;
        } catch (DuplicateKeyException duplicate) {
            ProblemDraftImportSagaEntity winner = sagaMapper.selectOne(
                    new QueryWrapper<ProblemDraftImportSagaEntity>()
                            .eq("creator_user_id", context.userId())
                            .eq("idempotency_key", context.request().idempotencyKey().trim())
                            .last("LIMIT 1"));
            if (winner == null) {
                throw duplicate;
            }
            return winner;
        }
    }

    private void assertSameRequest(ProblemDraftImportSagaEntity saga, ImportContext context) {
        if (!Objects.equals(saga.getWorkspaceId(), context.workspace().getId())
                || !Objects.equals(saga.getDraftId(), context.draftEntity().getId())
                || !Objects.equals(saga.getArtifactId(), context.artifact().getId())
                || !Objects.equals(saga.getArtifactSha256(), context.artifact().getSha256())
                || !Objects.equals(saga.getVerificationFingerprint(), context.workspace().getVerificationFingerprint())
                || !Objects.equals(saga.getImportMode(), context.mode())
                || !Objects.equals(saga.getRequestedProblemId(), context.targetProblemId())
                || !Objects.equals(saga.getTargetVisibility(), context.request().visibility().name())) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "The idempotency key is already bound to a different problem draft import");
        }
    }

    private void resume(ProblemDraftImportSagaEntity saga, ImportContext context, String authorization) {
        ProblemDraftImportSagaEntity current = requireSaga(saga.getId());
        if (current.getProblemId() == null) {
            AiProblemDraftImportPrepareResponse prepared = problemServiceClient.prepareAiProblemDraftImport(
                    context.workspace().getId(), context.draftEntity().getId(), context.userId(),
                    context.risk().code(), context.mode(), context.targetProblemId(), context.draft());
            if (prepared == null || prepared.problemId() == null) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR,
                        "Problem-service did not return an AI draft import target");
            }
            transition(current.getId(), null, prepared.problemId(), null, null);
            current = requireSaga(current.getId());
        }
        if (current.getTestcasePackageId() == null) {
            Path path = artifactService.resolvePath(context.artifact());
            Long packageId = problemServiceClient.uploadTestcasePackageReady(current.getProblemId(),
                    context.artifact(), path, authorization);
            transition(current.getId(), "PACKAGE_READY", current.getProblemId(), packageId, null);
            current = requireSaga(current.getId());
        }
        if (!"ACTIVATED".equals(current.getState()) && !"COMPLETED".equals(current.getState())) {
            AiProblemDraftImportCommitResponse committed = problemServiceClient.commitAiProblemDraftImport(
                    current.getProblemId(), context.workspace().getId(), context.draftEntity().getId(),
                    context.userId(), current.getTestcasePackageId(), context.request().visibility(),
                    context.risk().code(), context.mode(), context.draft());
            if (committed == null || committed.problemId() == null || !committed.aiGenerated()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR,
                        "Problem-service did not commit AI problem provenance");
            }
            transition(current.getId(), "ACTIVATED", committed.problemId(),
                    committed.activeTestcasePackageId(), null);
            current = requireSaga(current.getId());
        }
        completeLocal(current, context);
    }

    protected void completeLocal(ProblemDraftImportSagaEntity saga, ImportContext context) {
        transactionTemplate.executeWithoutResult(status -> {
            ProblemDraftEntity draft = draftMapper.selectById(context.draftEntity().getId());
            if (draft == null) {
                throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft disappeared during import");
            }
            draft.setImportedProblemId(saga.getProblemId());
            draft.setReviewRisk(context.risk().code());
            draftMapper.updateById(draft);
            artifactService.markImported(context.artifact().getId(), saga.getProblemId(), saga.getTestcasePackageId());
            transition(saga.getId(), "COMPLETED", saga.getProblemId(), saga.getTestcasePackageId(),
                    LocalDateTime.now());
        });
    }

    private void transition(Long sagaId, String state, Long problemId, Long packageId, LocalDateTime completedAt) {
        UpdateWrapper<ProblemDraftImportSagaEntity> update = new UpdateWrapper<ProblemDraftImportSagaEntity>()
                .eq("id", sagaId)
                .set("updated_at", LocalDateTime.now())
                .set("last_error_code", null)
                .set("last_error_message", null);
        if (state != null) update.set("state", state);
        if (problemId != null) update.set("problem_id", problemId);
        if (packageId != null) update.set("testcase_package_id", packageId);
        if (completedAt != null) update.set("completed_at", completedAt);
        sagaMapper.update(null, update);
    }

    private void recordFailure(Long sagaId, String code, String message) {
        sagaMapper.update(null, new UpdateWrapper<ProblemDraftImportSagaEntity>()
                .eq("id", sagaId)
                .set("last_error_code", code)
                .set("last_error_message", safeMessage(message))
                .set("updated_at", LocalDateTime.now()));
    }

    private ProblemDraftImportSagaEntity requireSaga(Long id) {
        ProblemDraftImportSagaEntity saga = sagaMapper.selectById(id);
        if (saga == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft import saga not found");
        }
        return saga;
    }

    private ProblemDraftImportResponse response(ProblemDraftImportSagaEntity saga,
                                                ProblemDraftImportReadiness readiness) {
        return new ProblemDraftImportResponse(saga.getId(), saga.getWorkspaceId(), saga.getDraftId(),
                saga.getArtifactId(), saga.getState(), saga.getImportMode(), saga.getProblemId(),
                saga.getTestcasePackageId(), readiness, saga.getLastErrorCode(),
                // Plan S7: an error key; older rows kept the failure's text, which is not handed out.
                UserErrorFeedback.storedKey(saga.getLastErrorMessage(), "draft.importFailed"),
                instant(saga.getCreatedAt()), instant(saga.getUpdatedAt()), instant(saga.getCompletedAt()));
    }

    private DomainException blocked(String code, String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, code + ": " + message);
    }

    private String safeMessage(String message) {
        String safe = StringUtils.hasText(message) ? message.trim() : "AI problem draft import failed";
        return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private record ImportContext(
            ProblemDraftWorkspaceEntity workspace,
            ProblemDraftEntity draftEntity,
            ProblemDraftResponse draft,
            ProblemDraftTestcaseArtifactEntity artifact,
            String mode,
            ProblemDraftReviewRisk risk,
            ProblemDraftImportReadiness readiness,
            ProblemDraftImportRequest request,
            long userId,
            Long targetProblemId
    ) {
    }
}
