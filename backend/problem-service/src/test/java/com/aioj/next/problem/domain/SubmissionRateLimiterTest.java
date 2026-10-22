package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.problem.config.SubmissionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SubmissionRateLimiterTest {
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:30Z");

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SubmissionProperties properties = new SubmissionProperties();
    private final SubmissionRateLimiter limiter = new SubmissionRateLimiter(redis, properties);

    @Test
    void firstSubmissionInAWindowSetsTheExpiry() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment("aioj:submission-rate:91:" + NOW.getEpochSecond() / 60)).thenReturn(1L);

        limiter.check(91L, NOW);

        verify(redis).expire("aioj:submission-rate:91:" + NOW.getEpochSecond() / 60, Duration.ofMinutes(2));
    }

    @Test
    void submissionsBeyondTheLimitAreRejected() {
        properties.setRateLimitPerMinute(3);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(4L);

        DomainException error = assertThrows(DomainException.class, () -> limiter.check(91L, NOW));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS, error.errorCode());
        verify(redis, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void redisOutageLetsSubmissionsThrough() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertDoesNotThrow(() -> limiter.check(91L, NOW));
    }

    @Test
    void zeroLimitDisablesTheCheck() {
        properties.setRateLimitPerMinute(0);

        limiter.check(91L, NOW);

        verifyNoInteractions(redis);
    }
}
