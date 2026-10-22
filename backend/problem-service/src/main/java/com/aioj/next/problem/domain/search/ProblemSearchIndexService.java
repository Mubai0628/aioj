package com.aioj.next.problem.domain.search;

import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.problem.config.ProblemSearchProperties;
import com.aioj.next.problem.domain.ProblemChangedEvent;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.ProblemSearchIndexEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.ProblemSearchIndexMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps problem_search_index in line with the public problems. A change to a
 * problem refreshes its row right after commit; a scheduler embeds due rows a
 * few at a time, backing off on failure up to a fixed number of attempts; a
 * slower reconciliation repairs anything an event missed.
 */
@Service
public class ProblemSearchIndexService {
    static final String PENDING = "PENDING";
    static final String READY = "READY";
    static final String FAILED = "FAILED";
    private static final Logger log = LoggerFactory.getLogger(ProblemSearchIndexService.class);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);
    private static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    private static final Duration MAX_RETRY = Duration.ofHours(6);
    private static final Duration REARM_INTERVAL = Duration.ofMinutes(10);
    private static final int RECONCILE_PAGE = 200;

    private final ProblemMapper problemMapper;
    private final ProblemSearchIndexMapper indexMapper;
    private final ProblemEmbeddingClient embeddingClient;
    private final ProblemSearchProperties properties;
    private final ObjectMapper objectMapper;
    private volatile String lastModel;
    private volatile Instant lastRearm = Instant.EPOCH;

    public ProblemSearchIndexService(ProblemMapper problemMapper, ProblemSearchIndexMapper indexMapper,
                                     ProblemEmbeddingClient embeddingClient, ProblemSearchProperties properties,
                                     ObjectMapper objectMapper) {
        this.problemMapper = problemMapper;
        this.indexMapper = indexMapper;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProblemChanged(ProblemChangedEvent event) {
        if (!properties.isSemanticEnabled() || event.problemId() == null) {
            return;
        }
        try {
            refresh(event.problemId());
        } catch (RuntimeException ex) {
            // The change itself is committed; reconciliation brings the row in line later.
            log.warn("Search index refresh for problem {} deferred: {}", event.problemId(), ex.getClass().getSimpleName());
        }
    }

    /** Removes the row unless the problem is public and active; re-queues it when its text changed. */
    public void refresh(Long problemId) {
        ProblemEntity problem = problemMapper.selectOne(publicProblems().eq(ProblemEntity::getId, problemId));
        if (problem == null) {
            indexMapper.deleteById(problemId);
            return;
        }
        ProblemSearchIndexEntity current = indexMapper.selectOne(new LambdaQueryWrapper<ProblemSearchIndexEntity>()
                .select(ProblemSearchIndexEntity::getProblemId, ProblemSearchIndexEntity::getContentHash)
                .eq(ProblemSearchIndexEntity::getProblemId, problemId));
        upsert(problem, current == null ? null : current.getContentHash(), Instant.now());
    }

    @Scheduled(initialDelayString = "${aioj.problem-search.embed-initial-delay-millis:20000}",
            fixedDelayString = "${aioj.problem-search.embed-poll-millis:30000}")
    public void embedDue() {
        if (!properties.isSemanticEnabled()) {
            return;
        }
        Instant now = Instant.now();
        List<ProblemSearchIndexEntity> due;
        try {
            due = indexMapper.selectList(new LambdaQueryWrapper<ProblemSearchIndexEntity>()
                    .select(ProblemSearchIndexEntity::getProblemId, ProblemSearchIndexEntity::getSearchText,
                            ProblemSearchIndexEntity::getContentHash, ProblemSearchIndexEntity::getAttempts)
                    .in(ProblemSearchIndexEntity::getStatus, PENDING, FAILED)
                    .le(ProblemSearchIndexEntity::getNextAttemptAt, now)
                    .lt(ProblemSearchIndexEntity::getAttempts, properties.getMaxAttempts())
                    .orderByAsc(ProblemSearchIndexEntity::getNextAttemptAt)
                    .last("LIMIT " + Math.max(1, properties.getEmbedBatchSize())));
        } catch (RuntimeException ex) {
            log.warn("Problem search embedding skipped: {}", ex.getClass().getSimpleName());
            return;
        }
        for (ProblemSearchIndexEntity row : due) {
            embed(row, now);
        }
    }

    @Scheduled(initialDelayString = "${aioj.problem-search.reconcile-initial-delay-millis:60000}",
            fixedDelayString = "${aioj.problem-search.reconcile-millis:1800000}")
    public void reconcile() {
        if (!properties.isSemanticEnabled()) {
            return;
        }
        try {
            int removed = indexMapper.deleteNotPublic();
            Map<Long, String> hashes = new HashMap<>();
            indexMapper.selectList(new LambdaQueryWrapper<ProblemSearchIndexEntity>()
                            .select(ProblemSearchIndexEntity::getProblemId, ProblemSearchIndexEntity::getContentHash))
                    .forEach(row -> hashes.put(row.getProblemId(), row.getContentHash()));
            int queued = 0;
            Long afterId = 0L;
            Instant now = Instant.now();
            while (true) {
                List<ProblemEntity> page = problemMapper.selectList(publicProblems()
                        .gt(ProblemEntity::getId, afterId)
                        .orderByAsc(ProblemEntity::getId)
                        .last("LIMIT " + RECONCILE_PAGE));
                for (ProblemEntity problem : page) {
                    if (upsert(problem, hashes.get(problem.getId()), now)) {
                        queued++;
                    }
                }
                if (page.size() < RECONCILE_PAGE) {
                    break;
                }
                afterId = page.get(page.size() - 1).getId();
            }
            if (removed > 0 || queued > 0) {
                log.info("Problem search index reconciled: queued={}, removed={}", queued, removed);
            }
        } catch (RuntimeException ex) {
            log.warn("Problem search index reconciliation failed: {}", ex.getClass().getSimpleName());
        }
    }

    /**
     * Seen on every successful embedding. A new model re-queues rows made by the
     * old one, and proof that the provider works again re-arms rows that used up
     * their attempts while it was down.
     */
    void observeHealthyEmbedding(String model, int dimension) {
        Instant now = Instant.now();
        if (StringUtils.hasText(model) && !model.equals(lastModel)) {
            int requeued = indexMapper.requeueOtherModels(model, dimension, now);
            lastModel = model;
            if (requeued > 0) {
                log.info("Embedding model is now {}; re-embedding {} problems", model, requeued);
            }
        }
        if (lastRearm.plus(REARM_INTERVAL).isBefore(now)) {
            lastRearm = now;
            indexMapper.update(null, new LambdaUpdateWrapper<ProblemSearchIndexEntity>()
                    .eq(ProblemSearchIndexEntity::getStatus, FAILED)
                    .ge(ProblemSearchIndexEntity::getAttempts, properties.getMaxAttempts())
                    .set(ProblemSearchIndexEntity::getAttempts, 0)
                    .set(ProblemSearchIndexEntity::getNextAttemptAt, now)
                    .set(ProblemSearchIndexEntity::getUpdatedAt, now));
        }
    }

    private void embed(ProblemSearchIndexEntity row, Instant now) {
        if (indexMapper.claim(row.getProblemId(), row.getContentHash(), now, now.plus(CLAIM_LEASE)) == 0) {
            return;
        }
        ProblemEmbeddingClient.Result result = embeddingClient.embed(row.getSearchText(), "PROBLEM_INDEX");
        Instant done = Instant.now();
        // Matching the hash keeps a vector of an older text from landing on a newer one.
        LambdaUpdateWrapper<ProblemSearchIndexEntity> sameContent = new LambdaUpdateWrapper<ProblemSearchIndexEntity>()
                .eq(ProblemSearchIndexEntity::getProblemId, row.getProblemId())
                .eq(ProblemSearchIndexEntity::getContentHash, row.getContentHash());
        if (result.embedding() != null) {
            float[] vector = result.embedding().vector();
            indexMapper.update(null, sameContent
                    .set(ProblemSearchIndexEntity::getEmbedding, SearchVectors.toBytes(vector))
                    .set(ProblemSearchIndexEntity::getEmbeddingModel, result.embedding().model())
                    .set(ProblemSearchIndexEntity::getEmbeddingDimension, vector.length)
                    .set(ProblemSearchIndexEntity::getStatus, READY)
                    .set(ProblemSearchIndexEntity::getAttempts, 0)
                    .set(ProblemSearchIndexEntity::getNextAttemptAt, null)
                    .set(ProblemSearchIndexEntity::getLastError, null)
                    .set(ProblemSearchIndexEntity::getIndexedAt, done)
                    .set(ProblemSearchIndexEntity::getUpdatedAt, done));
            observeHealthyEmbedding(result.embedding().model(), vector.length);
            return;
        }
        int attempts = Objects.requireNonNullElse(row.getAttempts(), 0) + 1;
        indexMapper.update(null, sameContent
                .set(ProblemSearchIndexEntity::getStatus, FAILED)
                .set(ProblemSearchIndexEntity::getAttempts, attempts)
                .set(ProblemSearchIndexEntity::getNextAttemptAt,
                        attempts >= properties.getMaxAttempts() ? null : done.plus(backoff(attempts)))
                .set(ProblemSearchIndexEntity::getLastError, SearchVectors.cut(String.valueOf(result.failure()), 255))
                .set(ProblemSearchIndexEntity::getUpdatedAt, done));
    }

    /** 1, 2, 4 ... minutes after each failed attempt, at most six hours. */
    static Duration backoff(int attempts) {
        Duration delay = FIRST_RETRY.multipliedBy(1L << Math.min(Math.max(attempts, 1) - 1, 16));
        return delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay;
    }

    /** Writes a pending row for a new or changed text; returns false when the text is unchanged. */
    private boolean upsert(ProblemEntity problem, String currentHash, Instant now) {
        String text = SearchVectors.searchText(problem.getTitle(), problem.getDifficulty(),
                SearchVectors.tags(objectMapper, problem.getTags()),
                problem.getStatement(), problem.getNotes(), properties.getMaxInputChars());
        String hash = SearchVectors.sha256(text);
        if (hash.equals(currentHash)) {
            return false;
        }
        if (currentHash == null) {
            ProblemSearchIndexEntity row = new ProblemSearchIndexEntity();
            row.setProblemId(problem.getId());
            row.setSearchText(text);
            row.setContentHash(hash);
            row.setStatus(PENDING);
            row.setAttempts(0);
            row.setNextAttemptAt(now);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            try {
                indexMapper.insert(row);
                return true;
            } catch (DuplicateKeyException ignored) {
                // Another instance inserted it first; overwrite with this text below.
            }
        }
        indexMapper.update(null, new LambdaUpdateWrapper<ProblemSearchIndexEntity>()
                .eq(ProblemSearchIndexEntity::getProblemId, problem.getId())
                .set(ProblemSearchIndexEntity::getSearchText, text)
                .set(ProblemSearchIndexEntity::getContentHash, hash)
                .set(ProblemSearchIndexEntity::getEmbedding, null)
                .set(ProblemSearchIndexEntity::getEmbeddingModel, null)
                .set(ProblemSearchIndexEntity::getEmbeddingDimension, null)
                .set(ProblemSearchIndexEntity::getStatus, PENDING)
                .set(ProblemSearchIndexEntity::getAttempts, 0)
                .set(ProblemSearchIndexEntity::getNextAttemptAt, now)
                .set(ProblemSearchIndexEntity::getLastError, null)
                .set(ProblemSearchIndexEntity::getIndexedAt, null)
                .set(ProblemSearchIndexEntity::getUpdatedAt, now));
        return true;
    }

    private LambdaQueryWrapper<ProblemEntity> publicProblems() {
        return new LambdaQueryWrapper<ProblemEntity>()
                .select(ProblemEntity::getId, ProblemEntity::getTitle, ProblemEntity::getDifficulty, ProblemEntity::getTags,
                        ProblemEntity::getStatement, ProblemEntity::getNotes)
                .eq(ProblemEntity::getDeleted, false)
                .isNull(ProblemEntity::getDeletedAt)
                .isNull(ProblemEntity::getArchivedAt)
                .eq(ProblemEntity::getVisibility, ProblemVisibility.PUBLIC);
    }

}
