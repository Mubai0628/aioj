package com.aioj.next.agent.harness.channel.api;

import java.util.List;

/**
 * What publishing did: the stored event's id, whether the idempotency key had been published
 * before (then nothing new was stored or delivered), and the subscribers that got a job.
 */
public record PublishedEvent(String eventId, boolean duplicate, List<String> consumers) {
    public PublishedEvent {
        consumers = consumers == null ? List.of() : List.copyOf(consumers);
    }
}
