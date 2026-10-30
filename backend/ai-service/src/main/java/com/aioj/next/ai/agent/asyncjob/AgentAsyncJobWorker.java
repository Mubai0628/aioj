package com.aioj.next.ai.agent.asyncjob;

import com.aioj.next.agent.harness.channel.api.EventSubject;
import com.aioj.next.agent.harness.channel.api.EventSubscriber;
import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.config.HarnessDbQuota;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.common.api.TraceIds;
import com.aioj.next.common.error.SafeErrors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.management.ManagementFactory;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polling worker for ai_async_jobs (design doc §6.3): claims due jobs under a lease,
 * dispatches to the registered handler, and records completion or backoff. Unknown job
 * types and a handler's {@link AgentAsyncJobPermanentFailure} park as FAILED immediately,
 * so what no retry can fix is observable instead of retried.
 *
 * <p>Since T1.7 this is also the channel's delivery: each subscriber's jobs carry its name as
 * the job type, a Harness {@link EventSubscriber} gets the event rebuilt from the job, the jobs
 * of a disabled handler wait unclaimed, and failures are counted per consumer
 * ({@code aioj.ai.channel.failures}).</p>
 *
 * <p>Plan 15.4: each job runs on a job thread under a time limit
 * ({@code aioj.ai.agent-jobs.job-timeout-seconds}). Its lease is extended past that limit as it
 * starts; one that runs over is interrupted and retried with backoff, and the worker moves on.</p>
 */
