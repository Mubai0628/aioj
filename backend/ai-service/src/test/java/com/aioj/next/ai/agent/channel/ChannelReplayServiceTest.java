package com.aioj.next.ai.agent.channel;

import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService;
import com.aioj.next.ai.domain.OperationAuditWriter;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.ai.AiChannelReplayRequest;
import com.aioj.next.contract.ai.AiChannelReplayResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChannelReplayServiceTest {
    private final AgentAsyncJobService jobService = mock(AgentAsyncJobService.class);
    private final HarnessEventChannel channel = mock(HarnessEventChannel.class);
    private final OperationAuditWriter auditWriter = mock(OperationAuditWriter.class);
    private final ChannelReplayService service = new ChannelReplayService(jobService, channel, auditWriter);

    @Test
    void replaysOneEventsDeadLetterAndAuditsIt() {
        when(channel.consumers()).thenReturn(Set.of("AI_JUDGED_SUBMISSION_ANALYSIS"));
        when(jobService.requeueFailed("AI_JUDGED_SUBMISSION_ANALYSIS", "41", 100)).thenReturn(1);

        AiChannelReplayResponse response = service.replay(99L,
                new AiChannelReplayRequest(" AI_JUDGED_SUBMISSION_ANALYSIS ", 41L, null));

        assertThat(response).isEqualTo(new AiChannelReplayResponse("AI_JUDGED_SUBMISSION_ANALYSIS", 41L, 1));
        verify(auditWriter).record(eq("AI_CHANNEL_REPLAY"), eq("AI_CHANNEL_EVENT"), eq(41L), eq("SUCCEEDED"),
                argThat((Map<String, Object> summary) -> summary.get("requeued").equals(1)
                        && summary.get("consumer").equals("AI_JUDGED_SUBMISSION_ANALYSIS")),
                eq(99L), isNull(), isNull(), isNull());
    }

    @Test
    void aConsumerWideReplayIsCapped() {
        when(channel.consumers()).thenReturn(Set.of("TURN_CURATE"));
        when(jobService.requeueFailed("TURN_CURATE", null, 500)).thenReturn(7);

        AiChannelReplayResponse response = service.replay(99L, new AiChannelReplayRequest("TURN_CURATE", null, 10_000));

        assertThat(response.requeued()).isEqualTo(7);
        verify(jobService).requeueFailed("TURN_CURATE", null, 500);
    }

    @Test
    void onlySubscribersCanBeReplayed() {
        when(channel.consumers()).thenReturn(Set.of("TURN_CURATE"));

        assertThatThrownBy(() -> service.replay(99L, new AiChannelReplayRequest("AI_PROBLEM_DRAFT", null, null)))
                .isInstanceOf(DomainException.class);
        verify(jobService, org.mockito.Mockito.never()).requeueFailed(anyString(), any(), anyInt());
        verifyNoInteractions(auditWriter);
    }
}
