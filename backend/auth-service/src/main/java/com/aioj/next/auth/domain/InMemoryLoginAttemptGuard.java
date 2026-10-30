package com.aioj.next.auth.domain;

import com.aioj.next.common.error.RateLimitedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-process sliding-window login throttle. The deployment runs a single
 * auth-service instance, so process-local state is sufficient; restarts reset
 * the counters, which is acceptable because locks are a deceleration measure.
 *
 * <p>Two counters (T2.2): failures for an account from one client address lock that pair, so
 * someone typing wrong passwords elsewhere cannot lock a student out; failures for an account
 * from all addresses together lock the account at a much higher count, which bounds guessing
 * spread over many addresses.
 */
public class InMemoryLoginAttemptGuard implements LoginAttemptGuard {
    static final String MESSAGE = "Too many failed login attempts. The account is temporarily locked.";
    private static final int DEFAULT_ACCOUNT_MAX_FAILURES = 50;
    private static final int SWEEP_ABOVE = 10_000;

    private final int maxFailures;
    private final int accountMaxFailures;
    private final Duration window;
    private final Duration lockDuration;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockedUntil = new ConcurrentHashMap<>();

    public InMemoryLoginAttemptGuard(int maxFailures, Duration window, Duration lockDuration) {
        this(maxFailures, DEFAULT_ACCOUNT_MAX_FAILURES, window, lockDuration, Clock.systemUTC());
    }

    public InMemoryLoginAttemptGuard(int maxFailures, Duration window, Duration lockDuration, Clock clock) {
        this(maxFailures, DEFAULT_ACCOUNT_MAX_FAILURES, window, lockDuration, clock);
    }

    public InMemoryLoginAttemptGuard(int maxFailures, int accountMaxFailures, Duration window, Duration lockDuration,
                                     Clock clock) {
        this.maxFailures = Math.max(1, maxFailures);
        this.accountMaxFailures = Math.max(this.maxFailures, accountMaxFailures);
        this.window = window;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    @Override
    public void ensureNotLocked(String account, String clientIp) {
        Instant now = clock.instant();
        ensureOpen(pairKey(account, clientIp), "account_ip", now);
        ensureOpen(accountKey(account), "account", now);
    }

    @Override
    public void recordFailure(String account, String clientIp) {
        Instant now = clock.instant();
        record(pairKey(account, clientIp), maxFailures, now);
        record(accountKey(account), accountMaxFailures, now);
        if (failures.size() > SWEEP_ABOVE) {
            sweep(now);
        }
    }

    /** Clears this address's failures; the account-wide count keeps running until its window passes. */
    @Override
    public void recordSuccess(String account, String clientIp) {
        String key = pairKey(account, clientIp);
        failures.remove(key);
        lockedUntil.remove(key);
    }

    private void ensureOpen(String key, String dimension, Instant now) {
        Instant until = lockedUntil.get(key);
        if (until == null) {
            return;
        }
        if (now.isBefore(until)) {
            long seconds = Math.max(1, Duration.between(now, until).toSeconds() + 1);
            throw new RateLimitedException("login", dimension, seconds, MESSAGE);
        }
        lockedUntil.remove(key);
    }

    private void record(String key, int limit, Instant now) {
        Deque<Instant> attempts = failures.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
        attempts.addLast(now);
        purgeExpired(attempts, now);
        if (attempts.size() >= limit) {
            lockedUntil.put(key, now.plus(lockDuration));
            failures.remove(key);
        }
    }

    /** Guessing at many made-up accounts must not grow the maps without end. */
    private void sweep(Instant now) {
        failures.entrySet().removeIf(entry -> {
            purgeExpired(entry.getValue(), now);
            return entry.getValue().isEmpty();
        });
        lockedUntil.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
    }

    private void purgeExpired(Deque<Instant> attempts, Instant now) {
        Instant cutoff = now.minus(window);
        Iterator<Instant> iterator = attempts.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().isBefore(cutoff)) {
                iterator.remove();
            } else {
                break;
            }
        }
    }

    private static String pairKey(String account, String clientIp) {
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim().toLowerCase(Locale.ROOT);
        return "pair:" + normalize(account) + "|" + ip;
    }

    private static String accountKey(String account) {
        return "account:" + normalize(account);
    }

    private static String normalize(String account) {
        return account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
    }
}
