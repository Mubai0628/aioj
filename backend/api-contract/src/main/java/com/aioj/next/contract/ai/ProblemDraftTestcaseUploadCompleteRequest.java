package com.aioj.next.contract.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProblemDraftTestcaseUploadCompleteRequest(
        @NotBlank @Size(min = 64, max = 64) String sha256
) {
}
