package com.aioj.next.agent.harness.channel.api;

/**
 * Publishes an event (plan A3.3): in the caller's local transaction the event is stored once
 * per idempotency key and every subscriber of its type gets its own job, so a subscriber that
 * keeps failing never holds up the others. Delivery is at least once; subscribers de-duplicate.
 */
@FunctionalInterface
public interface EventPublisher {
    PublishedEvent publish(HarnessEvent event);
}
