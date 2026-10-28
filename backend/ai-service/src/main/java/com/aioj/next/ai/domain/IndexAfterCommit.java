package com.aioj.next.ai.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Plan T1.6 (remote calls out of transactions): retrieval indexing calls the embedding API, so a
 * write transaction must not wait for it. Inside {@link #run} each {@code indexChunk} is queued
 * instead of run; the queue runs once the work, transaction included, has returned and its
 * connection is back in the pool. When the work throws, the queue is dropped with it.
 */
public final class IndexAfterCommit {
    private static final ThreadLocal<List<Runnable>> QUEUE = new ThreadLocal<>();

    private IndexAfterCommit() {
    }

    public static <T> T run(Supplier<T> work) {
        if (QUEUE.get() != null) {
            return work.get(); // already inside an outer scope; it flushes once at its end
        }
        List<Runnable> queue = new ArrayList<>();
        QUEUE.set(queue);
        T result;
        try {
            result = work.get();
        } finally {
            QUEUE.remove();
        }
        queue.forEach(Runnable::run);
        return result;
    }

    /** Queues the indexing when a scope is open and says so; otherwise the caller indexes now. */
    static boolean deferIfOpen(Runnable indexing) {
        List<Runnable> queue = QUEUE.get();
        if (queue == null) {
            return false;
        }
        queue.add(indexing);
        return true;
    }
}
