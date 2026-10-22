package com.aioj.next.problem.domain.search;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemSearchFallbackReason;
import com.aioj.next.contract.problem.ProblemSearchHit;
import com.aioj.next.contract.problem.ProblemSearchMode;
import com.aioj.next.contract.problem.ProblemSearchResponse;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.problem.config.ProblemSearchProperties;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Search over public problems. Keyword mode matches title, problem number and
 * exact tag, like the catalog. Semantic mode ranks by 0.75 x cosine similarity
 * to the query plus 0.25 x a keyword match score, over the nearest vectors and
 * the keyword hits; whenever it cannot run it answers by keyword and says why.
 * Results never include statement text, and visibility is always read fresh.
 */
@Service
public class ProblemSearchService {
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    private static final Logger log = LoggerFactory.getLogger(ProblemSearchService.class);
    private static final double VECTOR_WEIGHT = 0.75;
    private static final double KEYWORD_WEIGHT = 0.25;
    /** Nearest vectors kept per result slot, before visibility and difficulty filtering. */
    private static final int NEIGHBOURS_PER_RESULT = 3;
    private static final Duration RATE_WINDOW_TTL = Duration.ofMinutes(2);

    private final ProblemMapper problemMapper;
    private final ProblemVectorCache vectorCache;
    private final ProblemEmbeddingClient embeddingClient;
    private final ProblemSearchIndexService indexService;
    private final ProblemSearchProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ProblemSearchService(ProblemMapper problemMapper, ProblemVectorCache vectorCache,
                                ProblemEmbeddingClient embeddingClient, ProblemSearchIndexService indexService,
                                ProblemSearchProperties properties, StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.problemMapper = problemMapper;
        this.vectorCache = vectorCache;
        this.embeddingClient = embeddingClient;
        this.indexService = indexService;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public ProblemSearchResponse search(String rawQuery, String mode, Difficulty difficulty, Integer requestedLimit) {
        String query = SearchVectors.cut(SearchVectors.compactWhitespace(rawQuery), Math.max(1, properties.getMaxQueryChars()));
        if (query.isEmpty()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Search query is required");
        }
        int limit = requestedLimit == null || requestedLimit <= 0 ? DEFAULT_LIMIT : Math.min(requestedLimit, MAX_LIMIT);
        boolean semantic = "semantic".equalsIgnoreCase(mode);
        ProblemSearchResponse response = semantic
                ? semantic(query, difficulty, limit)
                : keyword(query, difficulty, limit, null);
        meterRegistry.counter("aioj.problem.search.requests", "mode", semantic ? "semantic" : "keyword",
                "result", response.fallback() ? response.fallbackReason().name() : response.mode().name()).increment();
        return response;
    }

    private ProblemSearchResponse semantic(String query, Difficulty difficulty, int limit) {
        if (!properties.isSemanticEnabled()) {
            return keyword(query, difficulty, limit, ProblemSearchFallbackReason.SEMANTIC_DISABLED);
        }
        if (!withinRateLimit(SecuritySupport.currentUserId())) {
            return keyword(query, difficulty, limit, ProblemSearchFallbackReason.RATE_LIMITED);
        }
        Map<Long, ProblemVectorCache.Vector> vectors = vectorCache.vectors();
        if (vectors.isEmpty()) {
            return keyword(query, difficulty, limit, ProblemSearchFallbackReason.INDEX_NOT_READY);
        }
        QueryVector queryVector = cachedQueryVector(query);
        if (queryVector == null || !hasModel(vectors, queryVector)) {
            ProblemEmbeddingClient.Result result = embeddingClient.embed(query, "PROBLEM_QUERY");
            if (result.embedding() == null) {
                indexService.observeFailedEmbedding();
                return keyword(query, difficulty, limit, ProblemSearchFallbackReason.EMBEDDING_UNAVAILABLE);
            }
            queryVector = new QueryVector(result.embedding().vector(), result.embedding().model());
            cacheQueryVector(query, queryVector);
            indexService.observeHealthyEmbedding(queryVector.model(), queryVector.values().length);
        }
        Map<Long, Double> neighbours = nearest(vectors, queryVector, limit * NEIGHBOURS_PER_RESULT);
        if (neighbours.isEmpty()) {
            // The index still holds another model's vectors and is being rebuilt.
            return keyword(query, difficulty, limit, ProblemSearchFallbackReason.INDEX_NOT_READY);
        }
        Set<Long> ids = new LinkedHashSet<>(neighbours.keySet());
        keywordProblems(query, difficulty, limit).forEach(problem -> ids.add(problem.getId()));
        List<ProblemSearchHit> hits = problemMapper.selectList(publicProblems()
                        .eq(difficulty != null, ProblemEntity::getDifficulty, difficulty)
                        .in(ProblemEntity::getId, ids))
                .stream()
                .map(problem -> hit(problem, VECTOR_WEIGHT * Math.max(0, neighbours.getOrDefault(problem.getId(), 0.0))
                        + KEYWORD_WEIGHT * keywordScore(query, problem, tags(problem))))
                .sorted(Comparator.comparingDouble(ProblemSearchHit::score).reversed())
                .limit(limit)
                .toList();
        return new ProblemSearchResponse(hits, ProblemSearchMode.SEMANTIC, false, null);
    }

    private ProblemSearchResponse keyword(String query, Difficulty difficulty, int limit, ProblemSearchFallbackReason reason) {
        List<ProblemSearchHit> hits = keywordProblems(query, difficulty, limit).stream()
                .map(problem -> hit(problem, keywordScore(query, problem, tags(problem))))
                .sorted(Comparator.comparingDouble(ProblemSearchHit::score).reversed())
                .toList();
        return new ProblemSearchResponse(hits, ProblemSearchMode.KEYWORD, reason != null, reason);
    }

    /** Title, problem number or an exact tag, newest first; never statement text. */
    private List<ProblemEntity> keywordProblems(String query, Difficulty difficulty, int limit) {
        return problemMapper.selectList(publicProblems()
                .eq(difficulty != null, ProblemEntity::getDifficulty, difficulty)
                .and(match -> match.like(ProblemEntity::getTitle, query)
                        .or().apply("CAST(id AS CHAR) LIKE {0}", "%" + query + "%")
                        .or().apply("JSON_CONTAINS(tags, JSON_QUOTE({0}))", query))
                .orderByDesc(ProblemEntity::getCreatedAt)
                .orderByDesc(ProblemEntity::getId)
                .last("LIMIT " + limit));
    }

    private Map<Long, Double> nearest(Map<Long, ProblemVectorCache.Vector> vectors, QueryVector query, int count) {
        double queryNorm = SearchVectors.norm(query.values());
        Map<Long, Double> similarities = new HashMap<>();
        vectors.forEach((problemId, vector) -> {
            if (query.model().equals(vector.model()) && vector.values().length == query.values().length) {
                similarities.put(problemId, SearchVectors.cosine(query.values(), queryNorm, vector.values(), vector.norm()));
            }
        });
        Map<Long, Double> nearest = new HashMap<>();
        similarities.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(count)
                .forEach(entry -> nearest.put(entry.getKey(), entry.getValue()));
        return nearest;
    }

    private boolean hasModel(Map<Long, ProblemVectorCache.Vector> vectors, QueryVector query) {
        return vectors.values().stream().anyMatch(vector -> query.model().equals(vector.model())
                && vector.values().length == query.values().length);
    }

    /**
     * 1.0 when the query names a tag or the problem number, 0.8 when it names
     * the title (either containing the other), else 0.
     */
    static double keywordScore(String query, ProblemEntity problem, List<String> tags) {
        String q = compact(query);
        if (q.isEmpty()) {
            return 0;
        }
        if (q.equals(String.valueOf(problem.getId()))) {
            return 1.0;
        }
        for (String tag : tags) {
            if (names(q, compact(tag))) {
                return 1.0;
            }
        }
        return names(q, compact(problem.getTitle())) ? 0.8 : 0;
    }

    /** Containment either way; a one-character side only counts as an exact match. */
    private static boolean names(String query, String text) {
        if (text.isEmpty()) {
            return false;
        }
        return text.equals(query)
                || (query.length() >= 2 && text.contains(query))
                || (text.length() >= 2 && query.contains(text));
    }

    private static String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private ProblemSearchHit hit(ProblemEntity problem, double score) {
        return new ProblemSearchHit(problem.getId(), problem.getTitle(), problem.getDifficulty(), tags(problem),
                BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP).doubleValue());
    }

