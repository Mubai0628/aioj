package com.aioj.next.ai.domain.embedding;

import com.aioj.next.ai.domain.AiCapacityService;
import com.aioj.next.ai.domain.AiModelConfigResolver;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.domain.TextEmbeddings;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiEmbeddingRequest;
import com.aioj.next.contract.ai.AiEmbeddingResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Embeddings for other services, today problem search. Calls share the
 * embedding concurrency limit of memory retrieval (INTENT_MEMORY), and are
 * metered by purpose in {@code aioj.ai.embedding.requests} rather than recorded
 * as a student's AI usage.
 */
@Service
public class AiEmbeddingService {
    static final int MAX_INPUT_CHARS = 8000;
    private static final Set<String> PURPOSES = Set.of("PROBLEM_INDEX", "PROBLEM_QUERY");

    private final TextEmbeddings embeddings;
    private final AiCapacityService capacityService;
    private final AiModelConfigResolver configResolver;
    private final MeterRegistry meterRegistry;

    public AiEmbeddingService(TextEmbeddings embeddings, AiCapacityService capacityService,
                              AiModelConfigResolver configResolver, MeterRegistry meterRegistry) {
        this.embeddings = embeddings;
        this.capacityService = capacityService;
        this.configResolver = configResolver;
        this.meterRegistry = meterRegistry;
    }

    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
        String purpose = request.purpose() != null && PURPOSES.contains(request.purpose()) ? request.purpose() : "OTHER";
        String input = request.input().strip();
        String text = input.length() <= MAX_INPUT_CHARS ? input : input.substring(0, MAX_INPUT_CHARS);
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "unavailable";
        try {
            Optional<List<Double>> vector = capacityService.call(AiCapacityService.AiWorkload.INTENT_MEMORY,
                    () -> embeddings.embed(text));
            if (vector.isEmpty() || vector.get().isEmpty()) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Embedding provider unavailable");
            }
            List<Double> values = vector.get();
            float[] embedding = new float[values.size()];
            for (int i = 0; i < embedding.length; i++) {
                Double value = values.get(i);
                embedding[i] = value == null ? 0f : value.floatValue();
            }
            String model = configResolver.effectiveConfig(AiModelScope.EMBEDDING).model();
            outcome = "ok";
            return new AiEmbeddingResponse(embedding, model, embedding.length);
        } catch (DomainException ex) {
            if (ex.errorCode() == ErrorCode.TOO_MANY_REQUESTS) {
                outcome = "busy";
            }
            throw ex;
        } finally {
            meterRegistry.counter("aioj.ai.embedding.requests", "purpose", purpose, "outcome", outcome).increment();
            sample.stop(meterRegistry.timer("aioj.ai.embedding.duration", "purpose", purpose));
        }
    }
}
