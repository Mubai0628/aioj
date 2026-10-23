package com.aioj.next.agent.harness.tool.internal;

import com.aioj.next.agent.harness.tool.api.BulkheadLimits;
import com.aioj.next.agent.harness.tool.api.ExecutionClass;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One bounded pool per execution class; a full pool rejects instead of queueing without limit. */
final class Bulkheads implements AutoCloseable {
    private final Map<ExecutionClass, ThreadPoolExecutor> pools = new EnumMap<>(ExecutionClass.class);

    Bulkheads(BulkheadLimits limits) {
        for (ExecutionClass executionClass : ExecutionClass.values()) {
            int threads = limits.threadsFor(executionClass);
            int slots = limits.queueSlotsFor(executionClass);
            BlockingQueue<Runnable> queue = slots == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(slots);
            AtomicInteger counter = new AtomicInteger();
            String prefix = "harness-tool-" + executionClass.name().toLowerCase(java.util.Locale.ROOT) + "-";
            pools.put(executionClass, new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS, queue,
                    runnable -> {
                        Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy()));
        }
    }

    ExecutorService pool(ExecutionClass executionClass) {
        return pools.get(executionClass);
    }

    @Override
    public void close() {
        pools.values().forEach(ExecutorService::shutdownNow);
    }
}
