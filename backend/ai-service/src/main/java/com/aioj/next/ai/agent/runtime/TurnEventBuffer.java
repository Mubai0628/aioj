package com.aioj.next.ai.agent.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A single original SSE attachment's in-memory progress. The producer never waits for the
 * client: adjacent text in a model call is merged, and the entry limit is derived from the
 * run's bounded model and tool budgets. Text also has a hard character limit: overflow
 * discards speculative progress and falls back to the separate, complete final message.
 * A reconnect intentionally gets no old progress.
 *
 * <p>Only the prefix before the first possible internal protocol marker is streamed. Once
 * a brace, double quote or backtick appears, the rest of that model call stays in the normal
 * final response path. The markers are checked across chunks, before any characters reach
 * the client. This deliberately also suppresses parts of ordinary code and quoted text.</p>
 */
public final class TurnEventBuffer {
    public record Event(String type, int step, String text, String phase, String name,
                        String status, Long durationMs) { }

    public static final int MAX_BUFFERED_TEXT_CHARS = 262_144;

    private static final class Entry {
        final String type;
        final int step;
        final StringBuilder text;
        final String phase;
        final String name;
        final String status;
        final Long durationMs;

        Entry(int step, String text) {
            this.type = "delta";
            this.step = step;
            this.text = new StringBuilder(text);
            this.phase = null;
            this.name = null;
            this.status = null;
            this.durationMs = null;
        }

        Entry(int step, String phase, String name, String status, Long durationMs) {
            this.type = "tool";
            this.step = step;
            this.text = null;
            this.phase = phase;
            this.name = name;
            this.status = status;
            this.durationMs = durationMs;
        }

        Event snapshot() {
            return new Event(type, step, text == null ? null : text.toString(), phase, name, status, durationMs);
        }
    }

    private final int maxEntries;
    private final long startedNanos;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private boolean closed;
    private boolean detached;
    private boolean suppressed;
    private boolean overflowed;
    private boolean firstDeltaRecorded;
    private int step;
    private int bufferedTextChars;
    private String pendingHighSurrogate = "";

    public TurnEventBuffer(int maxEntries, long startedNanos) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.startedNanos = startedNanos;
    }

    /** At most one queued text entry per model call plus two events per executed tool. */
    public static int capacityFor(LoopBudget budget) {
        long capacity = (long) budget.maxAgentSteps() + 2L + 2L * budget.maxToolCalls();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, capacity));
    }

    public synchronized void beginStep(int sequence) {
        step = sequence;
        suppressed = false;
        pendingHighSurrogate = "";
    }

    public synchronized void text(String fragment) {
        if (closed || detached || suppressed || overflowed || fragment == null || fragment.isEmpty()) {
            return;
        }
        String value = pendingHighSurrogate + fragment;
        pendingHighSurrogate = "";
        if (Character.isHighSurrogate(value.charAt(value.length() - 1))) {
            pendingHighSurrogate = value.substring(value.length() - 1);
            value = value.substring(0, value.length() - 1);
        }
        int marker = firstMarker(value);
        if (marker >= 0) {
            suppressed = true;
            pendingHighSurrogate = "";
            value = value.substring(0, marker);
        }
        if (value.isEmpty()) {
            return;
        }
        if (value.length() > MAX_BUFFERED_TEXT_CHARS - bufferedTextChars) {
            overflowed = true;
            entries.clear();
            bufferedTextChars = 0;
            pendingHighSurrogate = "";
            notifyAll();
            return;
        }
        Entry last = entries.peekLast();
        if (last != null && "delta".equals(last.type) && last.step == step) {
            last.text.append(value);
        } else {
            add(new Entry(step, value));
        }
        bufferedTextChars += value.length();
        notifyAll();
    }

    public synchronized void toolStarted(String name) {
        if (!closed && !detached && !overflowed) {
            add(new Entry(step, "started", name, null, null));
            notifyAll();
        }
    }

    public synchronized void toolFinished(String name, String status, long durationMs) {
        if (!closed && !detached && !overflowed) {
            add(new Entry(step, "finished", name, status, Math.max(0, durationMs)));
            notifyAll();
        }
    }

    /** Waits at most one batching interval; no network I/O is performed under the lock. */
    public synchronized List<Event> awaitBatch(long waitMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMillis));
        while (!closed && !detached) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        List<Event> result = new ArrayList<>(entries.size());
        while (!entries.isEmpty()) {
            result.add(entries.removeFirst().snapshot());
        }
        bufferedTextChars = 0;
        return result;
    }

    /** Producer completion does not discard progress that the original client has yet to read. */
    public synchronized void close() {
        closed = true;
        pendingHighSurrogate = "";
        notifyAll();
    }

    /** A disconnected client no longer retains output; this never cancels the background run. */
    public synchronized void detach() {
        detached = true;
        entries.clear();
        bufferedTextChars = 0;
        pendingHighSurrogate = "";
        notifyAll();
    }

    public synchronized boolean drained() {
        return (closed || detached) && entries.isEmpty();
    }

    /** Whether progress was omitted because its bounded text capacity was exceeded. */
    public synchronized boolean overflowed() {
        return overflowed;
    }

    /** Elapsed time to the first delta actually sent, or -1 on subsequent calls. */
    public synchronized long firstDeltaElapsedNanos() {
        if (firstDeltaRecorded) {
            return -1L;
        }
        firstDeltaRecorded = true;
        return Math.max(0L, System.nanoTime() - startedNanos);
    }

    private void add(Entry entry) {
        if (entries.size() >= maxEntries) {
            // With one text entry per model call and two entries per executed tool this is
            // unreachable for the budgets used to construct the buffer; never silently drop.
            throw new IllegalStateException("turn progress exceeded its run budget");
        }
        entries.addLast(entry);
    }

    private static int firstMarker(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '"' || c == '`') {
                return i;
            }
        }
        return -1;
    }
}
