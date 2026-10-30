package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.ToolAuditSink;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Puts every tool call on {@code aioj.ai.tool.calls} (T2.5) by tool, execution class and outcome,
 * then leaves the audit to the wrapped sink. A tool name the model made up counts as {@code unknown},
 * so the model cannot grow the series; no user, conversation or problem goes into a tag.
 */
public final class ToolCallMetrics implements ToolAuditSink {
    public static final String NAME = "aioj.ai.tool.calls";
    private static final String UNKNOWN = "unknown";

    private final ToolAuditSink delegate;
    private final MeterRegistry registry;
    private final Map<String, ToolDescriptor> descriptors;

    /** @param registry null records nothing; @param descriptors the catalog's tools by name */
    public ToolCallMetrics(ToolAuditSink delegate, MeterRegistry registry, Map<String, ToolDescriptor> descriptors) {
        this.delegate = delegate;
        this.registry = registry;
        this.descriptors = Map.copyOf(descriptors);
    }

    @Override
    public Object start(ToolExecutionContext context, int callSeq, ToolDescriptor descriptor, ToolCallRequest call,
                        String decisionId) {
        return delegate.start(context, callSeq, descriptor, call, decisionId);
    }

    @Override
    public void complete(Object handle, ToolOutcome outcome) {
        try {
            delegate.complete(handle, outcome);
        } finally {
            record(outcome);
        }
    }

    @Override
    public void rejected(ToolExecutionContext context, int callSeq, ToolCallRequest call, ToolOutcome outcome) {
        try {
            delegate.rejected(context, callSeq, call, outcome);
        } finally {
            record(outcome);
        }
    }

    private void record(ToolOutcome outcome) {
        if (registry == null || outcome == null) {
            return;
        }
        try {
            ToolDescriptor descriptor = outcome.toolName() == null ? null : descriptors.get(outcome.toolName());
            Timer.builder(NAME)
                    .description("Agent tool calls by tool, execution class and outcome")
                    .tag("tool", descriptor == null ? UNKNOWN : descriptor.name())
                    .tag("class", descriptor == null || descriptor.executionClass() == null
                            ? UNKNOWN : descriptor.executionClass().name())
                    .tag("outcome", outcome.result() == null || outcome.result().status() == null
                            ? UNKNOWN : outcome.result().status().name())
                    .register(registry)
                    .record(Math.max(0L, outcome.durationMillis()), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // Metrics are best effort; the call and its audit row stand.
        }
    }
}
