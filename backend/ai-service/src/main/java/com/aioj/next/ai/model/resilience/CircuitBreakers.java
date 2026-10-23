package com.aioj.next.ai.model.resilience;

import com.aioj.next.ai.model.api.ModelErrorKind;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** One {@link CircuitBreaker} per endpoint. */
public final class CircuitBreakers {
    private final ConcurrentMap<String, CircuitBreaker> byEndpoint = new ConcurrentHashMap<>();
    private final int windowSize;
    private final double failureRateThreshold;
    private final int consecutiveFailures;
    private final Duration openDuration;
    private final LongSupplier nanoClock;

    public CircuitBreakers(int windowSize, double failureRateThreshold, int consecutiveFailures,
                           Duration openDuration, LongSupplier nanoClock) {
        this.windowSize = windowSize;
        this.failureRateThreshold = failureRateThreshold;
        this.consecutiveFailures = consecutiveFailures;
        this.openDuration = openDuration;
        this.nanoClock = nanoClock;
    }

    public CircuitBreaker forEndpoint(String endpointKey) {
        return byEndpoint.computeIfAbsent(endpointKey, key -> new CircuitBreaker(windowSize, failureRateThreshold,
                consecutiveFailures, openDuration, nanoClock));
    }

    /** How a failure of this kind counts for the endpoint's health. */
    public static CircuitBreaker.Outcome outcomeOf(ModelErrorKind kind) {
        return switch (kind) {
            case UNAVAILABLE, TIMEOUT -> CircuitBreaker.Outcome.FAILURE;
            case CANCELLED -> CircuitBreaker.Outcome.NEUTRAL;
            default -> CircuitBreaker.Outcome.SUCCESS;
        };
    }
}
