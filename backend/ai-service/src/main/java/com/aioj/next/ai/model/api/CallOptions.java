package com.aioj.next.ai.model.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-call options that are not part of what the model sees.
 *
 * @param deadline      the call fails with {@link ModelErrorKind#TIMEOUT} at this instant,
 *                      including bulkhead waits and retries; {@code null} means only the
 *                      configured timeouts apply
 * @param cancellation  cancelling it aborts the call and closes the connection
 * @param feature       what the call is for, for observation (for example {@code tutor.turn})
 * @param correlationId the turn, run or job the call belongs to
 * @param subjectUserId the user the call is made for, when there is one
 */
public record CallOptions(Instant deadline, CancellationSignal cancellation, String feature,
                          String correlationId, Long subjectUserId) {

    public CallOptions {
        feature = feature == null || feature.isBlank() ? "unspecified" : feature;
    }

    public static CallOptions of(String feature) {
        return new CallOptions(null, null, feature, null, null);
    }

    public CallOptions withDeadline(Instant value) {
        return new CallOptions(value, cancellation, feature, correlationId, subjectUserId);
    }

    public CallOptions withTimeout(Duration timeout) {
        return withDeadline(Instant.now().plus(timeout));
    }

    public CallOptions withCancellation(CancellationSignal value) {
        return new CallOptions(deadline, value, feature, correlationId, subjectUserId);
    }

    public CallOptions withCorrelation(String value, Long userId) {
        return new CallOptions(deadline, cancellation, feature, value, userId);
    }
}
