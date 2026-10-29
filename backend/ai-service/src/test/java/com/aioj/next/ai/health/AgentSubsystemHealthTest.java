package com.aioj.next.ai.health;

import com.aioj.next.ai.agent.channel.ChannelMetrics;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.common.http.InternalRestClientFactory;
import com.aioj.next.common.resilience.CircuitBreaker;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AgentSubsystemHealthTest {
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    @Test
    void modelHealthOnlyReadsPassiveTextStatesAndNeverCallsAProvider() {
        ModelClient client = mock(ModelClient.class);
        var indicator = new ModelProvidersHealthIndicator(client);
        when(client.textCircuitStates()).thenReturn(Map.of("TEXT_GENERATION", "CLOSED"));
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
        when(client.textCircuitStates()).thenReturn(Map.of("TEXT_GENERATION", "OPEN"));
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        when(client.textCircuitStates()).thenThrow(new IllegalStateException("not for the response"));
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        verify(client, times(3)).textCircuitStates();
        verifyNoMoreInteractions(client);
    }

    @Test
    void problemHealthReadsSharedCircuitWithoutMakingARequest() {
        var clients = mock(InternalRestClientFactory.class);
        var indicator = new ProblemServiceHealthIndicator(clients);
        when(clients.snapshots()).thenReturn(Map.of());
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
        when(clients.snapshots()).thenReturn(Map.of("problem-service", snapshot(CircuitBreaker.State.HALF_OPEN)));
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        verify(clients, times(2)).snapshots();
        verifyNoMoreInteractions(clients);
    }

    @Test
    void executorProbeFailureRecoveryCircuitAndStalenessStayInTheAgentHealthItem() {
        var available = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        var now = new AtomicReference<>(NOW);
        var state = new AtomicReference<>(CircuitBreaker.State.CLOSED);
        var indicator = new AgentExecutorHealthIndicator(() -> {
            calls.incrementAndGet();
            if (!available.get()) { throw new IllegalStateException("offline"); }
        }, () -> Map.of("agent-executor", snapshot(state.get())), new AgentHealthProperties(), now::get);
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(calls.get()).isZero();
        indicator.probe();
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(calls.get()).isEqualTo(1);
        available.set(false);
        indicator.probe();
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        available.set(true);
        indicator.probe();
        state.set(CircuitBreaker.State.OPEN);
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        state.set(CircuitBreaker.State.CLOSED);
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
        now.set(NOW.plusSeconds(31));
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(calls.get()).as("health reads do not run probes").isEqualTo(3);
    }

    @Test
    void channelThresholdsAndSnapshotFailuresAreConfigurableAndReadOnly() {
        var metrics = mock(ChannelMetrics.class);
        var properties = new AgentHealthProperties();
        properties.setBacklogThreshold(2);
        properties.setDeadLetterThreshold(1);
        var indicator = new AgentChannelHealthIndicator(metrics, properties, () -> NOW);
        when(metrics.healthSnapshot()).thenReturn(new ChannelMetrics.HealthSnapshot(2, 1, NOW, true));
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
        when(metrics.healthSnapshot()).thenReturn(new ChannelMetrics.HealthSnapshot(3, 1, NOW, true));
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        when(metrics.healthSnapshot()).thenReturn(new ChannelMetrics.HealthSnapshot(0, 2, NOW, true));
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        when(metrics.healthSnapshot()).thenReturn(new ChannelMetrics.HealthSnapshot(0, 0, NOW.minusSeconds(91), true));
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        when(metrics.healthSnapshot()).thenReturn(new ChannelMetrics.HealthSnapshot(0, 0, NOW, false));
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
        verify(metrics, times(5)).healthSnapshot();
        verifyNoMoreInteractions(metrics);
        assertThatThrownBy(() -> properties.setBacklogThreshold(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    private InternalRestClientFactory.TargetSnapshot snapshot(CircuitBreaker.State state) {
        return new InternalRestClientFactory.TargetSnapshot(state, 0, 8);
    }
}
