package com.aioj.next.ai.agent.port;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * The timed boundary every memory and profile read goes through (plan T1.6, §3.5): tools,
 * context contributors and the Harness port adapters alike. A read runs on a bounded pool and
 * the caller waits at most the port timeout; a read that overruns is interrupted. Any failure
 * comes back as {@link PortUnavailableException}, so callers have one thing to degrade on.
 */
public final class PortCalls implements AutoCloseable {
    private final ThreadPoolExecutor pool;
    private final Duration timeout;
    private final UnaryOperator<Runnable> taskDecorator;

    public PortCalls(int threads, int queueSlots, Duration timeout, UnaryOperator<Runnable> taskDecorator) {
        BlockingQueue<Runnable> queue = queueSlots <= 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(queueSlots);
        AtomicInteger counter = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(Math.max(1, threads), Math.max(1, threads), 60, TimeUnit.SECONDS, queue,
                runnable -> {
                    // The harness- prefix puts these reads under the Harness database quota.
                    Thread thread = new Thread(runnable, "harness-port-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.timeout = timeout;
        this.taskDecorator = taskDecorator == null ? UnaryOperator.identity() : taskDecorator;
    }

    /** One thread and the given timeout, for tests that exercise the timeout. */
    public static PortCalls withTimeout(Duration timeout) {
        return new PortCalls(1, 4, timeout, UnaryOperator.identity());
    }

    public <T> T read(String port, Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(work);
        try {
            pool.execute(taskDecorator.apply(task));
        } catch (RejectedExecutionException ex) {
            throw new PortUnavailableException(port, "rejected", ex);
        }
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            throw new PortUnavailableException(port, "timeout", ex);
        } catch (ExecutionException ex) {
            throw new PortUnavailableException(port, "error", ex.getCause());
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new PortUnavailableException(port, "interrupted", ex);
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
