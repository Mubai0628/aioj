package com.aioj.next.ai.agent.asyncjob;

import com.aioj.next.agent.harness.channel.api.EventSubject;
import com.aioj.next.agent.harness.channel.api.EventSubscriber;
import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.config.HarnessDbQuota;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.common.api.TraceIds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 */
@Service
public class AgentAsyncJobWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentAsyncJobWorker.class);

    private final AgentAsyncJobService jobService;
    private final AiProperties properties;
    private final Map<String, AgentAsyncJobHandler> handlers;
    private final MeterRegistry meters;
    private final ObjectMapper objectMapper;
    private final String workerId;

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
        try {
            handler.handle(job);
            jobService.complete(job);
        } catch (AgentAsyncJobPermanentFailure ex) {
            log.warn("agent async job failed permanently: jobId={} type={} error={}", job.getId(), job.getJobType(),
                    ex.getMessage());
            countFailure(job);
            jobService.failFinal(job, ex.getMessage());
        } catch (Exception ex) {
            log.warn("agent async job failed: jobId={} type={} error={}", job.getId(), job.getJobType(), ex.toString());
            countFailure(job);
            jobService.fail(job, ex, properties.getAgentJobs().getBackoffBaseSeconds());
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
