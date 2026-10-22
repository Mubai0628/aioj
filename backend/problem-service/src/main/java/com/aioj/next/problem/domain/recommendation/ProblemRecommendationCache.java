package com.aioj.next.problem.domain.recommendation;

import com.aioj.next.contract.problem.ProblemRecommendationReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A student's ranked recommendations, kept in Redis for a few minutes. A finished
 * practice submission clears them; a Redis outage only means ranking again.
 */
@Component
public class ProblemRecommendationCache {
    private static final Logger log = LoggerFactory.getLogger(ProblemRecommendationCache.class);
    static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ProblemRecommendationCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Ranking get(Long userId) {
        try {
            String json = redisTemplate.opsForValue().get(key(userId));
            return json == null ? null : objectMapper.readValue(json, Ranking.class);
        } catch (RuntimeException ex) {
            log.warn("Problem recommendation cache read skipped: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    public void put(Long userId, Ranking ranking) {
        try {
            redisTemplate.opsForValue().set(key(userId), objectMapper.writeValueAsString(ranking), TTL);
        } catch (RuntimeException ex) {
            log.warn("Problem recommendation cache write skipped: {}", ex.getClass().getSimpleName());
        }
    }

    public void evict(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException ex) {
            log.warn("Problem recommendation cache eviction skipped: {}", ex.getClass().getSimpleName());
        }
    }

    static String key(Long userId) {
        return "aioj:problem-recommendations:v1:" + userId;
    }

    /** Ranked problem ids with their reasons; titles and visibility are read fresh when served. */
    public record Ranking(List<Entry> entries, long solvedProblems, boolean coldStart) {
    }

    public record Entry(Long problemId, double score, ProblemRecommendationReason reason, Map<String, String> reasonParams) {
    }
}
