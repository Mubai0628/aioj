package com.aioj.next.ai.model.resilience;

import com.aioj.next.ai.model.api.CancellationSignal;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ProviderId;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

/**
 * Concurrency limit per scope with a bounded wait (plan A1.5): a scope that is
 * full makes its own callers wait or fail, never the callers of another scope.
 */
public final class ScopeBulkheads {
    private static final long POLL_MILLIS = 100;

    private final ConcurrentMap<String, Bulkhead> byScope = new ConcurrentHashMap<>();
    private final ToIntFunction<String> concurrency;
    private final int maxWaiting;
    private final Duration maxWait;

    public ScopeBulkheads(ToIntFunction<String> concurrency, int maxWaiting, Duration maxWait) {
        this.concurrency = concurrency;
        this.maxWaiting = Math.max(0, maxWaiting);
        this.maxWait = maxWait;
    }

    /**
     * Takes a permit, waiting at most {@code maxWait}, up to the deadline and only
     * while fewer than {@code maxWaiting} callers already wait for this scope.
     */
    public Permit acquire(String scope, Instant deadline, CancellationSignal cancellation,
                          ProviderId provider, String model) {
        return acquire(scope, null, deadline, cancellation, provider, model);
    }

    /**
     * The same, with the scope's concurrency from its current settings ({@code null} keeps the
     * default). A new limit counts for permits taken from now on; calls holding one keep it.
     */
    public Permit acquire(String scope, Integer limit, Instant deadline, CancellationSignal cancellation,
                          ProviderId provider, String model) {
        Bulkhead bulkhead = byScope.computeIfAbsent(scope, key -> new Bulkhead(concurrency.applyAsInt(key)));
        bulkhead.resize(limit == null ? concurrency.applyAsInt(scope) : limit);
        if (bulkhead.permits.tryAcquire()) {
            return bulkhead.permit();
        }
        if (bulkhead.waiting.incrementAndGet() > maxWaiting) {
            bulkhead.waiting.decrementAndGet();
            throw full(scope, provider, model);
        }
        try {
            Instant waitUntil = Instant.now().plus(maxWait);
            boolean deadlineFirst = deadline != null && deadline.isBefore(waitUntil);
            Instant until = deadlineFirst ? deadline : waitUntil;
            while (true) {
                if (cancellation != null && cancellation.isCancelled()) {
                    throw new ModelException(ModelErrorKind.CANCELLED, provider, model, "AI model call was cancelled");
                }
                long left = Duration.between(Instant.now(), until).toMillis();
                if (left <= 0) {
                    if (deadlineFirst) {
                        throw new ModelException(ModelErrorKind.TIMEOUT, provider, model,
                                "AI model call ran out of time waiting for capacity");
                    }
                    throw full(scope, provider, model);
                }
                if (bulkhead.permits.tryAcquire(Math.min(left, POLL_MILLIS), TimeUnit.MILLISECONDS)) {
                    return bulkhead.permit();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelErrorKind.CANCELLED, provider, model, "AI model call was interrupted");
        } finally {
            bulkhead.waiting.decrementAndGet();
        }
    }

    /** Permits currently free in a scope; for tests and health reporting. */
    public int available(String scope) {
        Bulkhead bulkhead = byScope.get(scope);
        return bulkhead == null ? concurrency.applyAsInt(scope) : bulkhead.permits.availablePermits();
    }

    private static ModelException full(String scope, ProviderId provider, String model) {
        return new ModelException(ModelErrorKind.UNAVAILABLE, provider, model,
                "AI model capacity for " + scope + " is exhausted; try again shortly");
    }

    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private static final class Bulkhead {
        private final ResizableSemaphore permits;
        private final AtomicInteger waiting = new AtomicInteger();
        private volatile int limit;

        private Bulkhead(int permits) {
            this.limit = Math.max(1, permits);
            this.permits = new ResizableSemaphore(limit);
        }

        /** Lowering the limit can leave fewer than zero free permits until running calls finish. */
        private void resize(int requested) {
            int next = Math.max(1, requested);
            if (next == limit) {
                return;
            }
            synchronized (this) {
                int delta = next - limit;
                if (delta > 0) {
                    permits.release(delta);
                } else if (delta < 0) {
                    permits.reduce(-delta);
                }
                limit = next;
            }
        }

        private Permit permit() {
            AtomicInteger released = new AtomicInteger();
            return () -> {
                if (released.compareAndSet(0, 1)) {
                    permits.release();
                }
            };
        }
    }

    private static final class ResizableSemaphore extends Semaphore {
        private ResizableSemaphore(int permits) {
            super(permits, true);
        }

        private void reduce(int reduction) {
            reducePermits(reduction);
        }
    }
}
