package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.problem.AiProblemDraftImportCommitRequest;
import com.aioj.next.contract.problem.AiProblemDraftImportCommitResponse;
import com.aioj.next.contract.problem.AiProblemDraftImportPrepareRequest;
import com.aioj.next.contract.problem.AiProblemDraftImportPrepareResponse;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.contract.problem.TestcasePackageResponse;
import com.aioj.next.problem.domain.testcase.TestcasePackageService;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Protected boundary used by ai-service's recoverable import saga. Workspace
 * ownership is server-derived and unique, while actorUserId is persisted only
 * as provenance and never installed into the SecurityContext.
 */
@Service
public class AiProblemDraftImportService {
    private static final int WORKSPACE_ID_MAX_LENGTH = 64;
    private static final int PROVENANCE_VALUE_MAX_LENGTH = 32;

    private final ProblemCatalog problemCatalog;
    private final TestcasePackageService testcasePackageService;

    public AiProblemDraftImportService(ProblemCatalog problemCatalog,
                                       TestcasePackageService testcasePackageService) {
        this.problemCatalog = problemCatalog;
        this.testcasePackageService = testcasePackageService;
    }

    /**
     * Deliberately not wrapped in one transaction: a concurrent insert that loses
     * the workspace unique-key race must be rolled back before the winning row is
     * queried and reused.
     */
    public AiProblemDraftImportPrepareResponse prepare(AiProblemDraftImportPrepareRequest request) {
        PrepareSpec spec = prepareSpec(request);
        ProblemEntity existing = problemCatalog.findByAiOriginWorkspaceId(spec.workspaceId()).orElse(null);
        if (existing != null) {
            if (spec.targetProblemId() != null && !spec.targetProblemId().equals(existing.getId())) {
                throw new DomainException(ErrorCode.CONFLICT,
                        "AI draft workspace is already bound to another problem");
            }
            ProblemEntity prepared = problemCatalog.prepareExistingAiDraftImport(existing.getId(), spec.workspaceId(),
                    spec.draftId(), spec.reviewRisk(), spec.verificationMode());
            return prepareResponse(prepared, false, spec);
        }

        if (spec.targetProblemId() != null) {
            ProblemEntity claimed = problemCatalog.claimAiDraftImportTarget(
                    spec.targetProblemId(), spec.workspaceId());
            ProblemEntity prepared = problemCatalog.prepareExistingAiDraftImport(claimed.getId(), spec.workspaceId(),
                    spec.draftId(), spec.reviewRisk(), spec.verificationMode());
            return prepareResponse(prepared, false, spec);
        }

        try {
            ProblemEntity created = problemCatalog.createAiDraftImportTarget(request.problem(), spec.actorUserId(),
                    spec.workspaceId(), spec.draftId(), spec.reviewRisk(), spec.verificationMode());
            return prepareResponse(created, true, spec);
        } catch (DuplicateKeyException duplicate) {
            // Workspace uniqueness is the idempotency fence. If another request
            // won the race, reuse it; otherwise preserve the original DB failure.
            ProblemEntity winner = problemCatalog.findByAiOriginWorkspaceId(spec.workspaceId())
                    .orElseThrow(() -> duplicate);
            ProblemEntity prepared = problemCatalog.prepareExistingAiDraftImport(winner.getId(), spec.workspaceId(),
                    spec.draftId(), spec.reviewRisk(), spec.verificationMode());
            return prepareResponse(prepared, false, spec);
        }
    }

