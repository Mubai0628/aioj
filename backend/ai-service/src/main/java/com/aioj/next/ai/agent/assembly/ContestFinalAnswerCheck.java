package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.FinalDecision;
import com.aioj.next.agent.harness.runtime.api.LoopContext;
import com.aioj.next.agent.harness.runtime.api.LoopInterceptor;
import com.aioj.next.ai.agent.guard.ContestOutputGuard;
import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.policy.PolicySnapshotService;

import java.util.function.Supplier;

/**
 * The final answer of one chat turn (P3-5 L4 and the P3-6 recheck), as a Harness
 * {@code beforeFinal} check. It replaces the second agent run the turn used to start:
 * an intercepted answer is regenerated once inside the same run, so a turn keeps one
 * ai_agent_runs row. Decisions keep their old order:
 * <ol>
 *   <li>a restricted turn's first answer goes through L4; an interception asks for the
 *       one safe regeneration;</li>
 *   <li>the regenerated answer goes through L4 again; still intercepted means the
 *       server's refusal text;</li>
 *   <li>then the policy is read again: a turn that became restricted checks the pending
 *       answer under the new policy (and regenerates once if nothing was regenerated
 *       yet); a turn that became unrestricted delivers the generated answer even after a
 *       refusal.</li>
 * </ol>
 * The one regeneration is shared between L4 and the recheck. Not thread-safe: one
 * instance belongs to one run.
 */
public final class ContestFinalAnswerCheck implements LoopInterceptor, ContextVerdictAware {
    /**
     * Server-authored safe refusal delivered when the one allowed regeneration is still
     * intercepted. Polite, names no policy details.
     */
    public static final String CONTEST_SAFE_REFUSAL_TEXT =
            "抱歉，该内容涉及正在进行中的比赛，我暂时不能提供解答或完整代码。"
                    + "我可以为你提供学习方向上的建议，例如相关算法的思路讲解或同类练习题的练习方法。";

    /** Which attempt produced the answer being checked; usage is attributed to it. */
    public enum Attempt {
        PRIMARY,
        SAFE_REGENERATION,
        RECHECK_REGENERATION
    }

    private final ContestOutputGuard outputGuard;
    private final PolicySnapshotService policySnapshotService;
    private final String turnId;
    private final long userId;
    private final String conversationId;
    private final PolicySnapshotService.PolicySnapshot snapshot;
    private final ContestPolicyView policy;
    private final boolean restrictedTurn;
    private Supplier<GuardVerdict> contextVerdict = () -> null;
    private ContestPolicyView recheckedPolicy;
    private volatile Attempt attempt = Attempt.PRIMARY;
    private boolean regenerationUsed;
    private volatile boolean finalRefusal;
    private volatile boolean pseudoStream;

    public ContestFinalAnswerCheck(ContestOutputGuard outputGuard, PolicySnapshotService policySnapshotService,
                                   String turnId, long userId, String conversationId,
                                   PolicySnapshotService.PolicySnapshot snapshot, ContestPolicyView policy,
                                   boolean restrictedTurn) {
        this.outputGuard = outputGuard;
        this.policySnapshotService = policySnapshotService;
        this.turnId = turnId;
        this.userId = userId;
        this.conversationId = conversationId;
        this.snapshot = snapshot;
        this.policy = policy;
        this.restrictedTurn = restrictedTurn;
        this.pseudoStream = restrictedTurn;
    }

    @Override
    public void useContextVerdict(Supplier<GuardVerdict> verdict) {
        this.contextVerdict = verdict == null ? () -> null : verdict;
    }

    @Override
    public FinalDecision beforeFinal(LoopContext context, String content) {
        return switch (attempt) {
            case PRIMARY -> {
                if (restrictedTurn) {
                    ContestOutputGuard.Verdict verdict = outputGuard.evaluate(turnId, userId, conversationId, content,
                            policy, contextVerdict.get(), false);
                    if (verdict.intercepted()) {
                        yield regenerate(Attempt.SAFE_REGENERATION, verdict.reasonCode());
                    }
                }
                yield afterRecheck(content, false);
            }
            case SAFE_REGENERATION -> {
                ContestOutputGuard.Verdict verdict = outputGuard.evaluate(turnId, userId, conversationId, content,
                        policy, contextVerdict.get(), true);
                yield afterRecheck(content, verdict.intercepted());
            }
            case RECHECK_REGENERATION -> decide(outputGuard.evaluate(turnId, userId, conversationId, content,
                    recheckedPolicy, null, true, ContestOutputGuard.TRIGGER_RECHECK).intercepted());
        };
    }

