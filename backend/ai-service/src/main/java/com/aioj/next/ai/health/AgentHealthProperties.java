package com.aioj.next.ai.health;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "aioj.ai.health")
public class AgentHealthProperties {
    private long backlogThreshold = 100;
    private long deadLetterThreshold = 100;
    private Duration channelMaxAge = Duration.ofSeconds(90);
    private Duration executorProbeInterval = Duration.ofSeconds(10);
    private Duration executorProbeTimeout = Duration.ofSeconds(2);
    private Duration executorMaxAge = Duration.ofSeconds(30);

    public long getBacklogThreshold() { return backlogThreshold; }
    public void setBacklogThreshold(long value) { backlogThreshold = nonnegative(value); }
    public long getDeadLetterThreshold() { return deadLetterThreshold; }
    public void setDeadLetterThreshold(long value) { deadLetterThreshold = nonnegative(value); }
    public Duration getChannelMaxAge() { return channelMaxAge; }
    public void setChannelMaxAge(Duration value) { channelMaxAge = positive(value); }
    public Duration getExecutorProbeInterval() { return executorProbeInterval; }
    public void setExecutorProbeInterval(Duration value) { executorProbeInterval = positive(value); }
    public Duration getExecutorProbeTimeout() { return executorProbeTimeout; }
    public void setExecutorProbeTimeout(Duration value) { executorProbeTimeout = positive(value); }
    public Duration getExecutorMaxAge() { return executorMaxAge; }
    public void setExecutorMaxAge(Duration value) { executorMaxAge = positive(value); }
    private static long nonnegative(long value) {
        if (value < 0) { throw new IllegalArgumentException("Agent health threshold must not be negative"); }
        return value;
    }
    private static Duration positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("Agent health duration must be positive");
        }
        return value;
    }
}
