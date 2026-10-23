package com.aioj.next.agent.harness.sandbox.api;

import java.time.Duration;

public record ExecutionLimits(Duration cpuTime, Duration wallTime, long memoryBytes, int maxOutputBytes) {
    public ExecutionLimits {
        if (cpuTime == null || wallTime == null || cpuTime.isNegative() || cpuTime.isZero()
                || wallTime.compareTo(cpuTime) < 0) {
            throw new IllegalArgumentException("cpu time must be positive and wall time at least cpu time");
        }
        if (memoryBytes <= 0 || maxOutputBytes <= 0) {
            throw new IllegalArgumentException("memory and output limits must be positive");
        }
    }
}