    /**
     * Problem content replacement and testcase activation join this transaction.
     * Any validation/activation failure therefore leaves the prior statement,
     * visibility, and active package untouched.
     */
    @Transactional
    public AiProblemDraftImportCommitResponse commit(Long problemId, AiProblemDraftImportCommitRequest request) {
        CommitSpec spec = commitSpec(problemId, request);
        ProblemEntity target = problemCatalog.findByAiOriginWorkspaceId(spec.workspaceId())
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "AI draft import target not found"));
        assertTarget(target, spec.problemId(), spec.draftId());

        // Preflight ownership and READY state before replacing any problem content.
        testcasePackageService.findActivatableOrThrow(spec.problemId(), spec.testcasePackageId());
        ProblemEntity updated = problemCatalog.commitAiDraftImport(spec.problemId(), request.problem(),
                spec.actorUserId(), spec.workspaceId(), spec.draftId(), spec.reviewRisk(),
                spec.verificationMode(), spec.targetVisibility());
        TestcasePackageResponse activated = testcasePackageService.activate(spec.problemId(), spec.testcasePackageId());
        return new AiProblemDraftImportCommitResponse(updated.getId(), activated.id(), updated.getVisibility(),
                updated.getAiOriginWorkspaceId(), updated.getAiOriginDraftId(),
                Boolean.TRUE.equals(updated.getAiGenerated()), updated.getAiReviewRisk(),
                updated.getAiTestcaseVerificationMode());
    }

    private PrepareSpec prepareSpec(AiProblemDraftImportPrepareRequest request) {
        if (request == null || request.problem() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "AI draft import request and problem are required");
        }
        return new PrepareSpec(workspaceId(request.workspaceId()), positive(request.draftId(), "draftId"),
                positive(request.actorUserId(), "actorUserId"), risk(request.reviewRisk()),
                provenanceValue(request.testcaseVerificationMode(), "testcaseVerificationMode"),
                optionalPositive(request.targetProblemId(), "targetProblemId"));
    }

    private CommitSpec commitSpec(Long problemId, AiProblemDraftImportCommitRequest request) {
        if (request == null || request.problem() == null || request.targetVisibility() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST,
                    "AI draft commit request, problem, and target visibility are required");
        }
        return new CommitSpec(positive(problemId, "problemId"), workspaceId(request.workspaceId()),
                positive(request.draftId(), "draftId"), positive(request.actorUserId(), "actorUserId"),
                positive(request.testcasePackageId(), "testcasePackageId"), request.targetVisibility(),
                risk(request.reviewRisk()),
                provenanceValue(request.testcaseVerificationMode(), "testcaseVerificationMode"));
    }

    private void assertTarget(ProblemEntity target, Long problemId, Long draftId) {
        if (!problemId.equals(target.getId()) || !Boolean.TRUE.equals(target.getAiGenerated())) {
            throw new DomainException(ErrorCode.CONFLICT, "AI draft workspace belongs to another problem");
        }
        if (!draftId.equals(target.getAiOriginDraftId())) {
            throw new DomainException(ErrorCode.CONFLICT, "AI draft import target changed; prepare the draft again");
        }
        if (Boolean.TRUE.equals(target.getDeleted()) || target.getDeletedAt() != null || target.getArchivedAt() != null) {
            throw new DomainException(ErrorCode.CONFLICT, "AI draft import target is not active");
        }
    }

    private AiProblemDraftImportPrepareResponse prepareResponse(ProblemEntity problem, boolean created,
                                                                 PrepareSpec spec) {
        return new AiProblemDraftImportPrepareResponse(problem.getId(), created, problem.getVisibility(),
                spec.workspaceId(), spec.draftId());
    }

    private String workspaceId(String value) {
        if (!StringUtils.hasText(value)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "workspaceId is required");
        }
        String normalized = value.trim();
        if (normalized.length() > WORKSPACE_ID_MAX_LENGTH) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "workspaceId is too long");
        }
        return normalized;
    }

    private Long positive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, field + " must be positive");
        }
        return value;
    }

    private Long optionalPositive(Long value, String field) {
        return value == null ? null : positive(value, field);
    }

    private String risk(String value) {
        String normalized = provenanceValue(value, "reviewRisk");
        return normalized == null ? "STANDARD" : normalized;
    }

    private String provenanceValue(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > PROVENANCE_VALUE_MAX_LENGTH) {
            throw new DomainException(ErrorCode.BAD_REQUEST, field + " is too long");
        }
        return normalized;
    }

    private record PrepareSpec(String workspaceId, Long draftId, Long actorUserId,
                               String reviewRisk, String verificationMode, Long targetProblemId) {
    }

    private record CommitSpec(Long problemId, String workspaceId, Long draftId, Long actorUserId,
                              Long testcasePackageId, ProblemVisibility targetVisibility,
                              String reviewRisk, String verificationMode) {
    }
}
