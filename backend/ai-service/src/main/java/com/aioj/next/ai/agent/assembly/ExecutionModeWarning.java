package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.LoopContext;
import com.aioj.next.agent.harness.runtime.api.LoopInterceptor;
import com.aioj.next.agent.harness.runtime.api.OutgoingMessages;

/**
 * Marks a run that calls the model without thinking under its fallback as
 * {@code STANDARD_TOOL_FALLBACK} before each call, so the warning is in the manifest of
 * the first call such a run makes, including a run that starts in that mode.
 */
final class ExecutionModeWarning implements LoopInterceptor {
    private final ModelClientPort port;

    ExecutionModeWarning(ModelClientPort port) {
        this.port = port;
    }

    @Override
    public void beforeModelCall(LoopContext context, OutgoingMessages messages) {
        if (port.standardFallback()) {
            context.warn(ModelClientPort.STANDARD_TOOL_FALLBACK);
        }
    }
}
