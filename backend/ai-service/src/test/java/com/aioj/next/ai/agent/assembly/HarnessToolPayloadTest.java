package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.SourceRef;
import com.aioj.next.ai.agent.tool.ToolAuditService;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.mapper.ProblemDraftWorkspaceMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.error.UserErrorFeedback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The envelope the model gets for a tool call through the Harness pipeline. It was proven
 * byte for byte equal to the old broker's when the tools moved (T1.5 step 2); this pins
 * its shape: data is never instructions, errors keep their codes, and an oversized result
 * is cut to the descriptor's token budget.
 */
class HarnessToolPayloadTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private ToolRuntime runtime;

    @AfterEach
    void close() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void aSuccessCarriesSourcesClassificationAndTrustInAFixedOrder() {
        JsonNode payload = payload(tool((context, input) -> new ToolResult<>("call-1", ToolStatus.SUCCESS,
                Map.of("excerpt", "pre[r] ^ pre[l-1]"), List.of(new SourceRef("message", "msg-9")),
                DataClassification.USER_PRIVATE, TrustLevel.USER_PROVIDED, null, false, "next-1", null,
                List.of("one hit"), null, null)), Set.of("AI_CHAT"), "{\"query\":\"xor\"}");

        assertThat(fieldNames(payload)).containsExactly("status", "data", "sources", "classification", "trustLevel",
                "truncated", "nextCursor", "warnings", "instructionAllowed");
        assertThat(payload.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(payload.path("data").path("excerpt").asString()).isEqualTo("pre[r] ^ pre[l-1]");
        assertThat(payload.path("sources").get(0).path("id").asString()).isEqualTo("msg-9");
        assertThat(payload.path("classification").asString()).isEqualTo("USER_PRIVATE");
        assertThat(payload.path("trustLevel").asString()).isEqualTo("USER_PROVIDED");
        assertThat(payload.path("instructionAllowed").asBoolean(true)).isFalse();
    }

    @Test
    void aResultOverItsTokenBudgetIsCutAsText() {
        JsonNode payload = payload(tool((context, input) -> ToolResult.success("call-1", "x".repeat(5000), List.of(),
                DataClassification.USER_PRIVATE, TrustLevel.SERVER_AUTHORITATIVE)), Set.of("AI_CHAT"),
                "{\"query\":\"xor\"}");

        assertThat(payload.path("truncated").asBoolean()).isTrue();
        assertThat(payload.path("dataTruncatedAsText").asBoolean()).isTrue();
        assertThat(payload.path("data").asString()).hasSize(4000 + "…[truncated]".length()).endsWith("…[truncated]");
        assertThat(payload.path("warnings").get(0).asString()).contains("truncated to fit the token budget");
    }

    @Test
    void aDomainExceptionKeepsItsCodeAndSaysItsKeysText() {
        JsonNode payload = payload(tool((context, input) -> {
            throw new DomainException(ErrorCode.NOT_FOUND, "Submission not found");
        }), Set.of("AI_CHAT"), "{\"query\":\"xor\"}");

        assertThat(payload.path("status").asString()).isEqualTo("EXECUTION_ERROR");
        assertThat(payload.path("errorCode").asString()).isEqualTo("DOMAIN_NOT_FOUND");
        assertThat(payload.path("errorMessage").asString())
                .isEqualTo(UserErrorFeedback.ofKey("submission.notFound").message());
    }

    @Test
    void badArgumentsAndMissingScopesComeBackAsData() {
        AgentTool tool = tool((context, input) -> {
            throw new AssertionError("must not run");
        });

        assertThat(payload(tool, Set.of("AI_CHAT"), "{\"other\":1}").path("errorCode").asString())
                .isEqualTo("ARGUMENTS_SCHEMA_VIOLATION");
        assertThat(payload(tool, Set.of("AI_CHAT"), "{not json").path("errorCode").asString())
                .isEqualTo("ARGUMENTS_NOT_JSON");
        assertThat(payload(tool, Set.of(), "{\"query\":\"xor\"}").path("errorCode").asString())
                .isEqualTo("MISSING_SCOPE");
    }

    private JsonNode payload(AgentTool tool, Set<String> scopes, String arguments) {
        if (runtime != null) {
            runtime.close();
        }
        runtime = HarnessToolConfiguration.create(List.of(tool), mock(ProblemDraftWorkspaceMapper.class),
                mock(ToolAuditService.class), new AiProperties().getCapacity(), mapper);
        ToolOutcome outcome = runtime.execute(new ToolExecutionContext("5", "7", "c1", "t1", "ps-1", scopes,
                        Instant.now(), "trace-1", Map.of("agentRunId", "5"), Map.of(BridgedTool.TURN_SEQ, 1L)), 1,
                new ToolCallRequest("call-1", "context.search_exact", arguments), null);
        return mapper.readTree(outcome.modelPayload());
    }

    private static AgentTool tool(BridgedToolTest.Behavior behavior) {
        return BridgedToolTest.fake("context.search_exact", true, behavior);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
