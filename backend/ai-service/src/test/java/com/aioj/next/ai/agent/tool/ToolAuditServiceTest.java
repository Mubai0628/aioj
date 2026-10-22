package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.persistence.entity.AiToolCallEntity;
import com.aioj.next.ai.persistence.mapper.AiToolCallMapper;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolAuditServiceTest {

    private final AiToolCallMapper mapper = mock(AiToolCallMapper.class);
    private final ToolAuditService service = new ToolAuditService(mapper, new ObjectMapper());

    @Test
    void runningRowIsInsertedBeforeResultAndSameRowIsCompleted() throws Exception {
        ToolExecutionContext context = new ToolExecutionContext(7L, "conversation", "turn", 1L,
                "policy", Set.of("AI_CHAT"), Instant.now(), "trace");
        ToolDescriptor descriptor = new ToolDescriptor("context.search_exact", "1.0.0", "search",
                new ObjectMapper().readTree("{\"type\":\"object\"}"), ToolRiskLevel.LOW,
                true, true, Set.of("AI_CHAT"), Set.of(DataClassification.USER_PRIVATE),
                100, Duration.ofSeconds(1), ToolAuditLevel.FULL);

        ToolAuditService.AuditHandle handle = service.start(context, 42L, 1, descriptor,
                descriptor.name(), "call-1", "{\"token\":\"secret\"}", "decision-1");
        service.complete(handle, ToolStatus.SUCCESS, DataClassification.USER_PRIVATE,
                "{\"status\":\"SUCCESS\"}", 12L, null);

        ArgumentCaptor<AiToolCallEntity> inserted = ArgumentCaptor.forClass(AiToolCallEntity.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo("RUNNING");
        assertThat(inserted.getValue().getArgumentsRedacted()).doesNotContain("secret").contains("***");

        ArgumentCaptor<AiToolCallEntity> completed = ArgumentCaptor.forClass(AiToolCallEntity.class);
        verify(mapper).updateById(completed.capture());
        assertThat(completed.getValue().getId()).isEqualTo(inserted.getValue().getId());
        assertThat(completed.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(completed.getValue().getResultHash()).hasSize(64);
        assertThat(completed.getValue().getLatencyMs()).isEqualTo(12);
    }

    @Test
    void auditStoresOnlyOpaqueReferenceAndWhitelistedMetricsFromExecutionOutput() throws Exception {
        ToolExecutionContext context = new ToolExecutionContext(7L, "conversation", "turn", 1L,
                "policy", Set.of("AI_PROBLEM_DRAFT"), Instant.now(), "trace");
        ToolDescriptor descriptor = new ToolDescriptor("draft.execution.get", "1.0.0", "result",
                new ObjectMapper().readTree("{\"type\":\"object\"}"), ToolRiskLevel.LOW,
                true, true, Set.of("AI_PROBLEM_DRAFT"), Set.of(DataClassification.STAFF_ONLY),
                100, Duration.ofSeconds(1), ToolAuditLevel.NO_ARGUMENTS);
        ToolAuditService.AuditHandle handle = service.start(context, 42L, 1, descriptor,
                descriptor.name(), "call-2", "{\"executionId\":\"opaque-1\"}", "decision-2");

        service.complete(handle, ToolStatus.SUCCESS, DataClassification.STAFF_ONLY, """
                {"status":"SUCCESS","data":{"executionId":"opaque-1","status":"ACCEPTED",
                "timeMillis":12,"memoryKb":2048,"stdout":"hidden-output","stderr":"private-error"}}
                """, 15L, null);

        ArgumentCaptor<AiToolCallEntity> completed = ArgumentCaptor.forClass(AiToolCallEntity.class);
        verify(mapper).updateById(completed.capture());
        AiToolCallEntity row = completed.getValue();
        assertThat(row.getResourceRefType()).isEqualTo("DRAFT_EXECUTION");
        assertThat(row.getResourceRefId()).isEqualTo("opaque-1");
        assertThat(row.getResourceMetricsJson()).contains("timeMillis", "memoryKb")
                .doesNotContain("stdout", "stderr", "hidden-output", "private-error");
        assertThat(row.getSafeSummaryJson()).contains("ACCEPTED")
                .doesNotContain("hidden-output", "private-error");
    }
}
