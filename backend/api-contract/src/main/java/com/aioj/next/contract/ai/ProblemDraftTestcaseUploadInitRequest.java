package com.aioj.next.contract.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ProblemDraftTestcaseUploadInitRequest(
        @NotBlank @Size(max = 255) String fileName,
        @Positive Long fileSizeBytes,
        @NotBlank @Size(min = 64, max = 64) String sha256
) {
}
