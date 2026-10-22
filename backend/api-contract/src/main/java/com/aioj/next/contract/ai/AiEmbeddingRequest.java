package com.aioj.next.contract.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Text to embed for another service. {@code purpose} (PROBLEM_INDEX or
 * PROBLEM_QUERY) tags the usage metric; embeddings never count against a
 * student's AI quota.
 */
public record AiEmbeddingRequest(
        @NotBlank @Size(max = 8000) String input,
        String purpose
) {
}
