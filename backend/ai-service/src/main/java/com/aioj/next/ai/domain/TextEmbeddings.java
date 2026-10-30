package com.aioj.next.ai.domain;

import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.EmbeddingRequest;
import com.aioj.next.ai.model.api.EmbeddingResponse;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.common.error.SafeErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Dense vectors for retrieval, on {@link ModelClient} (plan T1.3). Never throws:
 * when embeddings are switched off, unconfigured or failing, the vector is empty
 * and callers fall back to lexical search.
 */
@Component
public class TextEmbeddings {
    private static final Logger log = LoggerFactory.getLogger(TextEmbeddings.class);
    static final int MAX_INPUT_CHARS = 8000;

    private final ModelClient modelClient;
    private final AiModelConfigResolver configResolver;

    public TextEmbeddings(ModelClient modelClient, AiModelConfigResolver configResolver) {
        this.modelClient = modelClient;
        this.configResolver = configResolver;
    }

    public Optional<List<Double>> embed(String input) {
        AiModelEffectiveConfig embedding = configResolver.effectiveConfig(AiModelScope.EMBEDDING);
        if (!embedding.enabled() || !embedding.hasApiKey() || input == null || input.isBlank()) {
            return Optional.empty();
        }
        try {
            EmbeddingResponse response = modelClient.embed(new EmbeddingRequest(AiModelScope.EMBEDDING.name(),
                    AiModelConfigEndpointSource.toEndpoint(embedding), List.of(normalized(input))),
                    CallOptions.of("retrieval.embedding"));
            List<Double> vector = response.first();
            return vector.isEmpty() ? Optional.empty() : Optional.of(vector);
        } catch (RuntimeException ex) {
            AiFailureMetrics.incrementEmbeddingFailure();
            log.error("embedding request failed; continuing without dense vector: {}", SafeErrors.describe(ex));
            return Optional.empty();
        }
    }

    /** Same text the old client embedded: line endings unified, trimmed, cut at the input limit. */
    private static String normalized(String value) {
        String text = value.replace("\r\n", "\n").replace("\r", "\n").trim();
        return text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) + "\n…（内容已截断）" : text;
    }
}
