package com.aioj.next.ai.model.api;

import java.util.List;

/** One vector per input, in input order. */
public record EmbeddingResponse(List<List<Double>> vectors, ModelUsage usage, ProviderId provider, String model) {

    public EmbeddingResponse {
        vectors = vectors == null ? List.of() : vectors.stream().map(List::copyOf).toList();
        usage = usage == null ? ModelUsage.NONE : usage;
    }

    public List<Double> first() {
        return vectors.isEmpty() ? List.of() : vectors.get(0);
    }
}
