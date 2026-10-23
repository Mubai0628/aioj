package com.aioj.next.ai.model.resilience;

import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ProviderId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {
    private final RetryPolicy policy = new RetryPolicy(2, Duration.ofMillis(500), Duration.ofSeconds(2),
            Duration.ofSeconds(10), () -> 0.999);

    @ParameterizedTest
    @EnumSource(value = ModelErrorKind.class, names = {"RATE_LIMITED", "UNAVAILABLE"}, mode = EnumSource.Mode.EXCLUDE)
    void onlyRateLimitsAndUnavailableEndpointsAreRetried(ModelErrorKind kind) {
        assertThat(policy.delayBeforeRetry(failure(kind, null, false), 1, null)).isEmpty();
    }

    @Test
    void backoffGrowsPerAttemptUpToItsCapAndStopsAfterTheLastRetry() {
        ModelException busy = failure(ModelErrorKind.UNAVAILABLE, null, false);

        assertThat(policy.delayBeforeRetry(busy, 1, null)).hasValueSatisfying(
                delay -> assertThat(delay.toMillis()).isBetween(490L, 500L));
        assertThat(policy.delayBeforeRetry(busy, 2, null)).hasValueSatisfying(
                delay -> assertThat(delay.toMillis()).isBetween(990L, 1000L));
        assertThat(policy.delayBeforeRetry(busy, 3, null)).as("two retries made").isEmpty();
    }

    @Test
    void retryAfterIsUsedWhenItIsWithinTheCap() {
        assertThat(policy.delayBeforeRetry(failure(ModelErrorKind.RATE_LIMITED, Duration.ofSeconds(3), false), 1, null))
                .contains(Duration.ofSeconds(3));
        assertThat(policy.delayBeforeRetry(failure(ModelErrorKind.RATE_LIMITED, Duration.ofSeconds(11), false), 1, null))
                .isEmpty();
    }

    @Test
    void nothingIsRetriedOnceOutputStartedOrWhenTheDeadlineWouldPass() {
        assertThat(policy.delayBeforeRetry(failure(ModelErrorKind.UNAVAILABLE, null, true), 1, null)).isEmpty();
        assertThat(policy.delayBeforeRetry(failure(ModelErrorKind.UNAVAILABLE, null, false), 1,
                Instant.now().plusMillis(100))).isEmpty();
    }

    private static ModelException failure(ModelErrorKind kind, Duration retryAfter, boolean outputStarted) {
        return new ModelException(kind, ProviderId.DEEPSEEK, "deepseek-v4-pro", "failure", 503, null, retryAfter,
                outputStarted);
    }
}
