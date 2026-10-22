package com.aioj.next.problem.domain;

import com.aioj.next.contract.ai.AiRecommendationProfileSignal;
import com.aioj.next.problem.config.PlagiarismProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class AiProfileRecommendationClient {
    private static final Logger log =
            LoggerFactory.getLogger(AiProfileRecommendationClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AiProfileRecommendationClient(
            PlagiarismProperties properties,
            ObjectMapper objectMapper
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getAiServiceUri()))
                .defaultHeader("X-Internal-Token", properties.getInternalApiToken())
                .build();
        this.objectMapper = objectMapper;
    }

    public List<AiRecommendationProfileSignal> fetch(
            Long userId,
            int limit
    ) {
        if (userId == null) {
            return List.of();
        }

        try {
            String response = restClient.get()
                    .uri(
                            "/api/v1/internal/ai/users/{userId}/recommendation-signals?limit={limit}",
                            userId,
                            limit
                    )
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);

            if (root.path("code").asInt(-1) != 0) {
                log.warn("AI profile recommendation request returned an application error");
                return List.of();
            }

            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return List.of();
            }

            return objectMapper.convertValue(
                    data,
                    new TypeReference<List<AiRecommendationProfileSignal>>() {
                    }
            );
        } catch (RestClientException ex) {
            log.warn("AI profile recommendation service unavailable: {}",
                    ex.getClass().getSimpleName());
            return List.of();
        } catch (Exception ex) {
            log.warn("AI profile recommendation response could not be parsed", ex);
            return List.of();
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