package com.aioj.next.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * {@code aioj.sandbox.requests}: every request a service sends its go-judge sandbox, by operation
 * and outcome (T2.5). judge-worker and agent-executor hand {@link #record} to their GoJudgeClient.
 */
public final class SandboxRequestMeter {
    public static final String NAME = "aioj.sandbox.requests";
    private final MeterRegistry registry;

    /** @param registry null records nothing (tests that build the clients by hand) */
    public SandboxRequestMeter(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String operation, String outcome, long elapsedNanos) {
        if (registry == null) {
            return;
        }
        Timer.builder(NAME)
                .description("Requests to the go-judge sandbox by operation and outcome")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }
}
