package com.aioj.next.problem.domain;

import com.aioj.next.common.error.RateLimitedException;
import com.aioj.next.problem.config.SubmissionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final MeterRegistry meters;

    public SubmissionRateLimiter(StringRedisTemplate redisTemplate, SubmissionProperties properties) {
        this(redisTemplate, properties, (MeterRegistry) null);
    }

    @Autowired
    public SubmissionRateLimiter(StringRedisTemplate redisTemplate, SubmissionProperties properties,
                                 ObjectProvider<MeterRegistry> meters) {
        this(redisTemplate, properties, meters.getIfAvailable());
    }

    private SubmissionRateLimiter(StringRedisTemplate redisTemplate, SubmissionProperties properties,
                                  MeterRegistry meters) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.meters = meters;
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
            if (meters != null) {
                meters.counter("aioj.ratelimit.bypassed", "limiter", "submission", "reason", "redis_unavailable")
                        .increment();
            }
            return;
        }
        if (count != null && count > limit) {
            // The window is the calendar minute, so the wait is what is left of it (T2.2).
            throw new RateLimitedException("submission", "user", 60 - now.getEpochSecond() % 60,
                    "Too many submissions; please wait a minute before submitting again");
        }
    }
}
