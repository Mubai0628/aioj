package com.aioj.next.ai.model.api;

import java.time.Instant;

/**
 * What a {@link ModelCallListener} learns about one call. It never carries prompts,
 * reasoning, outputs or code (plan A1.6).
 *
 * @param provider  {@code null} when the call failed before an endpoint was resolved
 * @param attempts  HTTP attempts made, retries included; 0 when the call failed before sending
 * @param errorKind {@code null} for a successful call
 */
public record ModelCallRecord(ModelOperation operation, String scope, String feature, ProviderId provider,
                              String model, ModelUsage usage, long latencyMillis, int attempts,
                              ModelErrorKind errorKind, String correlationId, Long subjectUserId,
                              Instant startedAt) {

    public boolean succeeded() {
        return errorKind == null;
    }
}
