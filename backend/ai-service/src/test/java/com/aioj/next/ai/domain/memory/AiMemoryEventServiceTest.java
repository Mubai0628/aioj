package com.aioj.next.ai.domain.memory;

import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.ai.agent.channel.ChannelEventTypes;
import com.aioj.next.ai.agent.channel.HarnessEventChannel;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiDomainEventEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.mapper.AiDomainEventMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryJobMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMemoryEventServiceTest {
    @Mock
    private AiDomainEventMapper eventMapper;
    @Mock
    private AiMemoryJobMapper jobMapper;

    private AiMemoryEventService eventService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AiMemoryEventPayloadSanitizer sanitizer = new AiMemoryEventPayloadSanitizer();
        ObjectMapper objectMapper = new ObjectMapper();
        AiMemoryJobService jobService = new AiMemoryJobService(jobMapper, sanitizer, objectMapper, new AiProperties(), clock);
        eventService = new AiMemoryEventService(eventMapper, jobService, sanitizer, objectMapper, clock);
    }

    @Test
    void unifiedModeRecordsTheEventOnTheChannelInsteadOfTheMemoryQueue() {
        HarnessEventChannel channel = mock(HarnessEventChannel.class);
        AiDomainEventEntity stored = new AiDomainEventEntity();
        stored.setId(88L);
        when(channel.deliver(any(HarnessEvent.class), eq("SUBMISSION"), eq("501")))
                .thenReturn(new HarnessEventChannel.Delivery(stored, false, List.of("AI_JUDGED_SUBMISSION_ANALYSIS")));
        AiMemoryEventPayloadSanitizer sanitizer = new AiMemoryEventPayloadSanitizer();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AiMemoryEventService unified = new AiMemoryEventService(eventMapper,
                new AiMemoryJobService(jobMapper, sanitizer, new ObjectMapper(), new AiProperties(), clock),
                sanitizer, new ObjectMapper(), clock, channel, new AiProperties());

        AiMemoryEventService.RecordedEvent recorded = unified.recordEvent(
                AiMemoryJobTypes.EVENT_SUBMISSION_JUDGED_SAFE, 7L, "SUBMISSION", "501", "submission-judged:501",
                Map.of("submissionId", 501, "sourceCode", "int main(){return 0;}"),
                AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE,
                List.of(new AiMemoryEventService.EventJobSpec(
                        AiMemoryJobTypes.JOB_AI_JUDGED_SUBMISSION_ANALYSIS, "job-1", Map.of(), null, null)));

        ArgumentCaptor<HarnessEvent> event = ArgumentCaptor.forClass(HarnessEvent.class);
        verify(channel).deliver(event.capture(), eq("SUBMISSION"), eq("501"));
        assertThat(event.getValue().type()).isEqualTo(ChannelEventTypes.SUBMISSION_JUDGED);
        assertThat(event.getValue().idempotencyKey()).isEqualTo("submission-judged:501");
        assertThat(event.getValue().producer()).isEqualTo("ai-service:submission");
        assertThat(event.getValue().subject().userId()).isEqualTo("7");
        assertThat(event.getValue().payload().toString()).contains("501").doesNotContain("int main");
        assertThat(recorded.event()).isSameAs(stored);
        assertThat(recorded.jobs()).isEmpty();
        verify(eventMapper, never()).insert(any(AiDomainEventEntity.class));
        verify(jobMapper, never()).insert(any(AiMemoryJobEntity.class));
    }

    @Test
    void recordEventWritesSanitizedEventAndIdempotentJobs() {
        when(eventMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(jobMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            AiDomainEventEntity event = invocation.getArgument(0);
            event.setId(88L);
            return 1;
        }).when(eventMapper).insert(any(AiDomainEventEntity.class));
        doAnswer(invocation -> {
            AiMemoryJobEntity job = invocation.getArgument(0);
            job.setId(99L);
            return 1;
        }).when(jobMapper).insert(any(AiMemoryJobEntity.class));

        AiMemoryEventService.RecordedEvent recorded = eventService.recordEvent(
                "AI_CHAT_TURN_COMPLETED",
                7L,
                "conversation",
                "42",
                "event-1",
                Map.of("sourceCode", "int main(){return 0;}", "note", "stdout:\nhidden-output-content\n\nsafe"),
                AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE,
                List.of(new AiMemoryEventService.EventJobSpec(
                        "MEMORY_EXTRACT",
                        "job-1",
                        Map.of("stderr", "hidden", "note", "token=plain-secret"),
                        3,
                        LocalDateTime.of(2026, 1, 1, 0, 5)
                ))
        );

        ArgumentCaptor<AiDomainEventEntity> eventCaptor = ArgumentCaptor.forClass(AiDomainEventEntity.class);
        ArgumentCaptor<AiMemoryJobEntity> jobCaptor = ArgumentCaptor.forClass(AiMemoryJobEntity.class);
        verify(eventMapper).insert(eventCaptor.capture());
        verify(jobMapper).insert(jobCaptor.capture());

        AiDomainEventEntity event = eventCaptor.getValue();
        AiMemoryJobEntity job = jobCaptor.getValue();
        assertThat(recorded.event()).isSameAs(event);
        assertThat(recorded.jobs()).containsExactly(job);
        assertThat(event.getPayloadJson())
                .contains(AiMemoryEventPayloadSanitizer.OMITTED)
                .contains("[raw output omitted]")
                .doesNotContain("int main")
                .doesNotContain("hidden-output-content");
        assertThat(job.getEventId()).isEqualTo(88L);
        assertThat(job.getPayloadJson())
                .contains(AiMemoryEventPayloadSanitizer.OMITTED)
                .doesNotContain("hidden")
                .doesNotContain("plain-secret");
    }

    @Test
    void recordEventReturnsExistingEventForDuplicateIdempotencyKey() {
        AiDomainEventEntity existing = new AiDomainEventEntity();
        existing.setId(88L);
        existing.setEventType("AI_CHAT_TURN_COMPLETED");
        existing.setIdempotencyKey("event-1");
        when(eventMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        AiDomainEventEntity result = eventService.recordEvent(
                "AI_CHAT_TURN_COMPLETED",
                7L,
                "conversation",
                "42",
                "event-1",
                Map.of()
        );

        assertThat(result).isSameAs(existing);
        verify(eventMapper, never()).insert(any(AiDomainEventEntity.class));
    }
}
