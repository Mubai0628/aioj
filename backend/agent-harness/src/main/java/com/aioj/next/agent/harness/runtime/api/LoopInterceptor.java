package com.aioj.next.agent.harness.runtime.api;

import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;

import java.util.List;

/**
 * Where scenario rules enter the loop (plan §3.3); the loop itself knows no
 * domain. Interceptors run in registration order. An interceptor that throws
 * fails the run: they carry policy, so their failures are never skipped.
 */
public interface LoopInterceptor {
    /** The tools offered for this step, for example dropping one the user may not use. */
    default List<ToolDescriptor> filterTools(LoopContext context, List<ToolDescriptor> tools) {
        return tools;
    }

    /** Runs before every model call; may append messages such as a constraint. */
    default void beforeModelCall(LoopContext context, OutgoingMessages messages) {
    }

    /** Sees every tool result, for example to track state a later step depends on. */
    default void afterToolResult(LoopContext context, ToolCallRequest call, ToolOutcome outcome) {
    }

    /** Checks an answer before it ends the run. */
    default FinalDecision beforeFinal(LoopContext context, String content) {
        return FinalDecision.accept();
    }
}
