package com.aioj.next.ai.model.api;

import java.time.Duration;

/**
 * A scope's own call settings from ai_model_configs (plan A6.2). A null value means
 * the aioj.ai.model-client default.
 *
 * @param firstByteTimeout only a streaming call waits for its first byte
 * @param maxConcurrency   the scope's bulkhead; a change counts for permits taken afterwards
 * @param thinkingFallback whether a call may drop thinking by itself (after THINKING_UNAVAILABLE,
 *                         after an empty answer, or in a workflow's recovery); false fails instead
 * @param streaming        stored now; the Agent loop streams from plan T1.12
 */
public record EndpointTuning(
        Duration connectTimeout,
        Duration firstByteTimeout,
        Duration totalTimeout,
        Integer maxConcurrency,
        Integer maxRetries,
        Boolean thinkingFallback,
        Boolean streaming
) {
    public static final EndpointTuning DEFAULTS = new EndpointTuning(null, null, null, null, null, null, null);

    public boolean thinkingFallbackAllowed() {
        return thinkingFallback == null || thinkingFallback;
    }
}
