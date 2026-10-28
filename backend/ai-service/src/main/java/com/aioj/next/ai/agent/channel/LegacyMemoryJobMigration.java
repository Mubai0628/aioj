package com.aioj.next.ai.agent.channel;

import com.aioj.next.agent.harness.channel.api.EventSubject;
import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.ai.domain.memory.AiMemoryJobStatus;
import com.aioj.next.ai.domain.memory.AiMemoryJobTypes;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.mapper.AiAsyncJobMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryJobMapper;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * T1.13: move unfinished, supported ai_memory_jobs into the unified channel. Each locked
 * row, its channel event and subscriber jobs share one transaction. A restarted process
 * either sees the old unfinished row or the completed migration, never half of it.
 */
@Component
public class LegacyMemoryJobMigration {
    private static final Logger log = LoggerFactory.getLogger(LegacyMemoryJobMigration.class);
    private static final int PAGE_SIZE = 100;
    private static final List<String> UNFINISHED = List.of("QUEUED", "RUNNING", "FAILED_RETRYABLE");
    private static final List<String> SUPPORTED = List.of(AiMemoryJobTypes.JOB_AI_JUDGED_SUBMISSION_ANALYSIS,
            AiMemoryJobTypes.JOB_AI_MEMORY_MERGE_REVIEW);
    private static final Set<String> ENVELOPE_FIELDS = Set.of("eventId", "eventType", "traceId");

    private final AiMemoryJobMapper legacyJobs;
    private final AiAsyncJobMapper channelJobs;
    private final HarnessEventChannel channel;
    private final ObjectMapper mapper;
    private final TransactionOperations transactions;
    private final Clock clock;

    @Autowired
    public LegacyMemoryJobMigration(AiMemoryJobMapper legacyJobs, AiAsyncJobMapper channelJobs,
                                    HarnessEventChannel channel, ObjectMapper mapper,
                                    TransactionOperations transactions) {
        this(legacyJobs, channelJobs, channel, mapper, transactions, Clock.systemDefaultZone());
    }

