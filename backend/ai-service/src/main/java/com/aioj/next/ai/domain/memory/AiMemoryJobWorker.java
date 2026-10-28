package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiMemoryJobWorker {
    private static final Logger log = LoggerFactory.getLogger(AiMemoryJobWorker.class);

    private final AiMemoryJobService jobService;
    private final AiProperties properties;
    private final Map<String, AiMemoryJobHandler> handlers;
    private final String workerId;

    public AiMemoryJobWorker(
            AiMemoryJobService jobService,
            AiProperties properties,
            List<AiMemoryJobHandler> handlers
    ) {
        this.jobService = jobService;
        this.properties = properties == null ? new AiProperties() : properties;
        this.handlers = handlers == null ? Map.of() : handlers.stream()
                .collect(Collectors.toUnmodifiableMap(AiMemoryJobHandler::jobType, Function.identity(), (left, right) -> left));
        this.workerId = "ai-memory-" + ManagementFactory.getRuntimeMXBean().getName();
    }

    @Scheduled(fixedDelayString = "${aioj.ai.memory-jobs.poll-interval-ms:5000}")
    public void pollDueJobs() {
        // T1.7: in UNIFIED mode the channel's subscribers run these job types; the two never both run.
        if (!properties.getMemoryJobs().isEnabled() || properties.getChannel().isUnified()) {
            return;
        }
        List<AiMemoryJobEntity> jobs = jobService.claimDueJobs(
                properties.getMemoryJobs().getBatchSize(),
                Duration.ofSeconds(Math.max(1, properties.getMemoryJobs().getLeaseSeconds())),
                workerId
        );
        for (AiMemoryJobEntity job : jobs) {
            process(job);
        }
    }

    /**
     * UNIFIED mode leaves this queue alone, so what it still holds is only reported, once, at
     * startup; switching {@code aioj.ai.channel.mode} to LEGACY drains it.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportLegacyBacklog() {
        if (!properties.getChannel().isUnified()) {
            return;
        }
        try {
            long unfinished = jobService.countUnfinished();
            if (unfinished > 0) {
                log.warn("AI channel mode is UNIFIED: {} unfinished ai_memory_jobs are left unprocessed "
                        + "(aioj.ai.channel.mode=LEGACY drains them)", unfinished);
            }
        } catch (RuntimeException ex) {
            log.warn("AI memory job backlog check failed: {}", ex.toString());
        }
    }

    private void process(AiMemoryJobEntity job) {
        AiMemoryJobHandler handler = handlers.get(job.getJobType());
        if (handler == null) {
            jobService.failFinal(job, "No handler registered for AI memory job type: " + safeJobType(job));
            return;
        }
        try {
            handler.handle(job);
            jobService.complete(job);
        } catch (AiMemoryJobPermanentFailure ex) {
            log.warn("AI memory job failed permanently: jobId={}, jobType={}", job.getId(), safeJobType(job));
            jobService.failFinal(job, ex);
        } catch (Exception ex) {
            log.warn("AI memory job failed: jobId={}, jobType={}", job.getId(), safeJobType(job));
            jobService.failRetryableOrFinal(job, ex);
        }
    }

    private String safeJobType(AiMemoryJobEntity job) {
        String value = job == null ? null : job.getJobType();
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }
}
