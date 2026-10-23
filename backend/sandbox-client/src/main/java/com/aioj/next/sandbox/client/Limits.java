package com.aioj.next.sandbox.client;

/**
 * Unit conversions and limit rules shared by judging and execution. All
 * arithmetic saturates at {@code Long.MAX_VALUE} instead of overflowing.
 */
public final class Limits {
    public static final int DEFAULT_STDOUT_BASE_COLLECT_LIMIT_BYTES = 64 * 1024;
    public static final int DEFAULT_STDOUT_EXTRA_COLLECT_BYTES = 64 * 1024;
    public static final int DEFAULT_STDOUT_MAX_COLLECT_LIMIT_BYTES = 4 * 1024 * 1024;
    public static final int DEFAULT_STDERR_COLLECT_LIMIT_BYTES = 64 * 1024;
    public static final int DEFAULT_MAX_PROCESS_LIMIT = 80;
    private static final long WALL_CLOCK_GRACE_NANOS = 2_000_000_000L;

    private Limits() {
    }

    public static long millisToNanos(long millis) {
        return safeMultiply(millis, 1_000_000L);
    }

    public static long kbToBytes(long kb) {
        return safeMultiply(kb, 1024L);
    }

    /** Rounds up; null stays null and non-positive becomes 0. */
    public static Long nanosToMillis(Long nanos) {
        if (nanos == null) {
            return null;
        }
        if (nanos <= 0) {
            return 0L;
        }
        return (nanos + 999_999L) / 1_000_000L;
    }

    /** Rounds up; null stays null and non-positive becomes 0. */
    public static Long bytesToKb(Long bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes <= 0) {
            return 0L;
        }
        return (bytes + 1023L) / 1024L;
    }

    public static long safeMultiply(long value, long factor) {
        if (value > Long.MAX_VALUE / factor) {
            return Long.MAX_VALUE;
        }
        return value * factor;
    }

    public static long safeAdd(long value, long addend) {
        if (value > Long.MAX_VALUE - addend) {
            return Long.MAX_VALUE;
        }
        return value + addend;
    }

    public static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    /** Wall clock for a CPU limit: twice the CPU time, but at least the CPU time plus two seconds. */
    public static long wallClockLimit(long cpuLimitNs) {
        long normalized = Math.max(1L, cpuLimitNs);
        return Math.max(safeMultiply(normalized, 2L), safeAdd(normalized, WALL_CLOCK_GRACE_NANOS));
    }

    /** The requested process count, at least 1 and at most the configured maximum (80 when unset). */
    public static int processLimit(int requested, int configuredMaximum) {
        return Math.min(Math.max(1, requested), positiveOrDefault(configuredMaximum, DEFAULT_MAX_PROCESS_LIMIT));
    }

    /**
     * stdout collector cap for a case: the base cap, or the expected output size
     * plus some slack when that is larger, never above the maximum.
     */
    public static int stdoutCollectLimitBytes(long expectedOutputBytes, int baseLimit, int extraBytes, int maxLimit) {
        int normalizedBase = positiveOrDefault(baseLimit, DEFAULT_STDOUT_BASE_COLLECT_LIMIT_BYTES);
        int normalizedExtra = Math.max(0, extraBytes);
        int normalizedMax = Math.max(normalizedBase, positiveOrDefault(maxLimit, DEFAULT_STDOUT_MAX_COLLECT_LIMIT_BYTES));
        long expectedBytes = Math.max(0L, expectedOutputBytes);
        long wanted = expectedBytes <= normalizedBase ? normalizedBase : safeAdd(expectedBytes, normalizedExtra);
        return (int) Math.min(normalizedMax, wanted);
    }

    public static int stdoutBaseCollectLimitBytes(int configuredLimit) {
        return positiveOrDefault(configuredLimit, DEFAULT_STDOUT_BASE_COLLECT_LIMIT_BYTES);
    }

    public static int stderrCollectLimitBytes(int configuredLimit) {
        return positiveOrDefault(configuredLimit, DEFAULT_STDERR_COLLECT_LIMIT_BYTES);
    }
}
