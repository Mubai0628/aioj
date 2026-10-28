package com.aioj.next.ai.agent.asyncjob;

import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;

import java.util.Set;

/**
 * Handler contract for ai_async_jobs types; implementations are Spring beans auto-wired into the
 * worker. Since T1.7 a handler is also a channel subscriber: it gets a job of its type for every
 * event of the {@link #eventTypes()} it lists.
 */
public interface AgentAsyncJobHandler {
    String jobType();

    void handle(AiAsyncJobEntity job) throws Exception;

    /** The channel event types this handler subscribes to; none means jobs are enqueued directly. */
    default Set<String> eventTypes() {
        return Set.of();
    }

    /**
     * Whether the worker takes this type's jobs right now. A disabled type's jobs wait PENDING
     * (for example the paid memory analyses while the memory jobs are switched off).
     */
    default boolean enabled() {
        return true;
    }
}
