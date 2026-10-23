package com.aioj.next.ai.model.resilience;

import com.aioj.next.ai.model.resilience.CircuitBreaker.Outcome;
import com.aioj.next.ai.model.resilience.CircuitBreaker.State;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {
    private final AtomicLong now = new AtomicLong();

    @Test
    void opensWhenTheFailureRateOfAFullWindowReachesTheThreshold() {
        CircuitBreaker breaker = new CircuitBreaker(4, 0.5, 10, Duration.ofSeconds(30), now::get);

        record(breaker, Outcome.FAILURE, Outcome.SUCCESS, Outcome.FAILURE);
        assertThat(breaker.state()).as("window not full yet").isEqualTo(State.CLOSED);
        record(breaker, Outcome.SUCCESS);

        assertThat(breaker.state()).as("2 of 4 failed").isEqualTo(State.OPEN);
        assertThat(breaker.tryPass()).isFalse();
    }

    @Test
    void theWindowSlidesSoOldFailuresAgeOut() {
        CircuitBreaker breaker = new CircuitBreaker(4, 0.5, 10, Duration.ofSeconds(30), now::get);

        record(breaker, Outcome.FAILURE, Outcome.SUCCESS, Outcome.SUCCESS, Outcome.SUCCESS, Outcome.SUCCESS,
                Outcome.FAILURE, Outcome.SUCCESS);

        assertThat(breaker.state()).as("the window holds 1 failure in 4").isEqualTo(State.CLOSED);
    }

    @Test
    void opensAfterConsecutiveFailuresBeforeTheWindowFills() {
        CircuitBreaker breaker = new CircuitBreaker(20, 0.5, 3, Duration.ofSeconds(30), now::get);

        record(breaker, Outcome.FAILURE, Outcome.FAILURE, Outcome.FAILURE);

        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    @Test
    void halfOpenLetsExactlyOneProbeThroughAndItsOutcomeDecides() {
        CircuitBreaker breaker = new CircuitBreaker(20, 0.5, 1, Duration.ofSeconds(30), now::get);
        record(breaker, Outcome.FAILURE);
        assertThat(breaker.tryPass()).isFalse();

        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(breaker.tryPass()).as("the probe").isTrue();
        assertThat(breaker.tryPass()).as("a second call while the probe runs").isFalse();
        breaker.record(Outcome.FAILURE);
        assertThat(breaker.state()).isEqualTo(State.OPEN);

        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(breaker.tryPass()).isTrue();
        breaker.record(Outcome.SUCCESS);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.tryPass()).isTrue();
    }

    @Test
    void aCancelledProbeFreesTheSlotWithoutDeciding() {
        CircuitBreaker breaker = new CircuitBreaker(20, 0.5, 1, Duration.ofSeconds(30), now::get);
        record(breaker, Outcome.FAILURE);
        now.addAndGet(Duration.ofSeconds(31).toNanos());

        assertThat(breaker.tryPass()).isTrue();
        breaker.record(Outcome.NEUTRAL);

        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryPass()).as("the next call becomes the probe").isTrue();
    }

    private static void record(CircuitBreaker breaker, Outcome... outcomes) {
        for (Outcome outcome : outcomes) {
            assertThat(breaker.tryPass()).isTrue();
            breaker.record(outcome);
        }
    }
}
