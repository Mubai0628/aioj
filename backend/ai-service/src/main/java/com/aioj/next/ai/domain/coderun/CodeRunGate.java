package com.aioj.next.ai.domain.coderun;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.error.RateLimitedException;
import com.aioj.next.contract.ai.AiCodeRunSettings;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plan 15.5: whether a self-test run may start now, checked in this order:
 *
 * <ol>
 *   <li>the gap between two runs of one person, so a burst of clicks counts once and uses up nothing;</li>
 *   <li>one run in progress per person;</li>
 *   <li>the number of runs at once, site-wide: a run past it is told the runner is busy, it does not queue;</li>
 *   <li>the per-minute and per-day counts of the person.</li>
 * </ol>
 *
 * <p>The gap and the counts are kept in Redis, shared by every instance, as problem-service counts
 * submissions; when Redis is down they are skipped and the run is still bounded by the rest. The runs
 * in progress are counted by this instance (every deployment so far runs one ai-service).</p>
 */
@Component
public class CodeRunGate {
    static final String DISABLED = "Code runs are switched off";
    static final String TOO_FREQUENT = "Code runs are too frequent; please wait a moment";
    static final String IN_PROGRESS = "Your previous code run has not finished yet";
    static final String BUSY = "Code runs are busy; please try again shortly";
    static final String MINUTE_LIMIT = "Code run limit per minute reached";
    static final String DAY_LIMIT = "Code run limit per day reached";

    private static final Logger log = LoggerFactory.getLogger(CodeRunGate.class);
    private static final Duration MINUTE_TTL = Duration.ofMinutes(2);
    private static final Duration DAY_TTL = Duration.ofHours(26);

    private final StringRedisTemplate redis;
    private final ZoneId zone;
    private final MeterRegistry meters;
    private final AtomicInteger running = new AtomicInteger();
    private final Set<Long> runningUsers = ConcurrentHashMap.newKeySet();

    public CodeRunGate(StringRedisTemplate redis) {
        this(redis, ZoneId.systemDefault(), null);
    }

    @Autowired
    public CodeRunGate(StringRedisTemplate redis, ObjectProvider<MeterRegistry> meters) {
        this(redis, ZoneId.systemDefault(), meters.getIfAvailable());
    }

    CodeRunGate(StringRedisTemplate redis, ZoneId zone) {
        this(redis, zone, null);
    }

    CodeRunGate(StringRedisTemplate redis, ZoneId zone, MeterRegistry meters) {
        this.redis = redis;
        this.zone = zone;
        this.meters = meters;
    }

    /** A run that may go ahead; closing it frees its place. */
    public final class Pass implements AutoCloseable {
        private final Long userId;
        private boolean closed;

        private Pass(Long userId) {
            this.userId = userId;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                release(userId);
            }
        }
    }

    public Pass enter(Long userId, AiCodeRunSettings settings, Instant now) {
        if (!settings.enabled()) {
            throw new DomainException(ErrorCode.FORBIDDEN, DISABLED);
        }
        if (settings.minIntervalMillis() > 0 && !firstInGap(userId, settings.minIntervalMillis())) {
            throw new RateLimitedException("code-run", "user",
                    (settings.minIntervalMillis() + 999L) / 1000L, TOO_FREQUENT);
        }
        if (!runningUsers.add(userId)) {
            throw new RateLimitedException("code-run", "user", 2, IN_PROGRESS);
        }
        if (running.incrementAndGet() > settings.maxConcurrency()) {
            release(userId);
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, BUSY);
        }
        try {
            count(userId, settings, now);
        } catch (RuntimeException ex) {
            release(userId);
            throw ex;
        }
        return new Pass(userId);
    }

    /** Runs in progress on this instance. */
    int running() {
        return running.get();
    }

    private void release(Long userId) {
        running.decrementAndGet();
        runningUsers.remove(userId);
    }

    private boolean firstInGap(Long userId, int intervalMillis) {
        try {
            Boolean first = redis.opsForValue().setIfAbsent("aioj:code-run:gap:" + userId, "1",
                    Duration.ofMillis(intervalMillis));
            return !Boolean.FALSE.equals(first);
        } catch (RuntimeException ex) {
            log.warn("Code run gap skipped, Redis unavailable: {}", ex.getClass().getSimpleName());
            bypassed();
            return true;
        }
    }

    private void count(Long userId, AiCodeRunSettings settings, Instant now) {
        Long day;
        try {
            Long minute = increment("aioj:code-run:minute:" + userId + ":" + now.getEpochSecond() / 60, MINUTE_TTL);
            if (minute != null && minute > settings.perMinuteLimit()) {
                throw new RateLimitedException("code-run", "user", 60 - now.getEpochSecond() % 60, MINUTE_LIMIT);
            }
            day = increment("aioj:code-run:day:" + userId + ":" + LocalDate.ofInstant(now, zone), DAY_TTL);
        } catch (DomainException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Code run counts skipped, Redis unavailable: {}", ex.getClass().getSimpleName());
            bypassed();
            return;
        }
        if (day != null && day > settings.perDayLimit()) {
            long untilTomorrow = Duration.between(now,
                    LocalDate.ofInstant(now, zone).plusDays(1).atStartOfDay(zone).toInstant()).toSeconds();
            throw new RateLimitedException("code-run", "user", untilTomorrow, DAY_LIMIT);
        }
    }

    private void bypassed() {
        if (meters != null) {
            meters.counter("aioj.ratelimit.bypassed", "limiter", "code-run", "reason", "redis_unavailable")
                    .increment();
        }
    }

    private Long increment(String key, Duration ttl) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, ttl);
        }
        return count;
    }
}
