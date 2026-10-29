package com.aioj.next.problem.domain;

import com.aioj.next.common.http.InternalRestClientFactory;
import com.aioj.next.contract.submission.JudgeMetricsResponse;
import com.aioj.next.problem.config.InternalApiProperties;
import com.aioj.next.problem.config.JudgeQueueConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Collects judge health for the admin monitor: counters from the judge worker's
 * internal endpoint and queue depths from the broker. Either source may be down;
 * the page then shows what is known instead of failing.
 */
@Service
public class JudgeMetricsService {
    private static final Logger log = LoggerFactory.getLogger(JudgeMetricsService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AmqpAdmin amqpAdmin;

    public JudgeMetricsService(InternalApiProperties properties, ObjectMapper objectMapper, AmqpAdmin amqpAdmin, InternalRestClientFactory clients) {
        this.restClient = clients.create("judge-worker", stripTrailingSlash(properties.getJudgeWorkerBaseUrl()), "judge-metrics", TIMEOUT, TIMEOUT);
        this.objectMapper = objectMapper;
        this.amqpAdmin = amqpAdmin;
    }

    public JudgeMetricsResponse metrics() {
        Optional<WorkerSnapshot> worker = workerSnapshot();
        QueueInformation queue = queueInfo(JudgeQueueConfig.JUDGE_QUEUE);
        QueueInformation deadLetter = queueInfo(JudgeQueueConfig.JUDGE_DEAD_LETTER_QUEUE);
        WorkerSnapshot snapshot = worker.orElse(null);
        return new JudgeMetricsResponse(
                worker.isPresent(),
                snapshot == null ? null : snapshot.started(),
                snapshot == null ? null : snapshot.finished(),
                snapshot == null ? null : snapshot.failed(),
                snapshot == null ? null : snapshot.systemErrors(),
                snapshot == null ? null : snapshot.inFlight(),
                snapshot == null ? null : snapshot.averageQueueWaitMillis(),
                snapshot == null ? null : snapshot.averageJudgeMillis(),
                snapshot == null ? null : snapshot.lastFinishedAt(),
                snapshot == null ? null : snapshot.lastFailureAt(),
                snapshot == null ? null : snapshot.lastFailureType(),
                queue == null ? null : (long) queue.getMessageCount(),
                queue == null ? null : queue.getConsumerCount(),
                deadLetter == null ? null : (long) deadLetter.getMessageCount(),
                Instant.now());
    }

    private Optional<WorkerSnapshot> workerSnapshot() {
        try {
            String body = restClient.get()
                    .uri("/api/v1/internal/judge/metrics")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            JsonNode data = objectMapper.readTree(body == null ? "" : body).path("data");
            if (!data.isObject()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.treeToValue(data, WorkerSnapshot.class));
        } catch (RuntimeException ex) {
            log.warn("Judge worker metrics unavailable: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private QueueInformation queueInfo(String queue) {
        try {
            return amqpAdmin.getQueueInfo(queue);
        } catch (RuntimeException ex) {
            log.warn("Judge queue depth unavailable queue={}: {}", queue, ex.getClass().getSimpleName());
            return null;
        }
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://127.0.0.1:8203";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkerSnapshot(Long started,
                          Long finished,
                          Long failed,
                          Long systemErrors,
                          Integer inFlight,
                          Instant lastFinishedAt,
                          Instant lastFailureAt,
                          String lastFailureType,
                          Long averageQueueWaitMillis,
                          Long averageJudgeMillis) {
    }
}