    LegacyMemoryJobMigration(AiMemoryJobMapper legacyJobs, AiAsyncJobMapper channelJobs,
                             HarnessEventChannel channel, ObjectMapper mapper,
                             TransactionOperations transactions, Clock clock) {
        this.legacyJobs = legacyJobs;
        this.channelJobs = channelJobs;
        this.channel = channel;
        this.mapper = mapper;
        this.transactions = transactions;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateUnfinished() {
        Long unsupported = legacyJobs.selectCount(new QueryWrapper<AiMemoryJobEntity>()
                .in("status", UNFINISHED).notIn("job_type", SUPPORTED));
        if (unsupported != null && unsupported > 0) {
            log.warn("{} unfinished legacy memory jobs have unsupported types and remain unchanged", unsupported);
        }
        long afterId = 0;
        while (true) {
            List<AiMemoryJobEntity> page = legacyJobs.selectList(new QueryWrapper<AiMemoryJobEntity>()
                    .in("status", UNFINISHED).in("job_type", SUPPORTED).gt("id", afterId)
                    .orderByAsc("id").last("LIMIT " + PAGE_SIZE));
            if (page == null || page.isEmpty()) {
                return;
            }
            for (AiMemoryJobEntity candidate : page) {
                if (candidate == null || candidate.getId() == null || candidate.getId() <= afterId) {
                    throw new IllegalStateException("legacy job scan returned an invalid or unordered id");
                }
                afterId = candidate.getId();
                try {
                    long id = afterId;
                    transactions.execute(status -> {
                        migrateLocked(id);
                        return null;
                    });
                } catch (RuntimeException ex) {
                    // Keep the original row unfinished for inspection/retry; never log its payload.
                    log.error("Legacy memory job migration failed jobId={} errorType={}", afterId,
                            ex.getClass().getSimpleName());
                }
            }
            if (page.size() < PAGE_SIZE) {
                return;
            }
        }
    }

    private void migrateLocked(long id) {
        AiMemoryJobEntity job = legacyJobs.selectOne(new QueryWrapper<AiMemoryJobEntity>()
                .eq("id", id).last("FOR UPDATE"));
        if (job == null || !UNFINISHED.contains(job.getStatus()) || !SUPPORTED.contains(job.getJobType())) {
            return;
        }
        JsonNode payload = readPayload(job.getPayloadJson());
        long userId = requiredId(payload, "userId");
        String eventType;
        String sourceType;
        long sourceId;
        if (AiMemoryJobTypes.JOB_AI_JUDGED_SUBMISSION_ANALYSIS.equals(job.getJobType())) {
            eventType = ChannelEventTypes.SUBMISSION_JUDGED;
            sourceType = "submission";
            sourceId = requiredId(payload, "submissionId");
            requiredId(payload, "problemId");
            String status = payload.path("status").asString("");
            SubmissionStatus submissionStatus = SubmissionStatus.valueOf(status);
            if (submissionStatus == SubmissionStatus.QUEUED || submissionStatus == SubmissionStatus.RUNNING) {
                throw new IllegalArgumentException("legacy submission status is not terminal");
            }
            String judgedAt = payload.path("judgedAt").asString("");
            if (!judgedAt.isBlank()) {
                Instant.parse(judgedAt);
            }
        } else {
            eventType = ChannelEventTypes.MEMORY_CANDIDATE_PROPOSED;
            sourceType = "AI_MEMORY_CANDIDATE";
            sourceId = requiredId(payload, "candidateId");
        }
        HarnessEvent event = HarnessEvent.builder(eventType, "legacy-memory-job:" + id,
                        "ai-service:legacy-migration")
                .occurredAt(clock.instant())
                .subject(EventSubject.user(String.valueOf(userId)))
                .sensitivity("USER_PRIVATE_SAFE")
                .payload(payload)
                .build();
        HarnessEventChannel.Delivery delivered = channel.deliver(event, sourceType, String.valueOf(sourceId));
        if (!delivered.duplicate() && !delivered.consumers().contains(job.getJobType())) {
            throw new IllegalStateException("legacy job subscriber is not registered");
        }
        if (delivered.duplicate()) {
            Long existing = channelJobs.selectCount(new QueryWrapper<AiAsyncJobEntity>()
                    .eq("idempotency_key", delivered.row().getId() + ":" + job.getJobType()));
            if (existing == null || existing == 0) {
                throw new IllegalStateException("duplicate legacy event has no corresponding subscriber job");
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int changed = legacyJobs.update(null, new UpdateWrapper<AiMemoryJobEntity>()
                .eq("id", id).in("status", UNFINISHED)
                .set("status", AiMemoryJobStatus.FAILED_FINAL.name())
                .set("last_error_summary", "Migrated to unified channel; event=" + delivered.row().getId())
                .set("lease_owner", null).set("lease_expires_at", null)
                .set("completed_at", now).set("updated_at", now));
        if (changed != 1) {
            throw new IllegalStateException("legacy job state changed during migration");
        }
    }

    private JsonNode readPayload(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("legacy job payload is empty");
        }
        JsonNode payload = mapper.readTree(json);
        if (payload == null || !payload.isObject() || ENVELOPE_FIELDS.stream().anyMatch(payload::has)) {
            throw new IllegalArgumentException("legacy job payload is incompatible with the channel envelope");
        }
        return payload;
    }

    private long requiredId(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("legacy job payload is missing " + name);
        }
        long id;
        try {
            id = value.isIntegralNumber() ? value.longValue() : Long.parseLong(value.asString(""));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("legacy job payload has invalid " + name, ex);
        }
        if (id <= 0) {
            throw new IllegalArgumentException("legacy job payload has invalid " + name);
        }
        return id;
    }
}
