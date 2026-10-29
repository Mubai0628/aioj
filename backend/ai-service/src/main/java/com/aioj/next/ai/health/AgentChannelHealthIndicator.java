package com.aioj.next.ai.health;

import com.aioj.next.ai.agent.channel.ChannelMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.function.Supplier;

@Component
public class AgentChannelHealthIndicator implements HealthIndicator {
    private final ChannelMetrics metrics;
    private final AgentHealthProperties properties;
    private final Supplier<Instant> now;

    @Autowired
    public AgentChannelHealthIndicator(ChannelMetrics metrics, AgentHealthProperties properties) {
        this(metrics, properties, Instant::now);
    }
    AgentChannelHealthIndicator(ChannelMetrics metrics, AgentHealthProperties properties, Supplier<Instant> now) {
        this.metrics = metrics;
        this.properties = properties;
        this.now = now;
    }
    @Override
    public Health health() {
        var snapshot = metrics.healthSnapshot();
        boolean fresh = snapshot != null && snapshot.available() && snapshot.sampledAt() != null
                && !now.get().isAfter(snapshot.sampledAt().plus(properties.getChannelMaxAge()));
        boolean degraded = !fresh || snapshot.backlog() > properties.getBacklogThreshold()
                || snapshot.deadLetters() > properties.getDeadLetterThreshold();
        return Health.status(degraded ? "DEGRADED" : "UP")
                .withDetail("fresh", fresh)
                .withDetail("backlog", snapshot == null ? 0 : snapshot.backlog())
                .withDetail("deadLetters", snapshot == null ? 0 : snapshot.deadLetters())
                .withDetail("backlogThreshold", properties.getBacklogThreshold())
                .withDetail("deadLetterThreshold", properties.getDeadLetterThreshold()).build();
    }
}
