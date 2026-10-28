package com.aioj.next.ai.domain.memory;

import com.aioj.next.agent.harness.channel.api.EventSubject;
import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.ai.agent.channel.HarnessEventChannel;
import com.aioj.next.ai.persistence.entity.AiDomainEventEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;

/** Sanitizes memory-domain events before publishing them to the unified channel. */
@Service
public class AiMemoryEventService {
    public static final String SENSITIVITY_USER_PRIVATE_SAFE = "USER_PRIVATE_SAFE";

    private final AiMemoryEventPayloadSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final HarnessEventChannel channel;
    private final Clock clock;

    @Autowired
    public AiMemoryEventService(AiMemoryEventPayloadSanitizer sanitizer, ObjectMapper objectMapper,
                                HarnessEventChannel channel) {
        this(sanitizer, objectMapper, channel, Clock.systemDefaultZone());
    }

    AiMemoryEventService(AiMemoryEventPayloadSanitizer sanitizer, ObjectMapper objectMapper,
                         HarnessEventChannel channel, Clock clock) {
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.channel = channel;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Transactional
    public AiDomainEventEntity recordEvent(String eventType, Long userId, String sourceType, String sourceId,
                                           String idempotencyKey, Object payload, String sensitivity) {
        requireText(eventType, "eventType");
        requireText(sourceType, "sourceType");
        requireText(idempotencyKey, "idempotencyKey");
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        HarnessEvent event = HarnessEvent.builder(eventType.strip(), idempotencyKey.strip(),
                        "ai-service:" + sourceType.strip().toLowerCase(Locale.ROOT))
                .occurredAt(clock.instant())
                .subject(EventSubject.user(String.valueOf(userId)))
                .sensitivity(normalizeSensitivity(sensitivity))
                .payload(objectMapper.readTree(toJson(payload)))
                .build();
        return channel.deliver(event, sourceType.strip(), sourceId).row();
    }

    @Transactional
    public AiDomainEventEntity recordEvent(String eventType, Long userId, String sourceType, String sourceId,
                                           String idempotencyKey, Object payload) {
        return recordEvent(eventType, userId, sourceType, sourceId, idempotencyKey, payload,
                SENSITIVITY_USER_PRIVATE_SAFE);
    }

    private String toJson(Object payload) {
        Object sanitized = sanitizer.sanitizePayload(payload == null ? Map.of() : payload);
        try {
            return objectMapper.writeValueAsString(sanitized);
        } catch (JacksonException ex) {
            return "{\"error\":\"payload_serialization_failed\"}";
        }
    }

    private String normalizeSensitivity(String sensitivity) {
        if (sensitivity == null || sensitivity.isBlank()) {
            return SENSITIVITY_USER_PRIVATE_SAFE;
        }
        return sensitivity.length() > 48 ? sensitivity.substring(0, 48) : sensitivity;
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
