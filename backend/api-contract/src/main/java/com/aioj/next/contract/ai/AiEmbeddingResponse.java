package com.aioj.next.contract.ai;

import java.util.List;

public record AiEmbeddingResponse(
        List<Double> embedding,
        String model,
        int dimension
) {
}