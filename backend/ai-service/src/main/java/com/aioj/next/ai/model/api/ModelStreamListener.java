package com.aioj.next.ai.model.api;

/**
 * Receives a stream as it arrives, on the thread that called
 * {@link ModelClient#stream}. Reasoning deltas are deliberately not offered.
 */
public interface ModelStreamListener {

    ModelStreamListener NONE = new ModelStreamListener() {
    };

    default void onTextDelta(String delta) {
    }

    /** The first fragment of a tool call arrived; {@code name} is the internal dotted name. */
    default void onToolCallStarted(int index, String name) {
    }
}
