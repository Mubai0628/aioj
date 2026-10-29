package com.aioj.next.problem.domain.search;

import com.aioj.next.common.http.InternalRestClientFactory;
import com.aioj.next.contract.ai.AiEmbeddingRequest;
import com.aioj.next.problem.config.PlagiarismProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Embeddings from ai-service. Nothing here throws: a query falls back to keyword
 * search and an index row retries later, so a failure only carries a short reason.
 */
@Component
public class ProblemEmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(ProblemEmbeddingClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ProblemEmbeddingClient(PlagiarismProperties properties, ObjectMapper objectMapper, InternalRestClientFactory clients) {
        this.restClient = clients.create("ai-service", stripTrailingSlash(properties.getAiServiceUri()), CONNECT_TIMEOUT, READ_TIMEOUT);
        this.objectMapper = objectMapper;
    }

    public Result embed(String input, String purpose) {
        try {
            String body = restClient.post()
                    .uri("/api/v1/internal/ai/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new AiEmbeddingRequest(input, purpose))
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                return Result.failed("ai-service error " + code);
            }
            JsonNode values = root.path("data").path("embedding");
            if (!values.isArray() || values.isEmpty()) {
                return Result.failed("empty embedding");
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) values.get(i).asDouble(0.0);
            }
            return new Result(new Embedding(vector, root.path("data").path("model").asString("")), null);
        } catch (RestClientResponseException ex) {
            log.warn("Problem embedding ({}) rejected with HTTP {}", purpose, ex.getStatusCode().value());
            return Result.failed("HTTP " + ex.getStatusCode().value());
        } catch (RuntimeException ex) {
            log.warn("Problem embedding ({}) unavailable: {}", purpose, ex.getClass().getSimpleName());
            return Result.failed(ex.getClass().getSimpleName());
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8204";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record Embedding(float[] vector, String model) {
    }

    /** Either an embedding or why there is none. */
    public record Result(Embedding embedding, String failure) {
        static Result failed(String failure) {
            return new Result(null, failure);
        }
    }
}
