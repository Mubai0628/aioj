package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.LoopContext;
import com.aioj.next.agent.harness.runtime.api.LoopInterceptor;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.ai.agent.policy.ContestPolicyView;

import java.util.List;
import java.util.Set;

/**
 * C5 (frozen): problem.search exists only for contest participants, because its search
 * space is the running-contest snapshot set. Non-participants never see the tool.
 *
 * <p>T1.11: the code tools run code for practice only. A restricted turn (a participant with a
 * constrained problem, the predicate the turn's output protocol uses) never sees them, and
 * neither does a run without a policy view: the filter fails closed.</p>
 */
final class ContestToolFilter implements LoopInterceptor {
    static final String PROBLEM_SEARCH = "problem.search";
    static final Set<String> CODE_TOOLS = Set.of("code.compile_check", "code.run_samples", "code.run_custom");

    private final boolean participant;
    private final boolean restricted;

    ContestToolFilter(ContestPolicyView contestPolicy) {
        this.participant = contestPolicy != null && contestPolicy.isParticipant();
        this.restricted = contestPolicy == null
                || (contestPolicy.isParticipant() && !contestPolicy.constrainedProblems().isEmpty());
    }

    @Override
    public List<ToolDescriptor> filterTools(LoopContext context, List<ToolDescriptor> tools) {
        return tools.stream()
                .filter(tool -> participant || !PROBLEM_SEARCH.equals(tool.name()))
                .filter(tool -> !restricted || !CODE_TOOLS.contains(tool.name()))
                .toList();
    }
}
