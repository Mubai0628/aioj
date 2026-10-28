package com.aioj.next.ai.agent.channel;

import com.aioj.next.agent.harness.channel.api.EventPublisher;
import com.aioj.next.agent.harness.channel.api.EventSubject;
import com.aioj.next.agent.harness.channel.api.EventSubscriber;
import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.agent.harness.channel.api.PublishedEvent;
import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobHandler;
import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiDomainEventEntity;
import com.aioj.next.ai.persistence.mapper.AiDomainEventMapper;
import com.aioj.next.common.api.TraceIds;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The unified data channel (plan T1.7, §3.4, A3.3). The event log is {@code ai_domain_events}
 * (V47, envelope columns from V72); each subscriber's queue is {@code ai_async_jobs}. Publishing
 * runs in the caller's transaction (or its own): the event is stored once per idempotency key
 * and every subscriber of its type gets one job keyed {@code eventId:consumer}, so a duplicate
 * publish delivers nothing new and one failing subscriber never blocks another. The agent job
 * worker delivers with its lease, backoff and attempt limit; a job out of attempts stays
 * FAILED, which is the dead letter.
 *
 * <p>Subscribers are the {@link AgentAsyncJobHandler}s that list the event type, under their
 * job type, plus any Harness {@link EventSubscriber}, under its name. They are looked up per
 * publish, so a subscriber may itself publish (the curator emits DIGEST_READY).</p>
 */
@Service
public class HarnessEventChannel implements EventPublisher {
    static final String DEFAULT_SENSITIVITY = "USER_PRIVATE_SAFE";

    private final AiDomainEventMapper eventMapper;
    private final AgentAsyncJobService jobService;
    private final ObjectMapper objectMapper;
    private final TransactionOperations transactions;
    private final AiProperties properties;
    private final ObjectProvider<AgentAsyncJobHandler> handlers;
    private final ObjectProvider<EventSubscriber> subscribers;

    public HarnessEventChannel(AiDomainEventMapper eventMapper, AgentAsyncJobService jobService,
                               ObjectMapper objectMapper, TransactionOperations transactions, AiProperties properties,
                               ObjectProvider<AgentAsyncJobHandler> handlers, ObjectProvider<EventSubscriber> subscribers) {
        this.eventMapper = eventMapper;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.properties = properties;
        this.handlers = handlers;
        this.subscribers = subscribers;
    }

    /** What one publish stored and delivered; {@code row} is the stored (or earlier) event. */
    public record Delivery(AiDomainEventEntity row, boolean duplicate, List<String> consumers) {
    }

    @Override
    public PublishedEvent publish(HarnessEvent event) {
        Delivery delivery = deliver(event, null, null);
        return new PublishedEvent(String.valueOf(delivery.row().getId()), delivery.duplicate(), delivery.consumers());
    }

    /**
     * Publishes with an explicit source (for example {@code submission} / its id); without one
     * the source is the most specific identifier of the subject.
     */
    public Delivery deliver(HarnessEvent event, String sourceType, String sourceId) {
        return transactions.execute(status -> deliverInTransaction(event, sourceType, sourceId));
    }

    /** The subscribers of a type, in a stable order: job handlers first, then Harness subscribers. */
    public List<String> consumersOf(String eventType) {
        Set<String> consumers = new LinkedHashSet<>();
        handlers.orderedStream()
                .filter(handler -> handler.eventTypes().contains(eventType))
                .forEach(handler -> consumers.add(handler.jobType()));
        subscribers.orderedStream()
                .filter(subscriber -> subscriber.eventTypes().contains(eventType))
                .forEach(subscriber -> consumers.add(subscriber.name()));
        return List.copyOf(consumers);
    }

    /** Every consumer that subscribes to at least one event type. */
    public Set<String> consumers() {
        Set<String> consumers = new java.util.TreeSet<>();
        handlers.orderedStream()
                .filter(handler -> !handler.eventTypes().isEmpty())
                .forEach(handler -> consumers.add(handler.jobType()));
        subscribers.orderedStream()
                .filter(subscriber -> !subscriber.eventTypes().isEmpty())
                .forEach(subscriber -> consumers.add(subscriber.name()));
        return Set.copyOf(consumers);
    }

