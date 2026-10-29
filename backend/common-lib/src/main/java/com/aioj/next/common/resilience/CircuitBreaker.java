package com.aioj.next.common.resilience;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Count-based circuit breaker for one endpoint (plan §3.2.3): it opens after
 * {@code consecutiveFailures} failures in a row, or when the failure rate over a
 * full window reaches the threshold. After {@code openDuration} it lets one probe
 * through; the probe's outcome closes it again or reopens it.
 *
 * <p>Only provider-health failures count (unavailable, timeout). A rejected
 * request or API key proves the endpoint answers and counts as a success; a
 * cancelled call counts as nothing.</p>
 */
public final class CircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public enum Outcome {
        SUCCESS,
        FAILURE,
        NEUTRAL
    }

    private final boolean[] window;
    private final double failureRateThreshold;
    private final int consecutiveFailureLimit;
    private final long openNanos;
    private final LongSupplier nanoClock;

    private State state = State.CLOSED;
    private int recorded;
    private int next;
    private int failuresInWindow;
    private int consecutiveFailures;
    private long openUntil;
    private boolean probeInFlight;

    public CircuitBreaker(int windowSize, double failureRateThreshold, int consecutiveFailures,
                          Duration openDuration, LongSupplier nanoClock) {
        this.window = new boolean[Math.max(1, windowSize)];
        this.failureRateThreshold = failureRateThreshold;
        this.consecutiveFailureLimit = Math.max(1, consecutiveFailures);
        this.openNanos = openDuration.toNanos();
        this.nanoClock = nanoClock;
    }

    /** Whether a call may go out now. A {@code true} in half-open state makes that call the probe. */
    public synchronized boolean tryPass() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (nanoClock.getAsLong() - openUntil < 0) {
                    return false;
                }
                state = State.HALF_OPEN;
                probeInFlight = true;
                return true;
            case HALF_OPEN:
            default:
                if (probeInFlight) {
                    return false;
                }
                probeInFlight = true;
                return true;
        }
    }

    public synchronized void record(Outcome outcome) {
        if (state == State.HALF_OPEN) {
            probeInFlight = false;
            if (outcome == Outcome.SUCCESS) {
                close();
            } else if (outcome == Outcome.FAILURE) {
                open();
            }
            return;
        }
        if (state == State.OPEN || outcome == Outcome.NEUTRAL) {
            return;
        }
        boolean failure = outcome == Outcome.FAILURE;
        if (recorded == window.length && window[next]) {
            failuresInWindow--;
        }
        window[next] = failure;
        next = (next + 1) % window.length;
        recorded = Math.min(recorded + 1, window.length);
        if (failure) {
            failuresInWindow++;
            consecutiveFailures++;
        } else {
            consecutiveFailures = 0;
        }
        boolean rateTripped = recorded == window.length
                && failuresInWindow >= failureRateThreshold * window.length;
        if (consecutiveFailures >= consecutiveFailureLimit || rateTripped) {
            open();
        }
    }

    public synchronized State state() {
        return state;
    }

    private void open() {
        state = State.OPEN;
        openUntil = nanoClock.getAsLong() + openNanos;
    }

    private void close() {
        state = State.CLOSED;
        recorded = 0;
        next = 0;
        failuresInWindow = 0;
        consecutiveFailures = 0;
    }
}
