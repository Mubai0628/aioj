package com.aioj.next.agent.harness.runtime.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancels a run from outside, for example when the user leaves or a newer turn supersedes it. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public static CancellationToken none() {
        return new CancellationToken();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            callbacks.forEach(Runnable::run);
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Runs {@code callback} on cancellation, or at once if already cancelled. */
    public void onCancel(Runnable callback) {
        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            callback.run();
        }
    }
}