    /** The attempt whose answer is being or was last checked. */
    public Attempt attempt() {
        return attempt;
    }

    /** Whether the delivered content is the server's refusal text. */
    public boolean finalRefusal() {
        return finalRefusal;
    }

    /** Whether the answer was checked before delivery, so the client gets it replayed, not streamed. */
    public boolean pseudoStream() {
        return pseudoStream;
    }

    /** P3-6: right before delivery, compare the policy with the turn-start snapshot (fail-closed inside). */
    private FinalDecision afterRecheck(String content, boolean refusal) {
        PolicySnapshotService.PolicyRecheck recheck =
                policySnapshotService.recheckBeforeReturn(userId, turnId, conversationId, snapshot);
        if (recheck.changed()) {
            ContestPolicyView rechecked = ContestPolicyView.from(recheck.participation().status(), recheck.statements());
            boolean nowRestricted = rechecked.isParticipant() && !rechecked.constrainedProblems().isEmpty();
            if (nowRestricted) {
                // Restricted mid-generation: the pending answer passes L4 under the NEW policy. The
                // L3 verdict is not used; an answer already refused stays refused.
                pseudoStream = true;
                if (!refusal) {
                    ContestOutputGuard.Verdict race = outputGuard.evaluate(turnId, userId, conversationId, content,
                            rechecked, null, false, ContestOutputGuard.TRIGGER_RECHECK);
                    if (race.intercepted()) {
                        if (!regenerationUsed) {
                            recheckedPolicy = rechecked;
                            return regenerate(Attempt.RECHECK_REGENERATION, race.reasonCode());
                        }
                        refusal = true;
                    }
                }
            } else {
                // Unrestricted mid-generation: there is no basis left for a refusal.
                refusal = false;
            }
        }
        return decide(refusal);
    }

    private FinalDecision regenerate(Attempt next, String reasonCode) {
        regenerationUsed = true;
        attempt = next;
        return FinalDecision.retry(retryDirective(reasonCode));
    }

    private FinalDecision decide(boolean refusal) {
        finalRefusal = refusal;
        return refusal ? FinalDecision.replace(CONTEST_SAFE_REFUSAL_TEXT) : FinalDecision.accept();
    }

    /** Names only the reason category; the intercepted draft is never shown back to the model. */
    static String retryDirective(String reasonCode) {
        return "[Contest Output Guard — server safety check intercepted the previous draft; regenerate]\n"
                + "Your previous draft reply for this turn was intercepted by a server-side contest output "
                + "safety check (reason category: " + reasonCategory(reasonCode) + "). "
                + "The intercepted draft is withheld and must not be restated or quoted.\n"
                + "Regenerate the reply under these rules:\n"
                + "- Private contest problems: refuse to discuss their content, solution, or code; decline politely.\n"
                + "- Public contest problems of a running contest: hints and idea-level guidance only; "
                + "never output complete submittable code.\n"
                + "- Never reveal hidden test data, judging points, or evaluation details.\n"
                + "[/Contest Output Guard]";
    }

    private static String reasonCategory(String reasonCode) {
        return switch (reasonCode == null ? "" : reasonCode) {
            case ContestOutputGuard.REASON_FULL_CODE -> "complete submittable code disclosure";
            case ContestOutputGuard.REASON_PRIVATE_STATEMENT -> "private contest statement leakage";
            case ContestOutputGuard.REASON_HIDDEN_TEST -> "hidden test information leakage";
            case ContestOutputGuard.REASON_GUARD_ERROR -> "output safety check failure (fail-closed)";
            default -> "contest output policy violation";
        };
    }
}
