package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.ToolAuditLevel;
import com.aioj.next.ai.agent.tool.ToolAuditService;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.mapper.ProblemDraftWorkspaceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HarnessToolConfigurationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HarnessToolConfiguration.class)
            .withBean(ProblemDraftWorkspaceMapper.class, () -> mock(ProblemDraftWorkspaceMapper.class))
            .withBean(ToolAuditService.class, () -> mock(ToolAuditService.class))
            .withBean(AiProperties.class, AiProperties::new)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void theToolBeansBecomeTheHarnessCatalog() {
        runner.withBean("search", AgentTool.class, () -> BridgedToolTest.fake("context.search_exact", true,
                        (context, input) -> null))
                .withBean("propose", AgentTool.class, () -> BridgedToolTest.fake("memory.propose_candidate", false,
                        (context, input) -> null))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ToolRuntime runtime = context.getBean(ToolRuntime.class);
                    assertThat(runtime.visibleTools(Set.of("AI_CHAT")))
                            .extracting(ToolDescriptor::name, ToolDescriptor::executionClass)
                            .containsExactly(
                                    org.assertj.core.groups.Tuple.tuple("context.search_exact", ExecutionClass.READ_ONLY),
                                    org.assertj.core.groups.Tuple.tuple("memory.propose_candidate",
                                            ExecutionClass.WRITE_CANDIDATE));
                });
    }

    /** One broken tool costs that tool, not the service (A2.4): the old registry refused to start instead. */
    @Test
    void aToolAskingForAServerOwnedIdIsLeftOut() {
        runner.withBean("search", AgentTool.class, () -> BridgedToolTest.fake("context.search_exact", true,
                        (context, input) -> null))
                .withBean("broken", AgentTool.class, HarnessToolConfigurationTest::toolAskingForADraftId)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ToolRuntime.class).visibleTools(Set.of("AI_CHAT")))
                            .extracting(ToolDescriptor::name).containsExactly("context.search_exact");
                });
    }

    private static AgentTool toolAskingForADraftId() {
        JsonNode schema = JSON.readTree("""
                {"type":"object","additionalProperties":false,"required":["draftId"],
                 "properties":{"draftId":{"type":"string"}}}
                """);
        com.aioj.next.ai.agent.tool.ToolDescriptor descriptor = new com.aioj.next.ai.agent.tool.ToolDescriptor(
                "draft.state.peek", "1.0.0", "broken tool", schema, ToolRiskLevel.LOW, true, true, Set.of("AI_CHAT"),
                Set.of(DataClassification.USER_PRIVATE), 100, Duration.ofSeconds(1), ToolAuditLevel.FULL);
        return new AgentTool() {
            @Override
            public com.aioj.next.ai.agent.tool.ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
                return null;
            }
        };
    }
}
