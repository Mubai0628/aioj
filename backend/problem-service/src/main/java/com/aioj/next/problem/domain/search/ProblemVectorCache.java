package com.aioj.next.problem.domain.search;

import com.aioj.next.problem.config.ProblemSearchProperties;
import com.aioj.next.problem.persistence.entity.ProblemSearchIndexEntity;
import com.aioj.next.problem.persistence.mapper.ProblemSearchIndexMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ready vectors in memory, decoded once with their norms. A query refreshes the
 * snapshot when it is older than the refresh interval, and only rows whose
 * content hash or model changed are read again; other queries keep using the
 * old snapshot meanwhile.
 */
@Component
public class ProblemVectorCache {
    private static final Logger log = LoggerFactory.getLogger(ProblemVectorCache.class);
    private static final int LOAD_BATCH = 200;

    private final ProblemSearchIndexMapper indexMapper;
    private final ProblemSearchProperties properties;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Instant.EPOCH);

    public ProblemVectorCache(ProblemSearchIndexMapper indexMapper, ProblemSearchProperties properties) {
        this.indexMapper = indexMapper;
        this.properties = properties;
    }

    public Map<Long, Vector> vectors() {
        Snapshot current = snapshot;
        if (current.loadedAt().plus(properties.getVectorRefreshInterval()).isBefore(Instant.now()) && refreshLock.tryLock()) {
            try {
                current = refresh(current);
                snapshot = current;
            } catch (RuntimeException ex) {
                log.warn("Problem vector cache refresh failed: {}", ex.getClass().getSimpleName());
            } finally {
                refreshLock.unlock();
            }
        }
        return current.vectors();
    }

    private Snapshot refresh(Snapshot previous) {
        Map<Long, Vector> next = new HashMap<>();
        List<Long> changed = new ArrayList<>();
        for (ProblemSearchIndexEntity head : indexMapper.readyHeads()) {
            Vector known = previous.vectors().get(head.getProblemId());
            if (known != null && known.contentHash().equals(head.getContentHash())
                    && Objects.equals(known.model(), head.getEmbeddingModel())) {
                next.put(head.getProblemId(), known);
            } else {
                changed.add(head.getProblemId());
            }
        }
        for (int from = 0; from < changed.size(); from += LOAD_BATCH) {
            List<Long> batch = changed.subList(from, Math.min(changed.size(), from + LOAD_BATCH));
            for (ProblemSearchIndexEntity row : indexMapper.selectList(new LambdaQueryWrapper<ProblemSearchIndexEntity>()
                    .select(ProblemSearchIndexEntity::getProblemId, ProblemSearchIndexEntity::getContentHash,
                            ProblemSearchIndexEntity::getEmbeddingModel, ProblemSearchIndexEntity::getEmbedding)
                    .in(ProblemSearchIndexEntity::getProblemId, batch)
                    .eq(ProblemSearchIndexEntity::getStatus, ProblemSearchIndexService.READY))) {
                float[] values = SearchVectors.fromBytes(row.getEmbedding());
                double norm = SearchVectors.norm(values);
                if (values.length > 0 && norm > 0) {
                    next.put(row.getProblemId(), new Vector(values, norm, row.getEmbeddingModel(), row.getContentHash()));
                }
            }
        }
        return new Snapshot(Map.copyOf(next), Instant.now());
    }

    public record Vector(float[] values, double norm, String model, String contentHash) {
    }

    private record Snapshot(Map<Long, Vector> vectors, Instant loadedAt) {
    }
}
