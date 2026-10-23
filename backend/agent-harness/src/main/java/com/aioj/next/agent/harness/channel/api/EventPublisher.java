package com.aioj.next.agent.harness.channel.api;

@FunctionalInterface
public interface EventPublisher {
    void publish(HarnessEvent event);
}
