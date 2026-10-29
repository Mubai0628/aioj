package com.aioj.next.ai.health;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.common.http.InternalRestClientFactory;
import com.aioj.next.common.resilience.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class AgentExecutorHealthIndicator implements HealthIndicator {
    private final Runnable probe;
    private final Supplier<Map<String, InternalRestClientFactory.TargetSnapshot>> circuits;
    private final AgentHealthProperties properties;
    private final Supplier<Instant> now;
    private volatile Probe latest;
    private record Probe(Instant at, boolean available) { }

    @Autowired
    public AgentExecutorHealthIndicator(InternalRestClientFactory clients, AiProperties ai,
                                         AgentHealthProperties properties) {
        this(() -> clients.create("agent-executor", ai.getAgentExecutorUri(), "executor-health",
                        Duration.ofSeconds(1), properties.getExecutorProbeTimeout())
                .get().uri("/actuator/health/readiness").retrieve().toBodilessEntity(),
                clients::snapshots, properties, Instant::now);
    }
    AgentExecutorHealthIndicator(Runnable probe,
            Supplier<Map<String, InternalRestClientFactory.TargetSnapshot>> circuits,
            AgentHealthProperties properties, Supplier<Instant> now) {
        this.probe = probe;
        this.circuits = circuits;
        this.properties = properties;
        this.now = now;
    }

    @Scheduled(fixedDelayString = "${aioj.ai.health.executor-probe-interval:PT10S}")
    public void probe() {
        boolean available;
        try { probe.run(); available = true; }
        catch (RuntimeException ex) { available = false; }
        latest = new Probe(now.get(), available);
    }

    @Override
    public Health health() {
        Probe reading = latest;
        boolean fresh = reading != null && !now.get().isAfter(reading.at().plus(properties.getExecutorMaxAge()));
        try {
            var circuit = circuits.get().get("agent-executor");
            var state = circuit == null ? CircuitBreaker.State.CLOSED : circuit.circuit();
            boolean degraded = !fresh || !reading.available() || state != CircuitBreaker.State.CLOSED;
            return Health.status(degraded ? "DEGRADED" : "UP")
                    .withDetail("probeFresh", fresh).withDetail("probeAvailable", reading != null && reading.available())
                    .withDetail("circuit", state.name()).build();
        } catch (RuntimeException ex) {
            return Health.status("DEGRADED").withDetail("reason", "snapshot_unavailable").build();
        }
    }
}
