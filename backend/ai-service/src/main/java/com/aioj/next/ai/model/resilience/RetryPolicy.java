package com.aioj.next.ai.model.resilience;

import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * The only retry in the model call path (plan A1.5, appendix G.4 item 2): rate
 * limits and unavailable endpoints are retried with backoff, but only while the
 * call has produced nothing yet. Timeouts are not retried, because the provider
 * may still be generating and billing the first attempt.
 */
public final class RetryPolicy {
    private final int maxRetries;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration maxRetryAfter;
    private final DoubleSupplier jitter;

    /** @param jitter returns a value in [0, 1); the backoff is scaled into [50%, 100%] of its step */
    public RetryPolicy(int maxRetries, Duration initialBackoff, Duration maxBackoff, Duration maxRetryAfter,
                       DoubleSupplier jitter) {
        this.maxRetries = Math.max(0, maxRetries);
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.maxRetryAfter = maxRetryAfter;
        this.jitter = jitter;
    }

    /**
     * The wait before the next attempt, or empty when the failure is final.
     *
     * @param attemptsMade attempts already made, the failed one included
     */
    public Optional<Duration> delayBeforeRetry(ModelException failure, int attemptsMade, Instant deadline) {
        return delayBeforeRetry(failure, attemptsMade, deadline, null);
    }

    /** The same, with the endpoint's own retry count; {@code null} keeps the configured one. */
    public Optional<Duration> delayBeforeRetry(ModelException failure, int attemptsMade, Instant deadline,
                                               Integer endpointMaxRetries) {
        int limit = endpointMaxRetries == null ? maxRetries : Math.max(0, endpointMaxRetries);
        if (attemptsMade > limit || failure.outputStarted() || !retryable(failure.kind())) {
            return Optional.empty();
        }
        Duration delay;
        if (failure.retryAfter() != null) {
            if (failure.retryAfter().compareTo(maxRetryAfter) > 0) {
                return Optional.empty();
            }
            delay = failure.retryAfter();
        } else {
            long step = initialBackoff.toMillis() << Math.min(20, attemptsMade - 1);
            long capped = Math.min(step, maxBackoff.toMillis());
            delay = Duration.ofMillis((long) (capped * (0.5 + 0.5 * jitter.getAsDouble())));
        }
        if (deadline != null && !Instant.now().plus(delay).isBefore(deadline)) {
            return Optional.empty();
        }
        return Optional.of(delay);
    }

    private static boolean retryable(ModelErrorKind kind) {
        return kind == ModelErrorKind.RATE_LIMITED || kind == ModelErrorKind.UNAVAILABLE;
    }
}
