package com.aioj.next.ai.health;

import com.aioj.next.common.http.InternalRestClientFactory;
import com.aioj.next.common.resilience.CircuitBreaker;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class ProblemServiceHealthIndicator implements HealthIndicator {
    private final InternalRestClientFactory clients;
    public ProblemServiceHealthIndicator(InternalRestClientFactory clients) { this.clients = clients; }

    @Override
    public Health health() {
        try {
            var snapshot = clients.snapshots().get("problem-service");
            var state = snapshot == null ? CircuitBreaker.State.CLOSED : snapshot.circuit();
            return Health.status(state == CircuitBreaker.State.CLOSED ? "UP" : "DEGRADED")
                    .withDetail("mode", "PASSIVE_CIRCUIT").withDetail("circuit", state.name()).build();
        } catch (RuntimeException ex) {
            return Health.status("DEGRADED").withDetail("reason", "snapshot_unavailable").build();
        }
    }
}
