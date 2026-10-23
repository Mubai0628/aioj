package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.LoopContext;
import com.aioj.next.agent.harness.runtime.api.LoopInterceptor;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.ai.agent.policy.ContestPolicyView;

import java.util.List;

/**
 * C5 (frozen): problem.search exists only for contest participants, because its search
 * space is the running-contest snapshot set. Non-participants never see the tool.
 */
final class ContestToolFilter implements LoopInterceptor {
    static final String PROBLEM_SEARCH = "problem.search";

    private final boolean participant;

    ContestToolFilter(ContestPolicyView contestPolicy) {
        this.participant = contestPolicy != null && contestPolicy.isParticipant();
    }

    @Override
    public List<ToolDescriptor> filterTools(LoopContext context, List<ToolDescriptor> tools) {
        return participant ? tools : tools.stream().filter(tool -> !PROBLEM_SEARCH.equals(tool.name())).toList();
    }
}
