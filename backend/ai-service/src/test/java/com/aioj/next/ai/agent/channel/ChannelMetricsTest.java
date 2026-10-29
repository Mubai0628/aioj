package com.aioj.next.ai.agent.channel;

import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelMetricsTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 12, 0);

    private final AgentAsyncJobService jobService = mock(AgentAsyncJobService.class);
    private final HarnessEventChannel channel = mock(HarnessEventChannel.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void backlogOldestWaitAndDeadLettersPerConsumer() {
        when(channel.consumers()).thenReturn(Set.of("TURN_CURATE", "EMBED_DIGEST", "PROFILE_AGGREGATE"));
        when(jobService.countsByTypeAndStatus()).thenReturn(List.of(
                row("job_type", "TURN_CURATE", "status", "PENDING", "job_count", 3L, "oldest_created_at", NOW.minusSeconds(90)),
                row("job_type", "TURN_CURATE", "status", "RUNNING", "job_count", 1L),
                row("job_type", "TURN_CURATE", "status", "FAILED", "job_count", 2L),
                // Driver label case is not ours to choose.
                row("JOB_TYPE", "EMBED_DIGEST", "STATUS", "FAILED", "JOB_COUNT", 4)));
        ChannelMetrics metrics = metrics();

        metrics.refresh(NOW);

        assertThat(gauge("aioj.ai.channel.backlog", "TURN_CURATE")).isEqualTo(4);
        assertThat(gauge("aioj.ai.channel.oldest.wait.seconds", "TURN_CURATE")).isEqualTo(90);
        assertThat(gauge("aioj.ai.channel.dead.letters", "TURN_CURATE")).isEqualTo(2);
        assertThat(gauge("aioj.ai.channel.dead.letters", "EMBED_DIGEST")).isEqualTo(4);
        assertThat(gauge("aioj.ai.channel.backlog", "EMBED_DIGEST")).isZero();
        // A consumer with nothing queued still reports, so an alert has a series to watch.
        assertThat(gauge("aioj.ai.channel.backlog", "PROFILE_AGGREGATE")).isZero();
        assertThat(metrics.healthSnapshot().available()).isTrue();
        assertThat(metrics.healthSnapshot().backlog()).isEqualTo(4);
        assertThat(metrics.healthSnapshot().deadLetters()).isEqualTo(6);
    }

    @Test
    void aDrainedQueueReadsZeroOnTheNextRefresh() {
        when(channel.consumers()).thenReturn(Set.of("TURN_CURATE"));
        when(jobService.countsByTypeAndStatus()).thenReturn(
                List.of(row("job_type", "TURN_CURATE", "status", "PENDING", "job_count", 3L, "oldest_created_at", NOW)),
                List.of());
        ChannelMetrics metrics = metrics();

        metrics.refresh(NOW);
        assertThat(gauge("aioj.ai.channel.backlog", "TURN_CURATE")).isEqualTo(3);
        assertThat(metrics.healthSnapshot().backlog()).isEqualTo(3);
        metrics.refresh(NOW.plusMinutes(1));
        assertThat(gauge("aioj.ai.channel.backlog", "TURN_CURATE")).isZero();
        assertThat(gauge("aioj.ai.channel.oldest.wait.seconds", "TURN_CURATE")).isZero();
        assertThat(metrics.healthSnapshot().backlog()).isZero();
    }

    @Test
    void aFailedRefreshKeepsTheLastReading() {
        when(channel.consumers()).thenReturn(Set.of("TURN_CURATE"));
        when(jobService.countsByTypeAndStatus())
                .thenReturn(List.of(row("job_type", "TURN_CURATE", "status", "FAILED", "job_count", 1L)))
                .thenThrow(new IllegalStateException("db down"));
        ChannelMetrics metrics = metrics();

        metrics.refresh();
        metrics.refresh();

        assertThat(gauge("aioj.ai.channel.dead.letters", "TURN_CURATE")).isEqualTo(1);
        assertThat(metrics.healthSnapshot().available()).isFalse();
        assertThat(metrics.healthSnapshot().deadLetters()).isEqualTo(1);
    }

    private ChannelMetrics metrics() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new ChannelMetrics(jobService, channel, provider);
    }

    private double gauge(String name, String consumer) {
        return registry.get(name).tag("consumer", consumer).gauge().value();
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }
}
