package com.aioj.next.agent.harness.runtime.api;

import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;

/**
 * Observes a run, for example to write its state rows or stream progress to
 * a client. Listeners only observe: an exception from one is recorded as the
 * warning {@code listener_failed} and never changes the run.
 */
public interface RunListener {
    RunListener NONE = new RunListener() {
    };

    default void onRunStarted(LoopContext context) {
    }

    default void onModelCall(LoopContext context, int callSeq, ModelCall call) {
    }

    default void onModelTurn(LoopContext context, int callSeq, ModelTurn turn) {
    }

    default void onTextDelta(LoopContext context, String delta) {
    }

    default void onToolStarted(LoopContext context, ToolCallRequest call) {
    }

    default void onToolOutcome(LoopContext context, ToolCallRequest call, ToolOutcome outcome) {
    }

    /** The tool runtime threw before it could return an outcome. */
    default void onToolFailed(LoopContext context, ToolCallRequest call) {
    }

    default void onRunFinished(LoopContext context, RunResult result) {
    }
}
