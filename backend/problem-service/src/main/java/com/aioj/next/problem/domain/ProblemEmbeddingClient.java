package com.aioj.next.problem.domain;

import com.aioj.next.contract.ai.AiEmbeddingRequest;
import com.aioj.next.contract.ai.AiEmbeddingResponse;
import com.aioj.next.problem.config.PlagiarismProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

@Component
public class ProblemEmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(ProblemEmbeddingClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ProblemEmbeddingClient(PlagiarismProperties properties, ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getAiServiceUri()))
                .defaultHeader("X-Internal-Token", properties.getInternalApiToken())
                .build();
        this.objectMapper = objectMapper;
    }

    public Optional<AiEmbeddingResponse> embed(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        try {
            String response = restClient.post()
                    .uri("/api/v1/internal/ai/embedding")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new AiEmbeddingRequest(input))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);

            if (root.path("code").asInt(-1) != 0) {
                log.warn("AI embedding request returned application error");
                return Optional.empty();
            }

            AiEmbeddingResponse result = objectMapper.treeToValue(
                    root.path("data"),
                    AiEmbeddingResponse.class
            );

            if (result == null
                    || result.embedding() == null
                    || result.embedding().isEmpty()) {
                log.warn("AI embedding response contained no vector");
                return Optional.empty();
            }

            return Optional.of(result);
        } catch (RestClientResponseException ex) {
            log.warn("AI embedding request rejected with HTTP {}",
                    ex.getStatusCode().value());
            return Optional.empty();
        } catch (RestClientException ex) {
            log.warn("AI embedding service unavailable: {}",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("AI embedding response could not be parsed", ex);
            return Optional.empty();
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8204";
        }
        return value.endsWith("/")
                ? value.substring(0, value.length() - 1)
                : value;
    }
}