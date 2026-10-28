package com.aioj.next.ai.agent.channel;

import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobHandler;
import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobPermanentFailure;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.memory.AiMemoryJobHandler;
import com.aioj.next.ai.domain.memory.AiMemoryJobPermanentFailure;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;

import java.util.Set;

/**
 * Runs one of the former ai_memory_jobs handlers as a channel subscriber (plan T1.7, A3.4): the
 * job type stays the same, the job lives in ai_async_jobs, and the handler gets the job's payload
 * exactly as before. Like the memory worker it replaces, it only runs while the memory jobs are
 * switched on ({@code AI_MEMORY_JOBS_ENABLED}); until then its jobs wait PENDING.
 */
abstract class LegacyMemoryJobSubscriber implements AgentAsyncJobHandler {
    private final AiMemoryJobHandler delegate;
    private final String eventType;
    private final AiProperties properties;

    LegacyMemoryJobSubscriber(AiMemoryJobHandler delegate, String eventType, AiProperties properties) {
        this.delegate = delegate;
        this.eventType = eventType;
        this.properties = properties;
    }

    @Override
    public String jobType() {
        return delegate.jobType();
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(eventType);
    }

    @Override
    public boolean enabled() {
        return properties.getMemoryJobs().isEnabled();
    }

    @Override
    public void handle(AiAsyncJobEntity job) {
        AiMemoryJobEntity memoryJob = new AiMemoryJobEntity();
        memoryJob.setId(job.getId());
        memoryJob.setJobType(job.getJobType());
        memoryJob.setIdempotencyKey(job.getIdempotencyKey());
        memoryJob.setPayloadJson(job.getPayloadJson());
        memoryJob.setAttemptCount(job.getAttemptCount());
        memoryJob.setMaxAttempts(job.getMaxAttempts());
        try {
            delegate.handle(memoryJob);
        } catch (AiMemoryJobPermanentFailure ex) {
            throw new AgentAsyncJobPermanentFailure(ex.getMessage());
        }
    }
}
