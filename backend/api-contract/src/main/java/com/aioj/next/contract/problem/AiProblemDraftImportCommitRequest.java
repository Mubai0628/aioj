package com.aioj.next.contract.problem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Atomically switches content, active testcase package, provenance and visibility. */
public record AiProblemDraftImportCommitRequest(
        @NotBlank @Size(max = 64) String workspaceId,
        @NotNull @Positive Long draftId,
        @NotNull @Positive Long actorUserId,
        @NotNull @Positive Long testcasePackageId,
        @NotNull ProblemVisibility targetVisibility,
        @Size(max = 32) String reviewRisk,
        @Size(max = 32) String testcaseVerificationMode,
        @NotNull @Valid ProblemUpdateRequest problem
) {
}
