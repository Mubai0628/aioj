package com.aioj.next.ai.config;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * How many database connections Harness work may hold at once (plan T1.6, §3.5): the
 * connection pool size minus one, so a burst of turns, tools or async jobs can never take the
 * whole pool from the non-Agent endpoints. Harness work is whatever runs on a thread whose name
 * starts with {@code harness-} (context contributors, tool bulkheads, the memory and profile
 * port) or inside {@link #runAsHarness}. A Harness thread that finds no permit within the wait
 * gets no connection; its caller degrades as for any database failure.
 */
public final class HarnessDbQuota {
    public static final String THREAD_PREFIX = "harness-";

    private static final ThreadLocal<Boolean> MARKED = ThreadLocal.withInitial(() -> false);

    private final Semaphore permits;
    private final int capacity;
    private final Duration wait;

    public HarnessDbQuota(int permits, Duration wait) {
        this.capacity = Math.max(1, permits);
        this.permits = new Semaphore(capacity, true);
        this.wait = wait;
    }

    /** The pool size minus one, at least one. */
    public static int permitsFor(int poolSize) {
        return Math.max(1, poolSize - 1);
    }

    public static boolean isHarnessThread() {
        return MARKED.get() || Thread.currentThread().getName().startsWith(THREAD_PREFIX);
    }

    /** Runs work that is Harness work but does not run on a Harness thread (the async job worker). */
    public static <T> T runAsHarness(Supplier<T> work) {
        boolean previous = MARKED.get();
        MARKED.set(true);
        try {
            return work.get();
        } finally {
            MARKED.set(previous);
        }
    }

    public static void runAsHarness(Runnable work) {
        runAsHarness(() -> {
            work.run();
            return null;
        });
    }

    boolean tryAcquire() throws InterruptedException {
        return permits.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS);
    }

    void release() {
        permits.release();
    }

    public int capacity() {
        return capacity;
    }

    /** Permits in use right now, for tests and the record of the measurement. */
    public int inUse() {
        return capacity - permits.availablePermits();
    }

    Duration waitTime() {
        return wait;
    }
}
