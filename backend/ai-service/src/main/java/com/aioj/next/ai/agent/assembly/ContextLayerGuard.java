package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.HarnessMessage;
import com.aioj.next.agent.harness.runtime.api.LoopContext;
import com.aioj.next.agent.harness.runtime.api.LoopInterceptor;
import com.aioj.next.agent.harness.runtime.api.OutgoingMessages;
import com.aioj.next.ai.agent.guard.ContextFingerprintGuard;
import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.policy.ContestPolicyView;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * L3 second layer (§5.3, P3-4) for one run: before every model call the assembled
 * messages are fingerprinted again, and the rules of newly matched contest problems are
 * appended as one system message that stays for the rest of the run. Problems the
 * message layer already constrained are not injected twice. The merged verdict is what
 * the L4 output guard reads (P3-5).
 */
final class ContextLayerGuard implements LoopInterceptor {
    private final ContextFingerprintGuard guard;
    private final String turnId;
    private final long userId;
    private final String conversationId;
    private final ContestPolicyView contestPolicy;
    private final Set<Long> constrainedProblemIds = new LinkedHashSet<>();
    private volatile GuardVerdict mergedVerdict;
    private int lastStep;
    private int lastGuardStep;

    ContextLayerGuard(ContextFingerprintGuard guard, String turnId, long userId, String conversationId,
                      ContestPolicyView contestPolicy, GuardVerdict messageLayerVerdict) {
        this.guard = guard;
        this.turnId = turnId;
        this.userId = userId;
        this.conversationId = conversationId;
        this.contestPolicy = contestPolicy;
        this.mergedVerdict = messageLayerVerdict;
        if (messageLayerVerdict != null && messageLayerVerdict.hasMatches()) {
            messageLayerVerdict.matchedProblems().forEach(ref -> {
                if (ref.problemId() != null) {
                    constrainedProblemIds.add(ref.problemId());
                }
            });
        }
    }

    @Override
    public void beforeModelCall(LoopContext context, OutgoingMessages messages) {
        ContextFingerprintGuard.Evaluation evaluation = guard.evaluate(turnId, userId, conversationId, contestPolicy,
                constrainedProblemIds, HarnessMessages.toChat(messages.view()), messages.startingMessageCount(),
                agentStep(context.step()));
        if (evaluation == null) {
            return;
        }
        if (evaluation.injectionText() != null) {
            messages.append(HarnessMessage.system(evaluation.injectionText()));
            constrainedProblemIds.addAll(evaluation.newlyMatchedProblemIds());
        }
        mergedVerdict = mergedVerdict == null ? evaluation.verdict() : mergedVerdict.mergedWith(evaluation.verdict());
    }

    /** The message layer's verdict merged with every match of this run. */
    GuardVerdict mergedVerdict() {
        return mergedVerdict;
    }

    /** A later call in the same loop step is the answer-only call after the budget; it counts as the next step. */
    private int agentStep(int step) {
        int agentStep = step == lastStep ? lastGuardStep + 1 : step;
        lastStep = step;
        lastGuardStep = agentStep;
        return agentStep;
    }
}
