package com.aioj.next.auth.domain;

import com.aioj.next.common.error.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Login locks by account and address, with an account-wide ceiling (T2.2). */
class InMemoryLoginAttemptGuardTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-30T08:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final InMemoryLoginAttemptGuard guard = new InMemoryLoginAttemptGuard(3, 10, Duration.ofMinutes(15),
            Duration.ofMinutes(15), clock);

    @Test
    void wrongPasswordsFromOneAddressDoNotLockTheStudentOutEverywhere() {
        for (int i = 0; i < 3; i++) {
            guard.recordFailure("student", "203.0.113.66");
        }

        assertThatThrownBy(() -> guard.ensureNotLocked("student", "203.0.113.66"))
                .isInstanceOfSatisfying(RateLimitedException.class, locked -> {
                    assertThat(locked.dimension()).isEqualTo("account_ip");
                    assertThat(locked.retryAfterSeconds()).isBetween(899L, 901L);
                });
        assertThatCode(() -> guard.ensureNotLocked("student", "198.51.100.7")).doesNotThrowAnyException();
    }

    @Test
    void failuresSpreadOverManyAddressesLockTheAccountAtItsCeiling() {
        for (int i = 0; i < 10; i++) {
            guard.recordFailure("Student", "198.51.100." + i);
        }

        assertThatThrownBy(() -> guard.ensureNotLocked("student", "192.0.2.1"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        locked -> assertThat(locked.dimension()).isEqualTo("account"));
    }

    @Test
    void aSuccessClearsItsAddressButNotTheAccountWideCount() {
        guard.recordFailure("student", "203.0.113.1");
        guard.recordFailure("student", "203.0.113.1");
        guard.recordSuccess("student", "203.0.113.1");
        guard.recordFailure("student", "203.0.113.1");
        guard.recordFailure("student", "203.0.113.1");

        assertThatCode(() -> guard.ensureNotLocked("student", "203.0.113.1")).as("the pair restarted at zero")
                .doesNotThrowAnyException();
    }

    @Test
    void locksEndAfterTheirDuration() {
        for (int i = 0; i < 3; i++) {
            guard.recordFailure("student", null);
        }
        assertThatThrownBy(() -> guard.ensureNotLocked("student", null)).isInstanceOf(RateLimitedException.class);

        now.set(now.get().plus(Duration.ofMinutes(16)));

        assertThatCode(() -> guard.ensureNotLocked("student", null)).doesNotThrowAnyException();
    }
}
