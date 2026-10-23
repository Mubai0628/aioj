package com.aioj.next.ai.model.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe, one-way cancellation flag with callbacks. */
public final class CancellationSignal {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable callback : callbacks) {
                try {
                    callback.run();
                } catch (RuntimeException ignored) {
                    // One failing callback must not keep the others from closing their resources.
                }
            }
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Runs the callback on cancellation, or right away if already cancelled.
     * Closing the returned registration removes the callback.
     */
    public Registration onCancel(Runnable callback) {
        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            callback.run();
        }
        return () -> callbacks.remove(callback);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
