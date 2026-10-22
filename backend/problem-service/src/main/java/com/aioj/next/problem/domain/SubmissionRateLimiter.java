package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.problem.config.SubmissionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-user submission limit in fixed one-minute windows, counted in Redis so all
 * problem-service instances share it. A Redis outage lets submissions through:
 * a broken limiter must not block contest submissions.
 */
@Component
public class SubmissionRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(SubmissionRateLimiter.class);
    private static final Duration WINDOW_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;
    private final SubmissionProperties properties;

    public SubmissionRateLimiter(StringRedisTemplate redisTemplate, SubmissionProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void check(Long userId, Instant now) {
        int limit = properties.getRateLimitPerMinute();
        if (limit <= 0 || userId == null) {
            return;
        }
        String key = "aioj:submission-rate:" + userId + ":" + now.getEpochSecond() / 60;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, WINDOW_TTL);
            }
        } catch (RuntimeException ex) {
            log.warn("Submission rate limit skipped, Redis unavailable: {}", ex.getClass().getSimpleName());
            return;
        }
        if (count != null && count > limit) {
            throw new DomainException(ErrorCode.TOO_MANY_REQUESTS,
                    "Too many submissions; please wait a minute before submitting again");
        }
    }
}
