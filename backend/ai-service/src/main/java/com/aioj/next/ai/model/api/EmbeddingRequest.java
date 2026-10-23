package com.aioj.next.ai.model.api;

import java.util.List;

/**
 * @param endpoint {@code null} resolves {@code scope} through the {@link ModelConfigSource}
 */
public record EmbeddingRequest(String scope, ModelEndpointConfig endpoint, List<String> inputs) {

    public EmbeddingRequest {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope is required");
        }
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("at least one input is required");
        }
    }

    public static EmbeddingRequest of(String scope, String input) {
        return new EmbeddingRequest(scope, null, List.of(input));
    }

    public EmbeddingRequest withEndpoint(ModelEndpointConfig value) {
        return new EmbeddingRequest(scope, value, inputs);
    }
}
