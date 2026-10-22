package com.aioj.next.contract.ai;

import com.aioj.next.contract.problem.ProblemVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProblemDraftImportRequest(
        @NotNull Long artifactId,
        @NotNull ProblemVisibility visibility,
        @NotBlank @Size(max = 32) String mode,
        Boolean acknowledgeUnverified,
        @NotBlank @Size(max = 128) String idempotencyKey
) {
}