@Service
public class AgentAsyncJobWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentAsyncJobWorker.class);
    /** The lease outlasts the time limit by this much, so a job that just made it is not reclaimed meanwhile. */
    static final long LEASE_MARGIN_SECONDS = 30;
    /**
     * One thread runs the current job; the other lets the next one start while a job that ran over and
     * ignores its interrupt finishes, at the latest at its model call's own limit.
     */
    private static final int JOB_THREADS = 2;

    private final AgentAsyncJobService jobService;
    private final AiProperties properties;
    private final Map<String, AgentAsyncJobHandler> handlers;
    private final MeterRegistry meters;
    private final ObjectMapper objectMapper;
    private final String workerId;
    private final ThreadPoolExecutor jobThreads;

    /** Tests and callers without channel subscribers or metrics. */
    public AgentAsyncJobWorker(
            AgentAsyncJobService jobService,
            AiProperties properties,
            List<AgentAsyncJobHandler> handlers
    ) {
        this(jobService, properties, handlers, List.of(), null, null);
    }

    @Autowired
    public AgentAsyncJobWorker(
            AgentAsyncJobService jobService,
            AiProperties properties,
            List<AgentAsyncJobHandler> handlers,
            ObjectProvider<EventSubscriber> subscribers,
            ObjectMapper objectMapper,
            ObjectProvider<MeterRegistry> meters
    ) {
        this(jobService, properties, handlers, subscribers.orderedStream().toList(), objectMapper,
                meters.getIfAvailable());
    }

    AgentAsyncJobWorker(
            AgentAsyncJobService jobService,
            AiProperties properties,
            List<AgentAsyncJobHandler> handlers,
            List<EventSubscriber> subscribers,
            ObjectMapper objectMapper,
            MeterRegistry meters
    ) {
        this.jobService = jobService;
        this.properties = properties == null ? new AiProperties() : properties;
        Map<String, AgentAsyncJobHandler> byType = new LinkedHashMap<>();
        for (AgentAsyncJobHandler handler : handlers == null ? List.<AgentAsyncJobHandler>of() : handlers) {
            byType.putIfAbsent(handler.jobType(), handler);
        }
        for (EventSubscriber subscriber : subscribers == null ? List.<EventSubscriber>of() : subscribers) {
            byType.putIfAbsent(subscriber.name(), new SubscriberJobs(subscriber, objectMapper));
        }
        this.handlers = Map.copyOf(byType);
        this.meters = meters;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.workerId = "agent-async-" + ManagementFactory.getRuntimeMXBean().getName();
        AtomicInteger threadNumber = new AtomicInteger();
        // The Harness prefix keeps the jobs on the Harness database quota (T1.6), as before on the poll thread.
        this.jobThreads = new ThreadPoolExecutor(JOB_THREADS, JOB_THREADS, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable,
                            HarnessDbQuota.THREAD_PREFIX + "agent-job-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.jobThreads.allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    public void stopJobThreads() {
        jobThreads.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${aioj.ai.agent-jobs.poll-interval-ms:5000}")
    public void pollDueJobs() {
        if (!properties.getAgentJobs().isEnabled()) {
            return;
        }
        List<String> skipped = handlers.values().stream()
                .filter(handler -> !handler.enabled())
                .map(AgentAsyncJobHandler::jobType)
                .toList();
        // T1.6: the jobs are Harness work and share the Harness database quota.
        HarnessDbQuota.runAsHarness(() -> {
            List<AiAsyncJobEntity> jobs = jobService.claimDueJobs(
                    properties.getAgentJobs().getBatchSize(),
                    properties.getAgentJobs().getLeaseSeconds(),
                    workerId,
                    skipped
            );
            for (AiAsyncJobEntity job : jobs) {
                if (Thread.currentThread().isInterrupted()) {
                    // Shutting down: the rest keep their leases until they run out, then are claimed again.
                    return;
                }
                process(job);
            }
        });
    }

    /** A channel job runs under the trace of the event that queued it (T1.7). */
    private void process(AiAsyncJobEntity job) {
        String trace = traceOf(job);
        if (trace == null) {
            processUntraced(job);
            return;
        }
        String previous = MDC.get(TraceIds.MDC_KEY);
        MDC.put(TraceIds.MDC_KEY, trace);
        try {
            processUntraced(job);
        } finally {
            if (previous == null) {
                MDC.remove(TraceIds.MDC_KEY);
            } else {
                MDC.put(TraceIds.MDC_KEY, previous);
            }
        }
    }

    private String traceOf(AiAsyncJobEntity job) {
        String payload = job.getPayloadJson();
        if (payload == null || !payload.contains("\"traceId\"")) {
            return null;
        }
        try {
            JsonNode trace = objectMapper.readTree(payload).get("traceId");
            return trace != null && trace.isString() && !trace.asString().isBlank() ? trace.asString() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void processUntraced(AiAsyncJobEntity job) {
        AgentAsyncJobHandler handler = handlers.get(job.getJobType());
        if (handler == null) {
            log.warn("no handler for agent async job type: jobId={} type={}", job.getId(), job.getJobType());
            countFailure(job);
            jobService.failFinal(job, "No handler registered for job type: " + job.getJobType());
            return;
        }
        long limitSeconds = properties.getAgentJobs().getJobTimeoutSeconds();
        if (!jobService.renewLease(job, workerId, limitSeconds + LEASE_MARGIN_SECONDS)) {
            log.warn("agent async job is no longer leased to this worker, skipped: jobId={} type={}", job.getId(),
                    job.getJobType());
            return;
        }
        try {
            runWithinLimit(handler, job, limitSeconds);
            jobService.complete(job);
        } catch (AgentAsyncJobPermanentFailure ex) {
            log.warn("agent async job failed permanently: jobId={} type={} error={}", job.getId(), job.getJobType(),
                    SafeErrors.describe(ex));
            countFailure(job);
            jobService.failFinal(job, ex.getMessage());
        } catch (TimeoutException ex) {
            log.warn("agent async job ran over its limit and was interrupted: jobId={} type={} limitSeconds={}",
                    job.getId(), job.getJobType(), limitSeconds);
            countFailure(job);
            jobService.fail(job, ex, properties.getAgentJobs().getBackoffBaseSeconds());
        } catch (InterruptedException ex) {
            // Shutting down: the job keeps its lease until it runs out, then is claimed again.
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("agent async job failed: jobId={} type={} error={}", job.getId(), job.getJobType(), SafeErrors.describe(ex));
            countFailure(job);
            jobService.fail(job, ex, properties.getAgentJobs().getBackoffBaseSeconds());
        }
    }

    /** Runs one job on a job thread, under the trace it was given, and waits at most its limit. */
    private void runWithinLimit(AgentAsyncJobHandler handler, AiAsyncJobEntity job, long limitSeconds)
            throws Exception {
        Map<String, String> context = MDC.getCopyOfContextMap();
        Future<?> running;
        try {
            running = jobThreads.submit(() -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    handler.handle(job);
                    return null;
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException busy) {
            throw new IllegalStateException("every agent job thread is still held by a job that ran over", busy);
        }
        try {
            running.get(limitSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException late) {
            running.cancel(true);
            throw new TimeoutException("agent async job ran over its " + limitSeconds + " s limit");
        } catch (InterruptedException interrupted) {
            running.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failed;
        }
    }

    private void countFailure(AiAsyncJobEntity job) {
        if (meters != null) {
            Counter.builder("aioj.ai.channel.failures")
                    .description("Failed attempts per channel consumer")
                    .tag("consumer", String.valueOf(job.getJobType()))
                    .register(meters)
                    .increment();
        }
    }

    /** A Harness subscriber as a job handler: the job payload carries the event's payload, id and type. */
    private record SubscriberJobs(EventSubscriber subscriber, ObjectMapper objectMapper) implements AgentAsyncJobHandler {
        @Override
        public String jobType() {
            return subscriber.name();
        }

        @Override
        public Set<String> eventTypes() {
            return subscriber.eventTypes();
        }

        @Override
        public void handle(AiAsyncJobEntity job) {
            JsonNode payload = objectMapper.readTree(job.getPayloadJson() == null ? "{}" : job.getPayloadJson());
            String eventId = payload.path("eventId").asString("");
            String eventType = payload.path("eventType").asString("");
            if (eventId.isBlank() || eventType.isBlank()) {
                throw new AgentAsyncJobPermanentFailure("channel job carries no event id or type");
            }
            subscriber.handle(new HarnessEvent(eventId, eventType, 1,
                    job.getCreatedAt() == null ? null : job.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                    "channel", EventSubject.NONE, null, eventId, payload, null));
        }
    }
}
