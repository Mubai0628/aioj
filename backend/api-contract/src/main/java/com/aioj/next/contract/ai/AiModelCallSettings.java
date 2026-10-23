package com.aioj.next.contract.ai;

/**
 * A scope's own call settings (plan A6.2); a null value means the server default from
 * aioj.ai.model-client. Saving replaces all of them, so a field left out goes back to
 * the default.
 *
 * @param firstByteTimeoutMs        only streaming calls wait for a first byte
 * @param thinkingFallbackEnabled   whether a call may drop thinking by itself; false fails instead
 * @param streamingEnabled          stored now; the Agent loop streams from plan T1.12
 */
public record AiModelCallSettings(
        Integer connectTimeoutMs,
        Integer firstByteTimeoutMs,
        Integer totalTimeoutMs,
        Integer maxConcurrency,
        Integer maxRetries,
        Boolean thinkingFallbackEnabled,
        Boolean streamingEnabled
) {
}
