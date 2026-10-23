package com.aioj.next.agent.harness.runtime.api;

/**
 * The model capability the Harness needs, and nothing else (plan §3.3): one
 * completion, or one streamed completion. The assembling service adapts it to
 * its model client; the Harness and the model call package never depend on
 * each other. Failures are thrown as {@link ModelCallException}.
 */
public interface ModelPort {
    ModelTurn complete(ModelCall call);

    /**
     * Streams the answer's text through {@code listener} and returns the whole
     * turn. Ports without streaming deliver the text as one delta.
     */
    default ModelTurn stream(ModelCall call, ModelStreamListener listener) {
        ModelTurn turn = complete(call);
        if (!turn.hasToolCalls() && !turn.content().isEmpty()) {
            listener.onTextDelta(turn.content());
        }
        return turn;
    }

    /** Whether {@link ToolChoice#REQUIRED} is enforced by the Provider; otherwise the loop simulates it. */
    boolean supportsRequiredToolChoice();
}
