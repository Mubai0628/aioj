package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.FinalDecision;
import com.aioj.next.agent.harness.runtime.api.LoopContext;
import com.aioj.next.agent.harness.runtime.api.LoopInterceptor;
import com.aioj.next.agent.harness.runtime.api.OutgoingMessages;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Keeps the first exception an interceptor of one run threw. The loop ends the run as
 * an interceptor error either way; the caller then reports the original exception, for
 * example the fail-closed recheck's SERVICE_UNAVAILABLE, exactly as before.
 */
final class InterceptorFailures {
    private volatile RuntimeException first;

    LoopInterceptor watch(LoopInterceptor interceptor) {
        return new LoopInterceptor() {
            @Override
            public List<ToolDescriptor> filterTools(LoopContext context, List<ToolDescriptor> tools) {
                return watched(() -> interceptor.filterTools(context, tools));
            }

            @Override
            public void beforeModelCall(LoopContext context, OutgoingMessages messages) {
                watched(() -> {
                    interceptor.beforeModelCall(context, messages);
                    return null;
                });
            }

            @Override
            public void afterToolResult(LoopContext context, ToolCallRequest call, ToolOutcome outcome) {
                watched(() -> {
                    interceptor.afterToolResult(context, call, outcome);
                    return null;
                });
            }

            @Override
            public FinalDecision beforeFinal(LoopContext context, String content) {
                return watched(() -> interceptor.beforeFinal(context, content));
            }
        };
    }

    Optional<RuntimeException> first() {
        return Optional.ofNullable(first);
    }

    private <T> T watched(Supplier<T> hook) {
        try {
            return hook.get();
        } catch (RuntimeException ex) {
            if (first == null) {
                first = ex;
            }
            throw ex;
        }
    }
}
