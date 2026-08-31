package com.aioj.next.contract.problem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Internal-only request. Browser clients must never be allowed to set AI provenance. */
public record AiProblemDraftImportPrepareRequest(
        @NotBlank @Size(max = 64) String workspaceId,
        @NotNull @Positive Long draftId,
        @NotNull @Positive Long actorUserId,
        @Size(max = 32) String reviewRisk,
        @Size(max = 32) String testcaseVerificationMode,
        @Positive Long targetProblemId,
        @NotNull @Valid ProblemCreateRequest problem
) {
}
