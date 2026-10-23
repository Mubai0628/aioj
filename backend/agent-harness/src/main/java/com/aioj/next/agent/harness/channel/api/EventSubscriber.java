package com.aioj.next.agent.harness.channel.api;

import java.util.Set;

/**
 * Handles events of some types. Each subscriber gets its own job per event,
 * so one failing subscriber never holds up the others.
 */
public interface EventSubscriber {
    String name();

    Set<String> eventTypes();

    void handle(HarnessEvent event);
}
