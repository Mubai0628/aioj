package com.aioj.next.contract.ai;

/** A dense vector from the configured EMBEDDING model; vectors of different models never mix. */
public record AiEmbeddingResponse(
        float[] embedding,
        String model,
        int dimension
) {
}
