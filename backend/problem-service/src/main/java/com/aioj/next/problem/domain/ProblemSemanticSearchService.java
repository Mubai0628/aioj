package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiEmbeddingResponse;
import com.aioj.next.contract.problem.ProblemSemanticSearchRecord;
import com.aioj.next.contract.problem.ProblemSemanticSearchResponse;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.ProblemIndexChunkEntity;
import com.aioj.next.problem.persistence.mapper.ProblemIndexChunkMapper;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Service
public class ProblemSemanticSearchService {
    private static final String READY = "READY";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private static final TypeReference<List<Double>> VECTOR_TYPE =
            new TypeReference<>() {
            };

    private final ProblemEmbeddingClient embeddingClient;
    private final ProblemIndexChunkMapper indexChunkMapper;
    private final ProblemMapper problemMapper;
    private final ProblemCatalog problemCatalog;
    private final ObjectMapper objectMapper;

    public ProblemSemanticSearchService(
            ProblemEmbeddingClient embeddingClient,
            ProblemIndexChunkMapper indexChunkMapper,
            ProblemMapper problemMapper,
            ProblemCatalog problemCatalog,
            ObjectMapper objectMapper) {
        this.embeddingClient = embeddingClient;
        this.indexChunkMapper = indexChunkMapper;
        this.problemMapper = problemMapper;
        this.problemCatalog = problemCatalog;
        this.objectMapper = objectMapper;
    }

    public ProblemSemanticSearchResponse search(String rawQuery, Integer requestedLimit) {
        String query = rawQuery == null ? "" : rawQuery.trim();

        if (query.isBlank()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Search query is required");
        }

        int limit = normalizeLimit(requestedLimit);

        var queryEmbedding = embeddingClient.embed(query);
        if (queryEmbedding.isEmpty()) {
            return keywordFallback(query, limit);
        }

        List<ProblemIndexChunkEntity> chunks = indexChunkMapper.selectList(
                new LambdaQueryWrapper<ProblemIndexChunkEntity>()
                        .eq(ProblemIndexChunkEntity::getIndexStatus, READY)
                        .isNotNull(ProblemIndexChunkEntity::getEmbeddingJson)
        );

        if (chunks.isEmpty()) {
            return new ProblemSemanticSearchResponse(List.of(), false);
        }

        List<Long> problemIds = chunks.stream()
                .map(ProblemIndexChunkEntity::getProblemId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        Map<Long, ProblemEntity> problems = loadPublicProblems(problemIds);
        List<Double> queryVector = queryEmbedding.get().embedding();

        List<ScoredProblem> ranked = chunks.stream()
                .map(chunk -> scoreChunk(chunk, problems, query, queryVector))
                .flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparingDouble(ScoredProblem::score).reversed())
                .limit(limit)
                .toList();

        List<ProblemSemanticSearchRecord> records = ranked.stream()
                .map(item -> new ProblemSemanticSearchRecord(
                        problemCatalog.toTutorResponse(item.problem()),
                        item.score()
                ))
                .toList();

        return new ProblemSemanticSearchResponse(records, false);
    }

    private ProblemSemanticSearchResponse keywordFallback(String query, int limit) {
        List<ProblemSemanticSearchRecord> records = problemCatalog
                .tutorSearch(1, limit, query, null, null, "NEWEST")
                .records()
                .stream()
                .map(problem -> new ProblemSemanticSearchRecord(problem, 0.0))
                .toList();

        return new ProblemSemanticSearchResponse(records, true);
    }

    private Map<Long, ProblemEntity> loadPublicProblems(List<Long> problemIds) {
            if (problemIds.isEmpty()) {
                return Map.of();
            }

            return problemMapper.selectList(
                            new LambdaQueryWrapper<ProblemEntity>()
                                    .in(ProblemEntity::getId, problemIds)
                                    .eq(ProblemEntity::getDeleted, false)
                                    .isNull(ProblemEntity::getDeletedAt)
                                    .isNull(ProblemEntity::getArchivedAt)
                                    .eq(ProblemEntity::getVisibility, ProblemVisibility.PUBLIC)
                    )
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(
                            ProblemEntity::getId,
                            problem -> problem
                    ));
        }

        private java.util.Optional<ScoredProblem> scoreChunk(
            ProblemIndexChunkEntity chunk,
            Map<Long, ProblemEntity> problems,
            String query,
            List<Double> queryVector) {
            ProblemEntity problem = problems.get(chunk.getProblemId());

            if (problem == null) {
                return java.util.Optional.empty();
            }

            try {
                List<Double> problemVector =
                        objectMapper.readValue(chunk.getEmbeddingJson(), VECTOR_TYPE);

                double vectorScore = cosineSimilarity(queryVector, problemVector);
                if (!Double.isFinite(vectorScore)) {
                    return java.util.Optional.empty();
                }

                double keywordScore = keywordScore(query, problem);
                double score = vectorScore * 0.75 + keywordScore * 0.25;

                return java.util.Optional.of(new ScoredProblem(problem, score));
            } catch (JsonProcessingException ex) {
                return java.util.Optional.empty();
            }
        }

        private double keywordScore(String query, ProblemEntity problem) {
        String normalizedQuery = normalizeSearchText(query);

        boolean tagMatched = problemCatalog.tagsOf(problem).stream()
                .map(this::normalizeSearchText)
                .anyMatch(tag -> !tag.isBlank() && normalizedQuery.contains(tag));

        if (tagMatched) {
            return 1.0;
        }

        String normalizedTitle = normalizeSearchText(problem.getTitle());
        if (!normalizedTitle.isBlank() && normalizedQuery.contains(normalizedTitle)) {
            return 0.8;
        }

        return 0.0;
    }

    private String normalizeSearchText(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", "");
    }

    private double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.size() != right.size() || left.isEmpty()) {
            return Double.NaN;
        }

        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;

        for (int i = 0; i < left.size(); i++) {
            Double leftValue = left.get(i);
            Double rightValue = right.get(i);

            if (leftValue == null || rightValue == null
                    || !Double.isFinite(leftValue)
                    || !Double.isFinite(rightValue)) {
                return Double.NaN;
            }

            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }

        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return Double.NaN;
        }

        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private record ScoredProblem(ProblemEntity problem, double score) {
    }
}