package com.aioj.next.ai.agent.asyncjob;

import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.ai.persistence.mapper.AiAsyncJobMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Persistence and lifecycle for ai_async_jobs (V60): idempotent enqueue, lease-based
 * claiming (multi-instance safe via CAS), completion, and exponential-backoff failure.
 * Handler dispatch lives in AgentAsyncJobWorker; this class owns only state transitions.
 */
@Service
public class AgentAsyncJobService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private static final long MAX_BACKOFF_SECONDS = 3600L;

    private static final Logger log = LoggerFactory.getLogger(AgentAsyncJobService.class);

    private final AiAsyncJobMapper mapper;

    public AgentAsyncJobService(AiAsyncJobMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Idempotent enqueue: a duplicate idempotency key is a no-op by design (unique key
     * uk_ai_async_jobs_idempotency), so producers may enqueue freely on retries.
     *
     * @return true when a new job row was inserted
     */
    public boolean enqueue(String jobType, String idempotencyKey, String payloadJson, int maxAttempts) {
        AiAsyncJobEntity job = new AiAsyncJobEntity();
        job.setJobType(jobType);
        job.setStatus(STATUS_PENDING);
        job.setIdempotencyKey(idempotencyKey);
        job.setPayloadJson(payloadJson);
        job.setAttemptCount(0);
        job.setMaxAttempts(Math.max(1, maxAttempts));
        LocalDateTime now = LocalDateTime.now();
        job.setNextRetryAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        try {
            return mapper.insert(job) > 0;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /**
     * Claim due jobs for a worker. Each row is leased with a CAS on status so two
     * instances never process the same job concurrently; a crashed worker's lease
     * expires and the row becomes claimable again.
     */
    public List<AiAsyncJobEntity> claimDueJobs(int batchSize, long leaseSeconds, String workerId) {
        return claimDueJobs(batchSize, leaseSeconds, workerId, null);
    }

    /**
     * As above, skipping the given job types (T1.7: a switched-off subscriber's jobs wait PENDING).
     * Every other type is claimed, so a job nobody handles still reaches the worker and is parked.
     */
    public List<AiAsyncJobEntity> claimDueJobs(int batchSize, long leaseSeconds, String workerId,
                                               Collection<String> skippedTypes) {
        LocalDateTime now = LocalDateTime.now();
        QueryWrapper<AiAsyncJobEntity> due = new QueryWrapper<AiAsyncJobEntity>()
                .eq("status", STATUS_PENDING)
                .le("next_retry_at", now);
        QueryWrapper<AiAsyncJobEntity> expired = new QueryWrapper<AiAsyncJobEntity>()
                .eq("status", STATUS_RUNNING)
                .lt("lease_expires_at", now);
        if (skippedTypes != null && !skippedTypes.isEmpty()) {
            due.notIn("job_type", skippedTypes);
            expired.notIn("job_type", skippedTypes);
        }
        List<AiAsyncJobEntity> candidates = mapper.selectList(due
                .orderByAsc("next_retry_at")
                .last("LIMIT " + Math.max(1, batchSize)));
        // Recover jobs whose previous worker died holding a lease.
        List<AiAsyncJobEntity> expiredLeases = mapper.selectList(expired
                .orderByAsc("lease_expires_at")
                .last("LIMIT " + Math.max(1, batchSize)));
        List<AiAsyncJobEntity> claimed = new ArrayList<>();
        for (AiAsyncJobEntity candidate : concat(candidates, expiredLeases)) {
            int updated = mapper.update(null, new UpdateWrapper<AiAsyncJobEntity>()
                    .eq("id", candidate.getId())
                    .in("status", STATUS_PENDING, STATUS_RUNNING)
                    .set("status", STATUS_RUNNING)
                    .set("lease_owner", workerId)
                    .set("lease_expires_at", now.plusSeconds(Math.max(1, leaseSeconds)))
                    .set("updated_at", now));
            if (updated > 0) {
                candidate.setStatus(STATUS_RUNNING);
                candidate.setLeaseOwner(workerId);
                claimed.add(candidate);
            }
        }
        return claimed;
    }

    public void complete(AiAsyncJobEntity job) {
        LocalDateTime now = LocalDateTime.now();
        mapper.update(null, new UpdateWrapper<AiAsyncJobEntity>()
                .eq("id", job.getId())
                .eq("status", STATUS_RUNNING)
                .set("status", STATUS_COMPLETED)
                .set("lease_owner", null)
                .set("lease_expires_at", null)
                .set("completed_at", now)
                .set("updated_at", now));
    }

    /** Retryable failure: back off exponentially until maxAttempts, then park as FAILED. */
    public void fail(AiAsyncJobEntity job, Throwable error, long backoffBaseSeconds) {
        int attempts = (job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1;
        int maxAttempts = job.getMaxAttempts() == null ? 1 : Math.max(1, job.getMaxAttempts());
        boolean exhausted = attempts >= maxAttempts;
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<AiAsyncJobEntity> update = new UpdateWrapper<AiAsyncJobEntity>()
                .eq("id", job.getId())
                .set("attempt_count", attempts)
                .set("status", exhausted ? STATUS_FAILED : STATUS_PENDING)
                .set("lease_owner", null)
                .set("lease_expires_at", null)
                .set("last_error", truncate(error))
                .set("updated_at", now);
        if (!exhausted) {
            long backoff = Math.min(MAX_BACKOFF_SECONDS, backoffBaseSeconds * (1L << Math.min(attempts - 1, 10)));
            update.set("next_retry_at", now.plusSeconds(Math.max(1, backoff)));
        } else {
            update.set("completed_at", now);
        }
        mapper.update(null, update);
        if (exhausted) {
            log.warn("async job exhausted retries: id={} type={} attempts={}", job.getId(), job.getJobType(), attempts);
        }
    }

    /** Permanent failure: park immediately without further retries (e.g. unknown job type). */
    public void failFinal(AiAsyncJobEntity job, String message) {
        LocalDateTime now = LocalDateTime.now();
        mapper.update(null, new UpdateWrapper<AiAsyncJobEntity>()
                .eq("id", job.getId())
                .set("status", STATUS_FAILED)
                .set("lease_owner", null)
                .set("lease_expires_at", null)
                .set("last_error", message == null ? "unknown" : message.substring(0, Math.min(1000, message.length())))
                .set("completed_at", now)
                .set("updated_at", now));
    }

    /**
     * Operator replay (T1.7): puts up to {@code limit} dead letters (FAILED jobs) of one consumer
     * back to PENDING with a fresh attempt budget; with {@code eventId}, only that event's job.
     *
     * @return how many jobs were requeued
     */
    public int requeueFailed(String jobType, String eventId, int limit) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<AiAsyncJobEntity> update = new UpdateWrapper<AiAsyncJobEntity>()
                .eq("job_type", jobType)
                .eq("status", STATUS_FAILED);
        if (eventId != null && !eventId.isBlank()) {
            update.eq("idempotency_key", eventId.strip() + ":" + jobType);
        }
        update.set("status", STATUS_PENDING)
                .set("attempt_count", 0)
                .set("next_retry_at", now)
                .set("lease_owner", null)
                .set("lease_expires_at", null)
                .set("completed_at", null)
                .set("updated_at", now)
                .orderByAsc("id")
                .last("LIMIT " + Math.min(500, Math.max(1, limit)));
        return mapper.update(null, update);
    }

    /** Per job type and status: how many jobs and the oldest one's creation time (T1.7 backlog gauges). */
    public List<Map<String, Object>> countsByTypeAndStatus() {
        return mapper.selectMaps(new QueryWrapper<AiAsyncJobEntity>()
                .select("job_type", "status", "COUNT(*) AS job_count", "MIN(created_at) AS oldest_created_at")
                .in("status", STATUS_PENDING, STATUS_RUNNING, STATUS_FAILED)
                .groupBy("job_type", "status"));
    }

    private List<AiAsyncJobEntity> concat(List<AiAsyncJobEntity> first, List<AiAsyncJobEntity> second) {
        List<AiAsyncJobEntity> all = new ArrayList<>(first);
        for (AiAsyncJobEntity job : second) {
            if (all.stream().noneMatch(existing -> existing.getId().equals(job.getId()))) {
                all.add(job);
            }
        }
        return all;
    }

    private String truncate(Throwable error) {
        String message = error == null ? "unknown" : error.toString();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
