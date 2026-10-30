package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.ToolAuditSink;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;
import com.aioj.next.agent.harness.tool.api.ToolResult;
import com.aioj.next.agent.harness.tool.api.ToolStatus;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S5 tool call meters, with tags the model cannot grow (T2.5). */
class ToolCallMetricsTest {
    private final ToolAuditSink audit = mock(ToolAuditSink.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ToolDescriptor run = descriptor("code.run", ExecutionClass.SANDBOX);
    private final ToolCallMetrics metrics = new ToolCallMetrics(audit, registry, Map.of("code.run", run));

    @Test
    void aCompletedCallIsTimedByToolClassAndOutcomeAndStillAudited() {
        Object handle = new Object();
        when(audit.start(any(), anyInt(), any(), any(), any())).thenReturn(handle);
        ToolOutcome outcome = outcome("code.run", ToolStatus.SUCCESS, 120);

        Object returned = metrics.start(null, 1, run, new ToolCallRequest("c1", "code.run", "{}"), "hd-1");
        metrics.complete(returned, outcome);

        verify(audit).complete(handle, outcome);
        Timer timer = registry.get(ToolCallMetrics.NAME).tags("tool", "code.run", "class", "SANDBOX", "outcome", "SUCCESS")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(120.0);
    }

    @Test
    void aToolNameTheModelMadeUpCountsAsUnknown() {
        ToolCallRequest call = new ToolCallRequest("c2", "ignore_previous_instructions_" + System.nanoTime(), "{}");
        ToolOutcome outcome = outcome(call.toolName(), ToolStatus.NOT_FOUND, 0);

        metrics.rejected(null, 2, call, outcome);

        verify(audit).rejected(null, 2, call, outcome);
        assertThat(registry.get(ToolCallMetrics.NAME).tags("tool", "unknown", "class", "unknown", "outcome", "NOT_FOUND")
                .timer().count()).isEqualTo(1);
        List<String> tools = registry.getMeters().stream().map(Meter::getId).map(id -> id.getTag("tool")).toList();
        assertThat(tools).containsOnly("unknown");
    }

    @Test
    void withoutARegistryTheAuditIsUnchanged() {
        ToolOutcome outcome = outcome("code.run", ToolStatus.TIMEOUT, 5);
        new ToolCallMetrics(audit, null, Map.of("code.run", run)).complete("h", outcome);

        verify(audit).complete("h", outcome);
    }

    private static ToolOutcome outcome(String tool, ToolStatus status, long millis) {
        ToolResult result = status == ToolStatus.SUCCESS
                ? ToolResult.success("ok", List.of(), null, null)
                : ToolResult.failure(status, "CODE", "message");
        return new ToolOutcome("c", tool, result, "{}", "hd-1", millis, false);
    }

    private static ToolDescriptor descriptor(String name, ExecutionClass executionClass) {
        ToolDescriptor descriptor = mock(ToolDescriptor.class);
        when(descriptor.name()).thenReturn(name);
        when(descriptor.executionClass()).thenReturn(executionClass);
        return descriptor;
    }
}