    private Delivery deliverInTransaction(HarnessEvent event, String sourceType, String sourceId) {
        AiDomainEventEntity existing = findByIdempotencyKey(event.idempotencyKey());
        if (existing != null) {
            return new Delivery(existing, true, List.of());
        }
        AiDomainEventEntity row = toRow(event, sourceType, sourceId);
        try {
            eventMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            AiDomainEventEntity raced = findByIdempotencyKey(event.idempotencyKey());
            if (raced != null) {
                return new Delivery(raced, true, List.of());
            }
            throw ex;
        }
        List<String> consumers = consumersOf(event.type());
        String jobPayload = jobPayload(event, row);
        for (String consumer : consumers) {
            jobService.enqueue(consumer, row.getId() + ":" + consumer, jobPayload,
                    properties.getAgentJobs().getMaxAttempts());
        }
        return new Delivery(row, false, consumers);
    }

    private AiDomainEventEntity findByIdempotencyKey(String key) {
        return eventMapper.selectOne(new QueryWrapper<AiDomainEventEntity>()
                .eq("idempotency_key", key.strip())
                .last("LIMIT 1"));
    }

    private AiDomainEventEntity toRow(HarnessEvent event, String sourceType, String sourceId) {
        EventSubject subject = event.subject();
        if (subject.userId() == null || subject.userId().isBlank()) {
            throw new IllegalArgumentException("channel events need the user they are about");
        }
        AiDomainEventEntity row = new AiDomainEventEntity();
        row.setEventType(event.type());
        row.setSchemaVersion(event.schemaVersion());
        row.setProducer(cap(event.producer(), 64));
        row.setUserId(Long.parseLong(subject.userId()));
        if (sourceType != null && !sourceType.isBlank()) {
            row.setSourceType(sourceType.strip());
            row.setSourceId(sourceId == null || sourceId.isBlank() ? null : sourceId.strip());
        } else if (subject.turnId() != null) {
            row.setSourceType("turn");
            row.setSourceId(subject.turnId());
        } else if (subject.workspaceId() != null) {
            row.setSourceType("workspace");
            row.setSourceId(subject.workspaceId());
        } else if (subject.conversationId() != null) {
            row.setSourceType("conversation");
            row.setSourceId(subject.conversationId());
        } else {
            row.setSourceType("user");
            row.setSourceId(subject.userId());
        }
        row.setIdempotencyKey(event.idempotencyKey().strip());
        row.setPayloadJson(event.payload() == null ? "{}" : objectMapper.writeValueAsString(event.payload()));
        row.setSensitivity(event.sensitivity() == null || event.sensitivity().isBlank()
                ? DEFAULT_SENSITIVITY : event.sensitivity());
        row.setTraceId(cap(event.traceId() != null ? event.traceId() : MDC.get(TraceIds.MDC_KEY), 64));
        LocalDateTime occurredAt = LocalDateTime.ofInstant(event.occurredAt(), ZoneId.systemDefault());
        row.setCreatedAt(occurredAt);
        row.setUpdatedAt(occurredAt);
        return row;
    }

    /**
     * The event payload, plus which event it came from and its trace (the worker logs, and any
     * event the subscriber publishes, under it), as each subscriber's job payload.
     */
    private String jobPayload(HarnessEvent event, AiDomainEventEntity row) {
        ObjectNode payload = event.payload() instanceof ObjectNode object ? object.deepCopy() : objectMapper.createObjectNode();
        payload.put("eventId", String.valueOf(row.getId()));
        payload.put("eventType", event.type());
        if (row.getTraceId() != null && !row.getTraceId().isBlank() && !"n/a".equals(row.getTraceId())) {
            payload.put("traceId", row.getTraceId());
        }
        return objectMapper.writeValueAsString(payload);
    }

    private static String cap(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** Reads a job payload back as JSON, for subscribers that want the envelope fields. */
    public JsonNode readPayload(String payloadJson) {
        return objectMapper.readTree(payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson);
    }
}
