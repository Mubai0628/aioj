package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.FailurePolicy;
import com.aioj.next.agent.harness.tool.api.ToolAuditLevel;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.policy.ParticipantStatus;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.problem.ProblemVisibility;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContestToolFilterTest {
    private static final List<String> ALL = List.of("problem.search", "problem.fetch_allowed_view",
            "code.compile_check", "code.run_samples", "code.run_custom");

    @Test
    void practiceTurnsKeepTheCodeTools() {
        assertThat(visible(ContestPolicyView.nonParticipant()))
                .containsExactly("problem.fetch_allowed_view", "code.compile_check", "code.run_samples", "code.run_custom");
    }

    @Test
    void aRestrictedTurnHasNoCodeTools() {
        assertThat(visible(participant(ContestAiPolicyMode.STRICT)))
                .containsExactly("problem.search", "problem.fetch_allowed_view");
    }

    @Test
    void aParticipantWithoutConstrainedProblemsMayRunCode() {
        // DISABLED-mode problems are not constrained, so the turn is not restricted (the output protocol's predicate).
        assertThat(visible(participant(ContestAiPolicyMode.DISABLED))).containsExactlyElementsOf(ALL);
    }

    @Test
    void withoutAPolicyViewTheFilterFailsClosed() {
        assertThat(visible(null)).containsExactly("problem.fetch_allowed_view");
    }

    private static ContestPolicyView participant(ContestAiPolicyMode mode) {
        return new ContestPolicyView(ParticipantStatus.PARTICIPANT_ACTIVE, Map.of(11L,
                new ContestPolicyView.ContestProblemPolicy(11L, ProblemVisibility.PUBLIC, mode, null, null, List.of())));
    }

    private static List<String> visible(ContestPolicyView policy) {
        List<ToolDescriptor> tools = ALL.stream().map(ContestToolFilterTest::descriptor).toList();
        return new ContestToolFilter(policy).filterTools(null, tools).stream().map(ToolDescriptor::name).toList();
    }

    private static ToolDescriptor descriptor(String name) {
        return new ToolDescriptor(name, "1.0.0", name, new ObjectMapper().createObjectNode().put("type", "object"),
                Set.of("AI_CHAT"), 100, Duration.ofSeconds(1), "other", ExecutionClass.READ_ONLY, FailurePolicy.FAIL_OPEN,
                ToolAuditLevel.FULL);
    }
}
