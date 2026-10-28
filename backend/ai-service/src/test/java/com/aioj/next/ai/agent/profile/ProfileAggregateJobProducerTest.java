package com.aioj.next.ai.agent.profile;

import com.aioj.next.agent.harness.channel.api.EventPublisher;
import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.ai.agent.channel.ChannelEventTypes;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProfileAggregateJobProducerTest {

    private final EventPublisher publisher = mock(EventPublisher.class);
    private final ProfileAggregateJobProducer producer =
            new ProfileAggregateJobProducer(new ObjectMapper(), publisher);

    @Test
    void enqueuesWithUserHourBucketedIdempotencyKey() {
        producer.enqueueForUser(7L);

        ArgumentCaptor<HarnessEvent> event = ArgumentCaptor.forClass(HarnessEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(ChannelEventTypes.PROFILE_SIGNAL_OBSERVED);
        assertThat(event.getValue().idempotencyKey()).startsWith("profile-signals:7:").endsWith(":00:00Z");
        assertThat(event.getValue().payload().get("userId").asLong()).isEqualTo(7L);
    }

    @Test
    void enqueueFailureNeverBreaksTheCaller() {
        when(publisher.publish(any(HarnessEvent.class)))
                .thenThrow(new RuntimeException("job db down"));

        assertThatCode(() -> producer.enqueueForUser(7L)).doesNotThrowAnyException();
    }

    @Test
    void unifiedModePublishesOneSignalEventPerUserHour() {
        producer.enqueueForUser(7L);

        ArgumentCaptor<HarnessEvent> event = ArgumentCaptor.forClass(HarnessEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(ChannelEventTypes.PROFILE_SIGNAL_OBSERVED);
        assertThat(event.getValue().idempotencyKey()).startsWith("profile-signals:7:").endsWith(":00:00Z");
        assertThat(event.getValue().subject().userId()).isEqualTo("7");
        assertThat(event.getValue().payload().get("userId").asLong()).isEqualTo(7L);
    }

    @Test
    void publishFailureNeverBreaksTheCaller() {
        when(publisher.publish(any(HarnessEvent.class))).thenThrow(new RuntimeException("channel down"));

        assertThatCode(() -> producer.enqueueForUser(7L)).doesNotThrowAnyException();
    }

    @Test
    void nullUserIdSkipsEnqueue() {
        producer.enqueueForUser(null);

        verifyNoInteractions(publisher);
    }
}
