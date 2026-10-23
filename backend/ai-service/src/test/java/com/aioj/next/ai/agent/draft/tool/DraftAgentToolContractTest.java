package com.aioj.next.ai.agent.draft.tool;

import com.aioj.next.agent.harness.tool.api.FailurePolicy;
import com.aioj.next.agent.harness.tool.api.ToolAuditLevel;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;
import com.aioj.next.agent.harness.tool.api.ToolStatus;
import com.aioj.next.ai.agent.assembly.HarnessToolConfiguration;
import com.aioj.next.ai.agent.tool.ToolAuditService;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.mapper.ProblemDraftWorkspaceMapper;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.domain.ProblemDraftTestcaseArtifactService;
import com.aioj.next.ai.domain.draftagent.DraftTestcasePackageAcceptanceClient;
import com.aioj.next.ai.domain.draftagent.ProblemDraftExecutionService;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DraftAgentToolContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProblemDraftWorkspaceService workspaceService = mock(ProblemDraftWorkspaceService.class);
    private final ProblemDraftExecutionService executionService = mock(ProblemDraftExecutionService.class);
    private final DraftSandboxClient sandboxClient = mock(DraftSandboxClient.class);
    private final ProblemDraftTestcaseArtifactService artifactService = mock(ProblemDraftTestcaseArtifactService.class);
    private final DraftTestcasePackageAcceptanceClient acceptanceClient =
            mock(DraftTestcasePackageAcceptanceClient.class);

    @Test
    void exposesExactlyNineWorkspaceBoundToolsWithProtectedAuditContracts() {
        List<AgentTool> tools = tools();

        // The Harness catalog also verifies the supported JSON-Schema subset and forbids
        // model-controlled identity/resource binding fields.
        assertThat(HarnessToolConfiguration.catalog(tools).rejections()).isEmpty();

        assertThat(tools.stream().map(tool -> tool.descriptor().name()).collect(Collectors.toSet()))
                .isEqualTo(Set.of(
                        "draft.state.get",
                        "draft.state.patch",
                        "draft.static.validate",
                        "draft.code.execute",
                        "draft.execution.get",
                        "draft.samples.run",
                        "draft.testcase.generate",
                        "draft.testcase.run_package",
                        "draft.finalize"
                ));
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.descriptor().requiredScopes()).containsExactly("AI_PROBLEM_DRAFT");
            assertThat(tool.descriptor().auditLevel()).isEqualTo(ToolAuditLevel.NO_ARGUMENTS);
            assertThat(tool.descriptor().failurePolicy()).isEqualTo(FailurePolicy.FAIL_OPEN);
            String schema = tool.descriptor().inputSchema().toString();
            assertThat(schema).doesNotContain("workspaceId", "userId", "generationJobId");
        });
    }

    @Test
    void statePatchSchemaRejectsUnknownFieldsAndEnforcesRealArrayAndNumericBounds() throws Exception {
        assertThat(patchSchemaErrors("""
                {"expectedStateVersion":1,"patch":{"workspaceId":"other"}}
                """)).contains("is not allowed");
        assertThat(patchSchemaErrors("""
                {"expectedStateVersion":1,"patch":{"timeLimitMillis":60001}}
                """)).contains("greater than maximum");

        StringBuilder tags = new StringBuilder();
        for (int index = 0; index < 31; index++) {
            if (index > 0) tags.append(',');
            tags.append('"').append(index).append('"');
        }
        assertThat(patchSchemaErrors("{\"expectedStateVersion\":1,\"patch\":{\"tags\":[" + tags + "]}}"))
                .contains("more than maxItems");
    }

    /** The schema errors the Harness pipeline reports for one draft.state.patch call; the tool never runs. */
    private String patchSchemaErrors(String arguments) {
        try (ToolRuntime runtime = HarnessToolConfiguration.create(tools(), mock(ProblemDraftWorkspaceMapper.class),
                mock(ToolAuditService.class), new AiProperties().getCapacity(), mapper)) {
            ToolOutcome outcome = runtime.execute(new ToolExecutionContext("1", "7", "c1", "t1", null,
                    Set.of("AI_PROBLEM_DRAFT"), Instant.now(), "t1", Map.of("workspaceId", "w1"), Map.of()), 1,
                    new ToolCallRequest("call-1", "draft.state.patch", arguments), null);
            assertThat(outcome.result().status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
            return outcome.result().errorMessage();
        }
    }

    private List<AgentTool> tools() {
        return List.of(
                new DraftStateGetTool(workspaceService, mapper),
                new DraftStatePatchTool(workspaceService, mapper),
                new DraftStaticValidateTool(workspaceService, mapper),
                new DraftCodeExecuteTool(workspaceService, executionService, mapper),
                new DraftExecutionGetTool(executionService, mapper),
                new DraftSamplesRunTool(workspaceService, sandboxClient, mapper),
                new DraftTestcaseGenerateTool(workspaceService, sandboxClient, artifactService, mapper),
                new DraftTestcaseRunPackageTool(workspaceService, artifactService, acceptanceClient, mapper),
                new DraftFinalizeTool(workspaceService, mapper)
        );
    }
}
