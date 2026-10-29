package com.aioj.next.ai.domain.coderun;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiCodeRunSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Plan 15.5: the gap, one run per person, the runs at once and the counts, in that order. */
class CodeRunGateTest {
    private static final Instant NOW = Instant.parse("2026-10-29T03:15:30Z");
    private static final AiCodeRunSettings SETTINGS = new AiCodeRunSettings(true, 1, 10, 300, 2_000, null, null);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final CodeRunGate gate = new CodeRunGate(redis, ZoneId.of("Asia/Shanghai"));

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(values.increment(anyString())).thenReturn(1L);
    }

    @Test
    void aRunHoldsItsPlaceUntilItIsClosedAndCountsOnce() {
        try (CodeRunGate.Pass pass = gate.enter(7L, SETTINGS, NOW)) {
            assertThat(gate.running()).isEqualTo(1);
            pass.close();
            assertThat(gate.running()).as("closing twice frees one place").isZero();
        }
        assertThat(gate.running()).isZero();
        verify(values).setIfAbsent("aioj:code-run:gap:7", "1", Duration.ofMillis(2_000));
        verify(values).increment("aioj:code-run:minute:7:" + NOW.getEpochSecond() / 60);
        verify(values).increment("aioj:code-run:day:7:2026-10-29");
        verify(redis).expire(startsWith("aioj:code-run:minute:7:"), eq(Duration.ofMinutes(2)));
    }

    @Test
    void aBurstOfClicksIsOneRunAndUsesUpNothing() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true).thenReturn(false);

        CodeRunGate.Pass first = gate.enter(7L, SETTINGS, NOW);
        first.close();
        assertThatThrownBy(() -> gate.enter(7L, SETTINGS, NOW))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    assertThat(error.getMessage()).isEqualTo(CodeRunGate.TOO_FREQUENT);
                });
        verify(values).increment(startsWith("aioj:code-run:minute:"));
    }

    @Test
    void onePersonHasOneRunInProgress() {
        AiCodeRunSettings noGap = new AiCodeRunSettings(true, 4, 10, 300, 0, null, null);

        try (CodeRunGate.Pass ignored = gate.enter(7L, noGap, NOW)) {
            assertThatThrownBy(() -> gate.enter(7L, noGap, NOW)).hasMessage(CodeRunGate.IN_PROGRESS);
            try (CodeRunGate.Pass other = gate.enter(8L, noGap, NOW)) {
                assertThat(gate.running()).isEqualTo(2);
            }
        }
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void pastTheSiteWideLimitARunIsToldTheRunnerIsBusyWithoutCountingIt() {
        try (CodeRunGate.Pass ignored = gate.enter(7L, SETTINGS, NOW)) {
            assertThatThrownBy(() -> gate.enter(8L, SETTINGS, NOW))
                    .isInstanceOfSatisfying(DomainException.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(error.getMessage()).isEqualTo(CodeRunGate.BUSY);
                    });
            assertThat(gate.running()).isEqualTo(1);
        }
        verify(values, never()).increment(startsWith("aioj:code-run:minute:8:"));
        try (CodeRunGate.Pass ignored = gate.enter(8L, SETTINGS, NOW)) {
            assertThat(gate.running()).as("free again once the first run ends").isEqualTo(1);
        }
    }

    @Test
    void theMinuteAndDayCountsStopARunAndGiveItsPlaceBack() {
        when(values.increment(startsWith("aioj:code-run:minute:"))).thenReturn(11L).thenReturn(3L);
        when(values.increment(startsWith("aioj:code-run:day:"))).thenReturn(301L);

        assertThatThrownBy(() -> gate.enter(7L, SETTINGS, NOW))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    assertThat(error.getMessage()).isEqualTo(CodeRunGate.MINUTE_LIMIT);
                });
        assertThatThrownBy(() -> gate.enter(8L, SETTINGS, NOW)).hasMessage(CodeRunGate.DAY_LIMIT);
        assertThat(gate.running()).isZero();
    }

    @Test
    void withoutRedisTheGapAndCountsAreSkippedButTheRunsAtOnceStillHold() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

        try (CodeRunGate.Pass ignored = gate.enter(7L, SETTINGS, NOW)) {
            assertThatThrownBy(() -> gate.enter(8L, SETTINGS, NOW)).hasMessage(CodeRunGate.BUSY);
        }
        assertThat(gate.running()).isZero();
    }

    @Test
    void aSwitchedOffRunnerLetsNothingThrough() {
        assertThatThrownBy(() -> gate.enter(7L, new AiCodeRunSettings(false, 1, 10, 300, 2_000, null, null), NOW))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(error.getMessage()).isEqualTo(CodeRunGate.DISABLED);
                });
        verify(redis, never()).opsForValue();
    }
}
