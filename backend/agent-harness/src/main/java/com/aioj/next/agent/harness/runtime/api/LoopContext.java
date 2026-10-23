package com.aioj.next.agent.harness.runtime.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** What interceptors and completion policies may read about the running loop. */
public interface LoopContext {
    String runId();

    String scenario();

    /** The current model step, starting at 1. */
    int step();

    int toolCallCount();

    /** Names of the tools that have returned SUCCESS in this run. */
    Set<String> succeededTools();

    /** How often {@link LoopInterceptor#beforeFinal} has sent an answer back; the loop allows one. */
    int finalRetries();

    Map<String, Object> attributes();

    default <T> Optional<T> attribute(String key, Class<T> type) {
        Object value = attributes().get(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    /** Adds a warning to the run result (duplicates are kept once). */
    void warn(String warning);

    /** The run's warnings so far, in the order they first occurred. */
    List<String> warnings();
}
