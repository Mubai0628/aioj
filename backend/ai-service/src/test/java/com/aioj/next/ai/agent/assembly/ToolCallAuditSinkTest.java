package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;
import com.aioj.next.agent.harness.tool.api.ToolResult;
import com.aioj.next.agent.harness.tool.api.ToolStatus;
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.tool.ToolAuditService;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolCallAuditSinkTest {
    private final ToolAuditService auditService = mock(ToolAuditService.class);
    private final ToolDescriptor legacy = BridgedToolTest.fake("context.search_exact", true, (c, i) -> null)
            .descriptor();
    private final ToolCallAuditSink sink = new ToolCallAuditSink(auditService, Map.of("context.search_exact", legacy));
    private final ToolCallRequest call = new ToolCallRequest("call-1", "context.search_exact", "{\"query\":\"xor\"}");

    @Test
    void aStartedCallIsTheSameRunningRowAsBefore() {
        ToolAuditService.AuditHandle handle = new ToolAuditService.AuditHandle(42L);
        when(auditService.start(any(), anyLong(), anyInt(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(handle);

        Object started = sink.start(BridgedToolTest.context(), 3, new BridgedTool(BridgedToolTest.fake(
                "context.search_exact", true, (c, i) -> null)).descriptor(), call, "hd-1");

        ArgumentCaptor<com.aioj.next.ai.agent.tool.ToolExecutionContext> context =
                ArgumentCaptor.forClass(com.aioj.next.ai.agent.tool.ToolExecutionContext.class);
        verify(auditService).start(context.capture(), eq(5L), eq(3), eq(legacy), eq("context.search_exact"),
                eq("call-1"), eq("{\"query\":\"xor\"}"), eq("hd-1"));
        assertThat(context.getValue().userId()).isEqualTo(7L);
        assertThat(context.getValue().turnId()).isEqualTo("t1");
        assertThat(started).isSameAs(handle);
    }

    @Test
    void completionCarriesStatusClassificationPayloadAndLatency() {
        ToolAuditService.AuditHandle handle = new ToolAuditService.AuditHandle(42L);

        sink.complete(handle, outcome(new ToolResult(ToolStatus.SUCCESS, "hit", List.of(), "USER_PRIVATE", null,
                false, null, List.of(), null, null)));

        verify(auditService).complete(handle, com.aioj.next.ai.agent.tool.ToolStatus.SUCCESS,
                DataClassification.USER_PRIVATE, "{\"status\":\"SUCCESS\"}", 12L, null);
    }

    @Test
    void withoutAHandleNothingIsCompleted() {
        sink.complete(null, outcome(ToolResult.failure(ToolStatus.TIMEOUT, "TOOL_EXECUTION_TIMEOUT", "late")));

        verifyNoInteractions(auditService);
    }

    @Test
    void aRejectedCallIsOneFinishedRow() {
        sink.rejected(BridgedToolTest.context(), 4, call,
                outcome(ToolResult.failure(ToolStatus.POLICY_DENIED, "MISSING_SCOPE", "denied")));

        verify(auditService).record(any(), eq(5L), eq(4), eq(legacy), eq("context.search_exact"), eq("call-1"),
                eq("{\"query\":\"xor\"}"), eq("hd-1"), eq(com.aioj.next.ai.agent.tool.ToolStatus.POLICY_DENIED),
                isNull(), eq("{\"status\":\"SUCCESS\"}"), eq(12L), eq("MISSING_SCOPE"));
    }

    private ToolOutcome outcome(ToolResult result) {
        return new ToolOutcome("call-1", "context.search_exact", result, "{\"status\":\"SUCCESS\"}", "hd-1", 12L,
                false);
    }
}