    private boolean withinRateLimit(Long userId) {
        int limit = properties.getQueryRateLimitPerMinute();
        if (limit <= 0 || userId == null) {
            return true;
        }
        String key = "aioj:problem-search-rate:" + userId + ":" + Instant.now().getEpochSecond() / 60;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, RATE_WINDOW_TTL);
            }
            return count == null || count <= limit;
        } catch (RuntimeException ex) {
            // A broken limiter must not turn semantic search off for everyone.
            return true;
        }
    }

    private QueryVector cachedQueryVector(String query) {
        try {
            String json = redisTemplate.opsForValue().get(queryKey(query));
            if (json == null) {
                return null;
            }
            CachedQueryVector cached = objectMapper.readValue(json, CachedQueryVector.class);
            float[] values = SearchVectors.fromBytes(Base64.getDecoder().decode(cached.vector()));
            return values.length == 0 || cached.model() == null ? null : new QueryVector(values, cached.model());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void cacheQueryVector(String query, QueryVector vector) {
        try {
            String json = objectMapper.writeValueAsString(new CachedQueryVector(vector.model(),
                    Base64.getEncoder().encodeToString(SearchVectors.toBytes(vector.values()))));
            redisTemplate.opsForValue().set(queryKey(query), json, properties.getQueryVectorTtl());
        } catch (RuntimeException ex) {
            log.debug("Query vector cache write skipped: {}", ex.getClass().getSimpleName());
        }
    }

    private static String queryKey(String query) {
        return "aioj:problem-search:query:v1:" + SearchVectors.sha256(query);
    }

    private LambdaQueryWrapper<ProblemEntity> publicProblems() {
        return new LambdaQueryWrapper<ProblemEntity>()
                .select(ProblemEntity::getId, ProblemEntity::getTitle, ProblemEntity::getDifficulty,
                        ProblemEntity::getTags, ProblemEntity::getCreatedAt)
                .eq(ProblemEntity::getDeleted, false)
                .isNull(ProblemEntity::getDeletedAt)
                .isNull(ProblemEntity::getArchivedAt)
                .eq(ProblemEntity::getVisibility, ProblemVisibility.PUBLIC);
    }

    private List<String> tags(ProblemEntity problem) {
        return SearchVectors.tags(objectMapper, problem.getTags());
    }

    private record QueryVector(float[] values, String model) {
    }

    record CachedQueryVector(String model, String vector) {
    }
}
