package com.aioj.next.problem.domain;

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
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import com.aioj.next.contract.ai.AiEmbeddingResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

@Service
public class ProblemIndexService {
    private static final String PENDING = "PENDING";
    private static final String READY = "READY";
    private static final String FAILED = "FAILED";
    private static final int EMBEDDING_BATCH_SIZE = 10;
    private static final Logger log = LoggerFactory.getLogger(ProblemIndexService.class);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ProblemMapper problemMapper;
    private final ProblemIndexChunkMapper indexChunkMapper;
    private final ObjectMapper objectMapper;
    private final ProblemEmbeddingClient embeddingClient;

    public ProblemIndexService(ProblemMapper problemMapper,
                               ProblemIndexChunkMapper indexChunkMapper,
                               ObjectMapper objectMapper,
                               ProblemEmbeddingClient embeddingClient) {
        this.problemMapper = problemMapper;
        this.indexChunkMapper = indexChunkMapper;
        this.objectMapper = objectMapper;
        this.embeddingClient = embeddingClient;
    }

    @Transactional
    public boolean indexPublicProblem(Long problemId) {
        if (problemId == null) {
            return false;
        }

        ProblemEntity problem = problemMapper.selectOne(new LambdaQueryWrapper<ProblemEntity>()
                .eq(ProblemEntity::getId, problemId)
                .eq(ProblemEntity::getDeleted, false)
                .isNull(ProblemEntity::getDeletedAt)
                .isNull(ProblemEntity::getArchivedAt)
                .eq(ProblemEntity::getVisibility, ProblemVisibility.PUBLIC));

        if (problem == null) {
            return false;
        }

        String searchText = buildSearchText(problem);
        String contentHash = sha256(searchText);
        Instant now = Instant.now();

        ProblemIndexChunkEntity current = indexChunkMapper.selectOne(
                new LambdaQueryWrapper<ProblemIndexChunkEntity>()
                        .eq(ProblemIndexChunkEntity::getProblemId, problemId));

        if (current != null && contentHash.equals(current.getContentHash())) {
            if (FAILED.equals(current.getIndexStatus())) {
                current.setIndexStatus(PENDING);
                current.setLastError(null);
                current.setUpdatedAt(now);
                indexChunkMapper.updateById(current);
                return true;
            }
            return false;
        }

        if (current == null) {
            current = new ProblemIndexChunkEntity();
            current.setProblemId(problemId);
            current.setCreatedAt(now);
        }

        current.setSearchText(searchText);
        current.setContentHash(contentHash);
        current.setEmbeddingModel(null);
        current.setEmbeddingDimension(null);
        current.setEmbeddingJson(null);
        current.setIndexStatus(PENDING);
        current.setIndexedAt(null);
        current.setLastError(null);
        current.setUpdatedAt(now);

        if (current.getId() == null) {
            indexChunkMapper.insert(current);
        } else {
            indexChunkMapper.updateById(current);
        }

        return true;
    }

