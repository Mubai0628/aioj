package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolBrokerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolAuditService auditService = mock(ToolAuditService.class);

    @Test
    void successfulCallRunsFullPipelineAndReturnsSanitizedPayload() throws Exception {
        AgentTool tool = fakeTool("context.search_exact", Set.of("AI_CHAT"), schema(), (context, input) ->
                ToolResult.success(null, Map.of("hits", List.of()), List.of(),
                        DataClassification.USER_PRIVATE, null));
        ToolBroker broker = broker(tool);
        ToolResult<String> result = broker.execute(executionContext(Set.of("AI_CHAT")), 42L, 1,
                "call-1", "context.search_exact", "{\"query\":\"二分\"}");

        assertThat(result.ok()).isTrue();
        JsonNode payload = objectMapper.readTree(result.data());
        assertThat(payload.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(payload.path("instructionAllowed").asBoolean()).isFalse();
        assertThat(payload.path("data").path("hits").isArray()).isTrue();
        verify(auditService).record(any(), anyLong(), anyInt(), any(), anyString(), anyString(),
                anyString(), any(), org.mockito.ArgumentMatchers.eq(ToolStatus.SUCCESS), any(), anyString(), anyLong(), isNull());
    }

    @Test
    void schemaViolationIsReturnedAsDataNotThrown() throws Exception {
        AgentTool tool = fakeTool("context.search_exact", Set.of("AI_CHAT"), schema(), (context, input) -> {
            throw new AssertionError("must not execute on schema violation");
        });
        ToolBroker broker = broker(tool);
        ToolResult<String> result = broker.execute(executionContext(Set.of("AI_CHAT")), 42L, 1,
                "call-2", "context.search_exact", "{\"unexpected\":1}");

        assertThat(result.ok()).isFalse();
        assertThat(result.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
        assertThat(objectMapper.readTree(result.data()).path("errorCode").asText())
                .isEqualTo("ARGUMENTS_SCHEMA_VIOLATION");
    }

    @Test
    void missingScopeIsDeniedBeforeExecution() throws Exception {
        AgentTool tool = fakeTool("context.search_exact", Set.of("AI_CHAT"), schema(), (context, input) -> {
            throw new AssertionError("must not execute when unauthorized");
        });
        ToolBroker broker = broker(tool);
        ToolResult<String> result = broker.execute(executionContext(Set.of()), 42L, 1,
                "call-3", "context.search_exact", "{\"query\":\"x\"}");

        assertThat(result.status()).isEqualTo(ToolStatus.POLICY_DENIED);
        assertThat(objectMapper.readTree(result.data()).path("errorCode").asText()).isEqualTo("MISSING_SCOPE");
    }

    @Test
    void unknownToolReturnsNotFound() throws Exception {
        ToolBroker broker = broker();
        ToolResult<String> result = broker.execute(executionContext(Set.of("AI_CHAT")), 42L, 1,
                "call-4", "context.missing", "{}");
        assertThat(result.status()).isEqualTo(ToolStatus.NOT_FOUND);
    }

    @Test
    void toolExceptionBecomesExecutionErrorPayload() throws Exception {
        AgentTool tool = fakeTool("context.search_exact", Set.of("AI_CHAT"), schema(), (context, input) -> {
            throw new IllegalStateException("boom");
        });
        ToolBroker broker = broker(tool);
        ToolResult<String> result = broker.execute(executionContext(Set.of("AI_CHAT")), 42L, 1,
                "call-5", "context.search_exact", "{\"query\":\"x\"}");
        assertThat(result.status()).isEqualTo(ToolStatus.EXECUTION_ERROR);
        JsonNode payload = objectMapper.readTree(result.data());
        assertThat(payload.path("errorCode").asText()).isEqualTo("TOOL_EXECUTION_ERROR");
    }

    @Test
    void authorizedExecutionIsPreAuditedAsRunningAndThenCompleted() throws Exception {
        ToolAuditService.AuditHandle handle = new ToolAuditService.AuditHandle(99L);
        when(auditService.start(any(), anyLong(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(handle);
        AgentTool tool = fakeTool("context.search_exact", Set.of("AI_CHAT"), schema(), (context, input) ->
                ToolResult.success(null, Map.of("hits", List.of()), List.of(),
                        DataClassification.USER_PRIVATE, null));

        ToolResult<String> result = broker(tool).execute(executionContext(Set.of("AI_CHAT")), 42L, 1,
                "call-audit", "context.search_exact", "{\"query\":\"x\"}");

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        verify(auditService).start(any(), anyLong(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString());
        verify(auditService).complete(org.mockito.ArgumentMatchers.eq(handle),
                org.mockito.ArgumentMatchers.eq(ToolStatus.SUCCESS), any(), anyString(), anyLong(),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void descriptorTimeoutInterruptsToolAndReturnsStructuredTimeout() throws Exception {
        JsonNode schema = schema();
        ToolDescriptor descriptor = new ToolDescriptor(
                "context.search_exact", "1.0.0", "slow tool", schema,
                ToolRiskLevel.LOW, true, true, Set.of("AI_CHAT"),
                Set.of(DataClassification.USER_PRIVATE), 1000, Duration.ofMillis(30), ToolAuditLevel.FULL);
        AgentTool slowTool = new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.success(null, Map.of(), List.of(), DataClassification.USER_PRIVATE, null);
            }
        };

        ToolResult<String> result = broker(slowTool).execute(executionContext(Set.of("AI_CHAT")), 42L, 1,
                "call-timeout", "context.search_exact", "{\"query\":\"x\"}");

        assertThat(result.status()).isEqualTo(ToolStatus.TIMEOUT);
        assertThat(objectMapper.readTree(result.data()).path("errorCode").asText())
                .isEqualTo("TOOL_EXECUTION_TIMEOUT");
    }

    @Test
    void cancelRunInterruptsInFlightTool() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AgentTool tool = fakeTool("context.search_exact", Set.of("AI_CHAT"), schema(), (context, input) -> {
            entered.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success(null, Map.of(), List.of(), DataClassification.USER_PRIVATE, null);
        });
        ToolBroker broker = broker(tool);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult<String>> future = caller.submit(() -> broker.execute(
                    executionContext(Set.of("AI_CHAT")), 77L, 1, "call-cancel",
                    "context.search_exact", "{\"query\":\"x\"}"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(broker.cancelRun(77L)).isEqualTo(1);
            assertThat(future.get(1, TimeUnit.SECONDS).status()).isEqualTo(ToolStatus.CANCELLED);
        } finally {
            caller.shutdownNow();
        }
    }

    private ToolBroker broker(AgentTool... tools) {
        return new ToolBroker(new ToolRegistry(List.of(tools)), new ToolAuthorizationService(),
                new ToolResultSanitizer(objectMapper), auditService, objectMapper);
    }

    private ToolExecutionContext executionContext(Set<String> scopes) {
        return new ToolExecutionContext(7L, "c1", "t1", 1L, "ps-1", scopes, Instant.now(), "trace-1");
    }

    private JsonNode schema() throws Exception {
        return objectMapper.readTree("""
                {"type":"object","additionalProperties":false,
                 "required":["query"],
                 "properties":{"query":{"type":"string","minLength":1}}}
                """);
    }

    private interface ToolBehavior {
        ToolResult<Object> run(ToolExecutionContext context, JsonNode input);
    }

    private AgentTool fakeTool(String name, Set<String> scopes, JsonNode schema, ToolBehavior behavior) {
        ToolDescriptor descriptor = new ToolDescriptor(
                name, "1.0.0", "fake tool", schema,
                ToolRiskLevel.LOW, true, true, scopes,
                Set.of(DataClassification.USER_PRIVATE), 1000, Duration.ofSeconds(2), ToolAuditLevel.FULL);
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
                return behavior.run(context, input);
            }
        };
    }
}
