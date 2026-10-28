package com.aioj.next.ai.agent.digest;

import com.aioj.next.agent.harness.channel.api.EventPublisher;
import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService;
import com.aioj.next.ai.agent.channel.ChannelEventTypes;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnDigestServiceTest {

    private final StubDigestFactory stubDigestFactory = new StubDigestFactory(new ObjectMapper());
    private final AiTurnDigestMapper digestMapper = mock(AiTurnDigestMapper.class);
    private final AgentAsyncJobService jobService = mock(AgentAsyncJobService.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final TurnDigestService service = new TurnDigestService(
            stubDigestFactory, digestMapper, jobService, new ObjectMapper(), legacy(), publisher);
    private final TurnDigestService unified = new TurnDigestService(
            stubDigestFactory, digestMapper, jobService, new ObjectMapper(), new AiProperties(), publisher);

    private static AiProperties legacy() {
        AiProperties properties = new AiProperties();
        properties.getChannel().setMode(AiProperties.Channel.Mode.LEGACY);
        return properties;
    }

    @Test
    void recordsStubAndEnqueuesCurateJob() {
        TurnDigestInput input = new TurnDigestInput(
                "t-1", "c-1", 7L, "100", "200", "讲一下二分答案", "好的……",
                "deepseek-v4-pro", null, List.of(), null, null, null, "CHAT");

        service.recordCompletedTurn(input);

        ArgumentCaptor<AiTurnDigestEntity> entityCaptor = ArgumentCaptor.forClass(AiTurnDigestEntity.class);
        verify(digestMapper).insert(entityCaptor.capture());
        AiTurnDigestEntity entity = entityCaptor.getValue();
        assertThat(entity.getTurnId()).isEqualTo("t-1");
        assertThat(entity.getConversationId()).isEqualTo("c-1");
        assertThat(entity.getUserId()).isEqualTo(7L);
        assertThat(entity.getDigestVersion()).isEqualTo(1);
        assertThat(entity.getStatus()).isEqualTo(StubDigestFactory.STATUS_STUB);
        assertThat(entity.getSourceHash()).hasSize(64);
        assertThat(entity.getStructuredDigest()).contains("\"schemaVersion\":3");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(jobService).enqueue(eq(TurnDigestService.JOB_TYPE_TURN_CURATE), keyCaptor.capture(),
                anyString(), anyInt());
        assertThat(keyCaptor.getValue()).isEqualTo("TURN_CURATE:t-1");
    }

    @Test
    void unifiedModePublishesTurnCompletedInsteadOfEnqueueing() {
        unified.recordCompletedTurn(new TurnDigestInput(
                "t-3", "c-1", 7L, "100", "200", "讲一下二分答案", "好的……",
                "deepseek-v4-pro", null, List.of(), null, null, null, "CHAT"));

        verify(digestMapper).insert(any(AiTurnDigestEntity.class));
        ArgumentCaptor<HarnessEvent> event = ArgumentCaptor.forClass(HarnessEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(ChannelEventTypes.TURN_COMPLETED);
        assertThat(event.getValue().idempotencyKey()).isEqualTo("turn-completed:t-3");
        assertThat(event.getValue().subject().userId()).isEqualTo("7");
        assertThat(event.getValue().subject().conversationId()).isEqualTo("c-1");
        assertThat(event.getValue().subject().turnId()).isEqualTo("t-3");
        assertThat(event.getValue().payload().get("turnId").asString()).isEqualTo("t-3");
        verify(jobService, never()).enqueue(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void publishFailureIsSwallowedAndNeverBreaksChat() {
        when(publisher.publish(any(HarnessEvent.class))).thenThrow(new RuntimeException("channel down"));

        assertThatCode(() -> unified.recordCompletedTurn(new TurnDigestInput(
                "t-4", "c-1", 7L, "1", "2", "hi", "hello",
                null, null, List.of(), null, null, null, "CHAT")))
                .doesNotThrowAnyException();
    }

    @Test
    void digestFailureIsSwallowedAndNeverBreaksChat() {
        when(digestMapper.insert(any(AiTurnDigestEntity.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.recordCompletedTurn(new TurnDigestInput(
                "t-2", "c-1", 7L, "1", "2", "hi", "hello",
                null, null, List.of(), null, null, null, "CHAT")))
                .doesNotThrowAnyException();
        verify(jobService, never()).enqueue(anyString(), anyString(), anyString(), anyInt());
    }
}