    @Scheduled(
                initialDelayString = "${AIOJ_PROBLEM_INDEX_INITIAL_DELAY_MS:60000}",
                fixedDelayString = "${AIOJ_PROBLEM_INDEX_FIXED_DELAY_MS:300000}"
        )
        public void syncPublicProblems() {
            List<ProblemEntity> problems;

            try {
                problems = problemMapper.selectList(new LambdaQueryWrapper<ProblemEntity>()
                        .eq(ProblemEntity::getDeleted, false)
                        .isNull(ProblemEntity::getDeletedAt)
                        .isNull(ProblemEntity::getArchivedAt)
                        .eq(ProblemEntity::getVisibility, ProblemVisibility.PUBLIC));
            } catch (RuntimeException ex) {
                log.error("Unable to load public problems for indexing", ex);
                return;
            }

            int changed = 0;
            int failed = 0;

            for (ProblemEntity problem : problems) {
                try {
                    if (indexPublicProblem(problem.getId())) {
                        changed++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    log.warn("Failed to index problem {}", problem.getId(), ex);
                }
            }

            log.info("Problem index sync finished: total={}, changed={}, failed={}",
                    problems.size(), changed, failed);
        }

        @Scheduled(
            initialDelayString = "${AIOJ_PROBLEM_EMBEDDING_INITIAL_DELAY_MS:90000}",
            fixedDelayString = "${AIOJ_PROBLEM_EMBEDDING_FIXED_DELAY_MS:300000}"
    )
    public void syncPendingEmbeddings() {
        List<ProblemIndexChunkEntity> pending;

        try {
            pending = indexChunkMapper.selectList(new LambdaQueryWrapper<ProblemIndexChunkEntity>()
                    .eq(ProblemIndexChunkEntity::getIndexStatus, PENDING)
                    .orderByAsc(ProblemIndexChunkEntity::getUpdatedAt)
                    .last("LIMIT " + EMBEDDING_BATCH_SIZE));
        } catch (RuntimeException ex) {
            log.error("Unable to load pending problem embeddings", ex);
            return;
        }

        int indexed = 0;
        int failed = 0;

        for (ProblemIndexChunkEntity chunk : pending) {
            try {
                var result = embeddingClient.embed(chunk.getSearchText());

                if (result.isEmpty()) {
                    markEmbeddingFailed(chunk, "Embedding provider unavailable");
                    failed++;
                    continue;
                }

                AiEmbeddingResponse embedding = result.get();
                Instant now = Instant.now();

                chunk.setEmbeddingModel(embedding.model());
                chunk.setEmbeddingDimension(embedding.dimension());
                chunk.setEmbeddingJson(
                        objectMapper.writeValueAsString(embedding.embedding())
                );
                chunk.setIndexStatus(READY);
                chunk.setIndexedAt(now);
                chunk.setLastError(null);
                chunk.setUpdatedAt(now);

                indexChunkMapper.updateById(chunk);
                indexed++;
            } catch (Exception ex) {
                markEmbeddingFailed(chunk, summarizeEmbeddingError(ex));
                failed++;
                log.warn("Failed to generate embedding for problem {}",
                        chunk.getProblemId(), ex);
            }
        }

        log.info(
                "Problem embedding sync finished: total={}, indexed={}, failed={}",
                pending.size(), indexed, failed
        );
    }

    public String buildSearchText(ProblemEntity problem) {
        StringBuilder text = new StringBuilder();

        appendField(text, "题号", problem.getId() == null ? "" : problem.getId().toString());
        appendField(text, "标题", problem.getTitle());
        appendField(text, "难度",
                problem.getDifficulty() == null ? "" : problem.getDifficulty().name());
        appendField(text, "标签", parseTags(problem.getTags()));
        appendField(text, "题面", problem.getStatement());
        appendField(text, "补充说明", problem.getNotes());

        return text.toString();
    }

    private void markEmbeddingFailed(ProblemIndexChunkEntity chunk, String message) {
        chunk.setIndexStatus(FAILED);
        chunk.setLastError(message);
        chunk.setUpdatedAt(Instant.now());
        indexChunkMapper.updateById(chunk);
    }

    private String summarizeEmbeddingError(Exception ex) {
            String message = ex.getMessage();

            if (message == null || message.isBlank()) {
                message = ex.getClass().getSimpleName();
            }

            return message.length() <= 1000
                    ? message
                    : message.substring(0, 1000);
    }

    private String parseTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return "";
        }

        try {
            List<String> tags = objectMapper.readValue(rawTags, STRING_LIST);
            return tags.stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(String::trim)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        } catch (JsonProcessingException ex) {
            return rawTags.trim();
        }
    }

    private void appendField(StringBuilder target, String label, String value) {
        String normalized = normalize(value);
        if (!normalized.isEmpty()) {
            target.append(label).append(": ").append(normalized).append('\n');
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}