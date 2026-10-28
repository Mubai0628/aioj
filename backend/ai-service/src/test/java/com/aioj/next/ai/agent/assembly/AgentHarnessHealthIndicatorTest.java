package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.ToolCatalog;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §6.3 / A2.4: one tool whose descriptor breaks the rules is left out of the catalog; the
 * scenario that needs it reports DEGRADED on the health endpoint, the other one stays fine.
 */
class AgentHarnessHealthIndicatorTest {
    private static final Map<String, List<String>> REQUIRED = Map.of(
            "tutor", List.of("context.fetch_sources"),
            "problem_draft", List.of("draft.finalize"));

    @Test
    void allRequiredToolsPresentIsUp() {
        Health health = new AgentHarnessHealthIndicator(catalog(tool("context.fetch_sources", OK_SCHEMA),
                tool("draft.finalize", OK_SCHEMA)), REQUIRED).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("rejectedTools")).asList().isEmpty();
    }

    @Test
    void aViolatingToolIsLeftOutAndItsScenarioIsDegraded() {
        // draft.finalize asks the model for a userId: identity fields are server-owned, so it is rejected.
        Health health = new AgentHarnessHealthIndicator(catalog(tool("context.fetch_sources", OK_SCHEMA),
                tool("draft.finalize", "{\"type\":\"object\",\"properties\":{\"userId\":{\"type\":\"integer\"}}}")),
                REQUIRED).health();

        assertThat(health.getStatus()).isEqualTo(AgentHarnessHealthIndicator.DEGRADED);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> scenarios = (Map<String, Map<String, Object>>) health.getDetails().get("scenarios");
        assertThat(scenarios.get("problem_draft").get("missing")).isEqualTo(List.of("draft.finalize"));
        assertThat(scenarios.get("tutor").get("missing")).isEqualTo(List.of());
        assertThat(health.getDetails().get("rejectedTools").toString()).contains("draft.finalize");
    }

    @Test
    void theScenariosOfTheServiceNameRealTools() {
        assertThat(AgentScenarios.REQUIRED_TOOLS).containsOnlyKeys(AgentScenarios.TUTOR, AgentScenarios.PROBLEM_DRAFT);
        List<String> all = new ArrayList<>();
        AgentScenarios.REQUIRED_TOOLS.values().forEach(all::addAll);
        assertThat(all).doesNotHaveDuplicates().allMatch(name -> name.matches("[a-z]+(\\.[a-z_]+)+"));
    }

    private static final String OK_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private static ToolCatalog catalog(AgentTool... tools) {
        return HarnessToolConfiguration.catalog(List.of(tools));
    }

    private static AgentTool tool(String name, String schema) {
        JsonNode parsed = new ObjectMapper().readTree(schema);
        return new AgentTool() {
            @Override
            public com.aioj.next.agent.harness.tool.api.ToolDescriptor descriptor() {
                return new com.aioj.next.agent.harness.tool.api.ToolDescriptor(name, "1.0.0", "test tool " + name,
                        parsed, Set.of("AI_CHAT"), 500, Duration.ofSeconds(5), "fetch",
                        com.aioj.next.agent.harness.tool.api.ExecutionClass.READ_ONLY,
                        com.aioj.next.agent.harness.tool.api.FailurePolicy.FAIL_OPEN,
                        com.aioj.next.agent.harness.tool.api.ToolAuditLevel.FULL);
            }

            @Override
            public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
                return ToolResult.success(null, Map.of(), List.of(), null, null);
            }
        };
    }
}
