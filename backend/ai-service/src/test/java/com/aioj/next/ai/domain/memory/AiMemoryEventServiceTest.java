package com.aioj.next.ai.domain.memory;

import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.ai.agent.channel.ChannelEventTypes;
import com.aioj.next.ai.agent.channel.HarnessEventChannel;
import com.aioj.next.ai.persistence.entity.AiDomainEventEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMemoryEventServiceTest {
    private final HarnessEventChannel channel = mock(HarnessEventChannel.class);
    private final AiMemoryEventService service = new AiMemoryEventService(new AiMemoryEventPayloadSanitizer(),
            new ObjectMapper(), channel, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void recordsTheEventOnTheUnifiedChannel() {
        AiDomainEventEntity stored = new AiDomainEventEntity();
        stored.setId(88L);
        when(channel.deliver(any(HarnessEvent.class), eq("SUBMISSION"), eq("501")))
                .thenReturn(new HarnessEventChannel.Delivery(stored, false, List.of("AI_JUDGED_SUBMISSION_ANALYSIS")));

        AiDomainEventEntity recorded = service.recordEvent(ChannelEventTypes.SUBMISSION_JUDGED, 7L,
                "SUBMISSION", "501", "submission-judged:501",
                Map.of("submissionId", 501, "sourceCode", "int main(){return 0;}"),
                AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE);

        ArgumentCaptor<HarnessEvent> event = ArgumentCaptor.forClass(HarnessEvent.class);
        verify(channel).deliver(event.capture(), eq("SUBMISSION"), eq("501"));
        assertThat(event.getValue().type()).isEqualTo(ChannelEventTypes.SUBMISSION_JUDGED);
        assertThat(event.getValue().idempotencyKey()).isEqualTo("submission-judged:501");
        assertThat(event.getValue().producer()).isEqualTo("ai-service:submission");
        assertThat(event.getValue().subject().userId()).isEqualTo("7");
        assertThat(event.getValue().payload().toString()).contains("501").doesNotContain("int main");
        assertThat(recorded).isSameAs(stored);
    }

    @Test
    void recordEventSanitizesCodeRawOutputAndSecretsBeforeChannelDelivery() {
        AiDomainEventEntity stored = new AiDomainEventEntity();
        stored.setId(88L);
        when(channel.deliver(any(HarnessEvent.class), eq("conversation"), eq("42")))
                .thenReturn(new HarnessEventChannel.Delivery(stored, false, List.of()));

        AiDomainEventEntity recorded = service.recordEvent("AI_CHAT_TURN_COMPLETED", 7L, "conversation", "42",
                "event-1", Map.of("sourceCode", "int main(){return 0;}",
                        "note", "stdout:\nhidden-output-content\n\nsafe", "stderr", "hidden",
                        "secondNote", "token=plain-secret"), AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE);

        ArgumentCaptor<HarnessEvent> event = ArgumentCaptor.forClass(HarnessEvent.class);
        verify(channel).deliver(event.capture(), eq("conversation"), eq("42"));
        assertThat(recorded).isSameAs(stored);
        assertThat(event.getValue().payload().toString())
                .contains(AiMemoryEventPayloadSanitizer.OMITTED)
                .contains("[raw output omitted]")
                .doesNotContain("int main", "hidden-output-content", "hidden", "plain-secret");
    }

    @Test
    void recordEventReturnsExistingEventForDuplicateIdempotencyKey() {
        AiDomainEventEntity existing = new AiDomainEventEntity();
        existing.setId(88L);
        existing.setEventType("AI_CHAT_TURN_COMPLETED");
        existing.setIdempotencyKey("event-1");
        when(channel.deliver(any(HarnessEvent.class), eq("conversation"), eq("42")))
                .thenReturn(new HarnessEventChannel.Delivery(existing, true, List.of()));

        AiDomainEventEntity result = service.recordEvent("AI_CHAT_TURN_COMPLETED", 7L,
                "conversation", "42", "event-1", Map.of());

        assertThat(result).isSameAs(existing);
        ArgumentCaptor<HarnessEvent> event = ArgumentCaptor.forClass(HarnessEvent.class);
        verify(channel).deliver(event.capture(), eq("conversation"), eq("42"));
        assertThat(event.getValue().idempotencyKey()).isEqualTo("event-1");
    }
}
