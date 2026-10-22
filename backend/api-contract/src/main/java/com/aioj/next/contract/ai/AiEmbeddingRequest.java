package com.aioj.next.contract.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiEmbeddingRequest(
        @NotBlank
        @Size(max = 8000)
        String input
) {
}