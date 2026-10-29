package com.aioj.next.ai.health;

import com.aioj.next.ai.model.api.ModelClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ModelProvidersHealthIndicator implements HealthIndicator {
    private final ModelClient models;
    public ModelProvidersHealthIndicator(ModelClient models) { this.models = models; }

    @Override
    public Health health() {
        try {
            Map<String, String> states = models.textCircuitStates();
            boolean degraded = states == null || states.values().stream().anyMatch(state -> !"CLOSED".equals(state));
            return Health.status(degraded ? "DEGRADED" : "UP")
                    .withDetail("mode", "PASSIVE_CIRCUITS")
                    .withDetail("textScopes", states == null ? Map.of() : states).build();
        } catch (RuntimeException ex) {
            return Health.status("DEGRADED").withDetail("reason", "snapshot_unavailable").build();
        }
    }
}
