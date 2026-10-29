package com.aioj.next.problem.domain.recommendation;

import com.aioj.next.common.http.InternalRestClientFactory;
import com.aioj.next.contract.ai.AiRecommendationProfileSignal;
import com.aioj.next.problem.config.PlagiarismProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a student's confirmed weaknesses from ai-service. Recommendations must
 * load when ai-service is slow or down, so this gives up quickly and answers
 * with no signals instead of an error.
 */
@Component
public class AiRecommendationSignalClient {
    private static final Logger log = LoggerFactory.getLogger(AiRecommendationSignalClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AiRecommendationSignalClient(PlagiarismProperties properties, ObjectMapper objectMapper, InternalRestClientFactory clients) {
        this.restClient = clients.create("ai-service", stripTrailingSlash(properties.getAiServiceUri()), CONNECT_TIMEOUT, READ_TIMEOUT);
        this.objectMapper = objectMapper;
    }

    public List<AiRecommendationProfileSignal> weaknesses(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        try {
            String body = restClient.get()
                    .uri("/api/v1/internal/ai/users/{userId}/recommendation-signals?limit={limit}", userId, limit)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
            JsonNode data = root.path("data");
            if (root.path("code").asInt(-1) != 0 || !data.isArray()) {
                log.warn("AI recommendation signals returned an application error");
                return List.of();
            }
            List<AiRecommendationProfileSignal> signals = new ArrayList<>();
            for (JsonNode item : data) {
                String key = item.path("profileKey").asString("");
                if (!key.isBlank()) {
                    signals.add(new AiRecommendationProfileSignal(key, item.path("label").asString(""),
                            item.path("confidence").asDouble(0.0), item.path("evidenceCount").asInt(0)));
                }
            }
            return signals;
        } catch (RuntimeException ex) {
            log.warn("AI recommendation signals unavailable: {}", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8204";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
