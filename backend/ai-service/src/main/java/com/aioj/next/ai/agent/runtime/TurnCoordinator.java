package com.aioj.next.ai.agent.runtime;

import com.aioj.next.agent.harness.runtime.api.CancellationToken;
import com.aioj.next.ai.agent.AgentChatFacade.TurnHandle;
import com.aioj.next.ai.agent.AgentChatFacade.TurnResult;
import com.aioj.next.ai.agent.assembly.ContestFinalAnswerCheck;
import com.aioj.next.ai.agent.context.BootstrapContextBuilder;
import com.aioj.next.ai.agent.context.ContextSectionType;
import com.aioj.next.ai.agent.digest.TurnDigestInput;
import com.aioj.next.ai.agent.digest.TurnDigestService;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.agent.runtime.ProviderUsage;
import com.aioj.next.ai.agent.policy.ContestParticipationService;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.agent.harness.context.api.ContextPolicyException;
import com.aioj.next.ai.agent.policy.GuardDecision;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.policy.GuardLayer;
import com.aioj.next.ai.agent.policy.ParticipantStatus;
import com.aioj.next.ai.agent.policy.PolicySnapshotService;
import com.aioj.next.ai.agent.guard.ContestOutputGuard;
import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.guard.ProblemFingerprintMatcher;
import com.aioj.next.ai.agent.telemetry.ContestAiAssistanceTelemetryService;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.contest.RunningContestParticipation;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import com.aioj.next.ai.agent.understanding.TurnUnderstandingService;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiChatContext;
import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.AiTurnService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turn mechanics for the Agent Core V3 pipeline (design doc §2/§7): idempotent
 * begin/resume on ai_turns, quota accounting, message lifecycle, in-flight
 * dedup, and timeout finalization — the same outer contract the legacy chat
 * pipeline honored (deleted in plan T1.1), but without its hooks (context
 * service, contest guard, memory, response policy guard). Those capabilities
 * return as V3 components in their own phases.
 */
@Service
public class TurnCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TurnCoordinator.class);
    static final String REASON_CONTEXT_POLICY_FAILED = "CONTEXT_POLICY_FAILED";

    /**
     * P3-5: server-authored safe refusal persisted as the assistant content when the
     * one allowed regeneration is still intercepted. Polite, names no policy details.
     */
    static final String CONTEST_SAFE_REFUSAL_TEXT = ContestFinalAnswerCheck.CONTEST_SAFE_REFUSAL_TEXT;

    private final AgentRuntime agentRuntime;
    private final BootstrapContextBuilder bootstrapBuilder;
    private final PolicySnapshotService policySnapshotService;
    private final ContestParticipationService participationService;
    private final ProblemFingerprintMatcher fingerprintMatcher;
    private final GuardDecisionRecorder guardDecisionRecorder;
    private final ContestOutputGuard contestOutputGuard;
    private final AiQuotaService aiQuotaService;
    private final AiConversationService aiConversationService;
    private final AiAssistantResponseNormalizer responseNormalizer;
    private final AiTurnService aiTurnService;
    private final TurnDigestService turnDigestService;
    private final TurnUnderstandingService turnUnderstandingService;
    private final AiProperties properties;
    private final Executor executor;
    private final long turnTimeoutMs;
    /**
     * Optional observation-only contest assistance ledger. Setter injection keeps the
     * package-visible constructor stable for the focused coordinator tests while
     * ensuring no telemetry failure can affect the V3 Agent outcome.
     */
    private ContestAiAssistanceTelemetryService contestAssistanceTelemetryService;
    /**
     * In-process registry of in-flight turns so a duplicate submission or an SSE reconnect can
     * attach to the running turn instead of regenerating. The ai_turns row remains the
     * consistency source; a registry miss falls back to polling the turn row.
     */
    private final Map<String, CompletableFuture<TurnResult>> inFlightTurns = new ConcurrentHashMap<>();

    @Autowired
    public TurnCoordinator(
            AgentRuntime agentRuntime,
            BootstrapContextBuilder bootstrapBuilder,
            PolicySnapshotService policySnapshotService,
            ContestParticipationService participationService,
            ProblemFingerprintMatcher fingerprintMatcher,
            GuardDecisionRecorder guardDecisionRecorder,
            ContestOutputGuard contestOutputGuard,
            AiQuotaService aiQuotaService,
            AiConversationService aiConversationService,
            AiAssistantResponseNormalizer responseNormalizer,
            AiTurnService aiTurnService,
            TurnDigestService turnDigestService,
            TurnUnderstandingService turnUnderstandingService,
            AiProperties properties,
            @Qualifier("aiChatTurnExecutor") Executor executor
    ) {
        this(agentRuntime, bootstrapBuilder, policySnapshotService, participationService,
                fingerprintMatcher, guardDecisionRecorder, contestOutputGuard, aiQuotaService, aiConversationService,
                responseNormalizer, aiTurnService, turnDigestService, turnUnderstandingService, properties, executor,
                properties.getAgentCore().getTurnTimeoutMs());
    }

    TurnCoordinator(
            AgentRuntime agentRuntime,
            BootstrapContextBuilder bootstrapBuilder,
            PolicySnapshotService policySnapshotService,
            ContestParticipationService participationService,
            ProblemFingerprintMatcher fingerprintMatcher,
            GuardDecisionRecorder guardDecisionRecorder,
            ContestOutputGuard contestOutputGuard,
            AiQuotaService aiQuotaService,
            AiConversationService aiConversationService,
            AiAssistantResponseNormalizer responseNormalizer,
            AiTurnService aiTurnService,
            TurnDigestService turnDigestService,
            TurnUnderstandingService turnUnderstandingService,
            AiProperties properties,
            Executor executor,
            long turnTimeoutMs
    ) {
        this.agentRuntime = agentRuntime;
        this.bootstrapBuilder = bootstrapBuilder;
        this.policySnapshotService = policySnapshotService;
        this.participationService = participationService;
        this.fingerprintMatcher = fingerprintMatcher;
        this.guardDecisionRecorder = guardDecisionRecorder;
        this.contestOutputGuard = contestOutputGuard;
        this.aiQuotaService = aiQuotaService;
        this.aiConversationService = aiConversationService;
        this.responseNormalizer = responseNormalizer;
        this.aiTurnService = aiTurnService;
        this.turnDigestService = turnDigestService;
        this.turnUnderstandingService = turnUnderstandingService;
        this.properties = properties;
        this.executor = executor;
        this.turnTimeoutMs = Math.max(1L, turnTimeoutMs);
    }

    @Autowired(required = false)
    public void setContestAssistanceTelemetryService(
            ContestAiAssistanceTelemetryService contestAssistanceTelemetryService
    ) {
        this.contestAssistanceTelemetryService = contestAssistanceTelemetryService;
    }

    /**
     * L3 first layer (design doc §5.3): fingerprint the raw user message against
     * the turn's constrained running-contest problems. Participants only;
     * every evaluation (including PASS) is audited.
     */
    private GuardVerdict matchUserMessage(long userId, String turnId, String conversationId,
                                          String message, PolicySnapshotService.PolicySnapshot snapshot) {
        if (snapshot == null || snapshot.participantStatus() == ParticipantStatus.NON_PARTICIPANT) {
            return null;
        }
        List<RunningContestProblemStatement> candidates = snapshot.constrainedProblems();
        if (candidates.isEmpty()) {
            return null;
        }
        long startedNanos = System.nanoTime();
        GuardVerdict verdict = fingerprintMatcher.match(message, candidates);
        int latencyMs = (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
        ObjectNode detail = JsonNodeFactory.instance.objectNode()
                .put("candidateCount", candidates.size())
                .put("maxScore", verdict.maxScore());
        guardDecisionRecorder.record(turnId, userId, conversationId,
                GuardLayer.L3_FINGERPRINT_MSG, verdict.decision(),
                verdict.matchedProblems(), verdict.reasonCode(), detail, false, latencyMs);
        return verdict;
    }

    /**
     * T1.6 (A3.2): a policy section that cannot be built stops the turn, fail-closed like the
     * L1/L2 checks: audited as a degraded L2 block, refused as temporarily unavailable. Optional
     * sections that fail are only skipped and listed in the bootstrap warnings.
     */
    private BootstrapContextBuilder.BootstrapContext buildBootstrap(Long userId, String turnId, String conversationId,
                                                                    String message,
                                                                    PolicySnapshotService.PolicySnapshot snapshot,
                                                                    GuardVerdict msgVerdict,
                                                                    BootstrapContextBuilder.EntryContext entryContext) {
        long startedNanos = System.nanoTime();
        try {
            return bootstrapBuilder.build(userId, conversationId, message, snapshot, msgVerdict, entryContext,
                    properties.getAgentCore().getRecentTurnsLimit(),
                    properties.getAgentCore().getBootstrapBudgetTokens());
        } catch (ContextPolicyException ex) {
            int latencyMs = (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
            log.warn("bootstrap policy context failed, fail-closed user={} turn={} contributor={} reason={} error={}",
                    userId, turnId, ex.contributor(), ex.reason(),
                    ex.getCause() == null ? null : ex.getCause().toString());
            ObjectNode detail = JsonNodeFactory.instance.objectNode()
                    .put("contributor", ex.contributor())
                    .put("reason", ex.reason());
            guardDecisionRecorder.record(turnId, userId, conversationId,
                    GuardLayer.L2_POLICY_INJECT, GuardDecision.BLOCK, REASON_CONTEXT_POLICY_FAILED, detail, true,
                    latencyMs);
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "安全策略暂时不可用，请稍后重试");
        }
    }

    public TurnHandle start(Long userId, AiChatRequest request) {
        long startedNanos = System.nanoTime();
        AiTurnEntity turn = null;
        boolean ownsTurn = false;
        try {
            aiQuotaService.assertAvailable(userId);
            AiConversationEntity conversation = aiConversationService.resolveForWrite(userId, request);
            AiTurnService.BeginTurnOutcome beginOutcome = aiTurnService.beginTurn(conversation.getId(), clientMessageId(request));
            turn = beginOutcome.turn();
            if (!beginOutcome.created()) {
                // Duplicate submission (retry/double-click/SSE reconnect with the same
                // clientTurnId): never regenerate; replay or attach to the existing turn.
                return resumeTurn(userId, conversation, turn);
            }
            ownsTurn = true;
            aiTurnService.markBuildingContext(turn.getId());
            // L1 (fail-closed, audited) then L2 snapshot + injection (design doc §5.1/§5.2).
            ContestParticipationService.ParticipationView participation =
                    participationService.evaluate(userId, turn.getId(), conversation.getId());
            ContestAiAssistanceTelemetryService telemetryService = contestAssistanceTelemetryService;
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext =
                    ContestAiAssistanceTelemetryService.TrackingContext.inactive();
            if (telemetryService != null) {
                try {
                    telemetryContext = telemetryService.begin(turn, conversation, participation.attributed());
                } catch (RuntimeException ex) {
                    log.warn("contest assistance start observation failed turn={} error={}", turn.getId(), ex.toString());
                }
            }
            PolicySnapshotService.PolicySnapshot snapshot =
                    policySnapshotService.createForTurn(userId, turn.getId(), conversation.getId(), participation);
            aiTurnService.attachPolicySnapshot(turn.getId(), snapshot.id());
            // L3 first layer (message fingerprint, participants only): annotate the
            // bootstrap with matched restricted problems; PASS is audited too.
            GuardVerdict msgVerdict = matchUserMessage(userId, turn.getId(), conversation.getId(),
                    request.message(), snapshot);
            // Build BEFORE appending the user message so the current request does not
            // appear twice (RECENT_TURNS + CURRENT_USER_REQUEST).
            // F1/F2 entry metadata: the request's problemId falls back to the
            // conversation-bound problem so the entry context survives follow-up
            // turns that no longer carry it.
            BootstrapContextBuilder.EntryContext entryContext = new BootstrapContextBuilder.EntryContext(
                    request.problemId() != null ? request.problemId() : conversation.getProblemId(),
                    request.contestContext(),
                    request.submissionContext(),
                    request.selectionContext());
            BootstrapContextBuilder.BootstrapContext bootstrap = buildBootstrap(userId, turn.getId(), conversation.getId(),
                    request.message(), snapshot, msgVerdict, entryContext);
            AiChatMessageResponse user = aiConversationService.appendMessage(
                    conversation.getId(),
                    userId,
                    request.problemId(),
                    "user",
                    request.message(),
                    null,
                    clientMessageId(request),
                    null
            );
            AiChatMessageResponse assistant = aiConversationService.appendMessageWithStatus(
                    conversation.getId(),
                    userId,
                    request.problemId(),
                    "assistant",
                    "",
                    null,
                    assistantClientMessageId(request),
                    null,
                    AiConversationService.MESSAGE_STATUS_RUNNING,
                    null
            );
            aiTurnService.attachMessages(turn.getId(), user.id(), assistant.id());
            AiTurnEntity scheduledTurn = turn;
            ContestAiAssistanceTelemetryService.TrackingContext scheduledTelemetryContext = telemetryContext;
            CancellationToken cancellation = new CancellationToken();
            LoopBudget budget = LoopBudget.from(properties.getAgentCore());
            TurnEventBuffer events = new TurnEventBuffer(TurnEventBuffer.capacityFor(budget), startedNanos);
            CompletableFuture<TurnResult> rawResult = CompletableFuture.supplyAsync(
                    () -> complete(userId, conversation, request, bootstrap, snapshot, msgVerdict,
                            user, assistant, scheduledTurn, scheduledTelemetryContext, participation.attributed(),
                            budget, events, cancellation),
                    executor
            );
            CompletableFuture<TurnResult> result = rawResult
                    .orTimeout(turnTimeoutMs, TimeUnit.MILLISECONDS)
                    .handle((value, ex) -> {
                        if (ex == null) {
                            return value;
                        }
                        if (isTimeout(ex)) {
                            try {
                                cancellation.cancel();
                            } catch (RuntimeException cancelFailure) {
                                log.warn("turn cancellation failed turn={} errorType={}", scheduledTurn.getId(), cancelFailure.getClass().getSimpleName());
                            }
                            failTimedOutTurn(userId, scheduledTurn.getId(), assistant.id());
                            throw new CompletionException(turnTimeoutFailure());
                        }
                        throw asCompletionException(ex);
                    });
            inFlightTurns.put(scheduledTurn.getId(), result);
            result.whenComplete((value, ex) -> {
                events.close();
                inFlightTurns.remove(scheduledTurn.getId());
            });
            return new TurnHandle(conversation, new AiChatContext("", "", "", "", ""), user, assistant, result,
                    scheduledTurn.getId(), events);
        } catch (RuntimeException ex) {
            // Only a turn created by this request may be failed here; a duplicate request
            // attaching to someone else's in-flight turn must never mutate its state.
            if (ownsTurn && turn != null) {
                if (aiTurnService.failTurn(turn.getId(), AiTurnService.STATUS_FAILED_RETRYABLE, "TURN_SETUP_FAILURE")
                        && contestAssistanceTelemetryService != null) {
                    contestAssistanceTelemetryService.finishFailedByTurnId(
                            turn.getId(), AiTurnService.STATUS_FAILED_RETRYABLE);
                }
            }
            throw ex;
        }
    }

    /**
     * Reattach to an existing turn (SSE reconnect by turnId). Read-only: no quota
     * assertion, no new messages, and crucially no second provider call.
     */
    public TurnHandle resume(Long userId, String turnId, AiChatRequest request) {
        AiTurnEntity turn = aiTurnService.findById(turnId);
        if (turn == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI turn not found");
        }
        AiConversationEntity conversation = aiConversationService.getOwnedConversation(userId, turn.getConversationId());
        return resumeTurn(userId, conversation, turn);
    }

    private TurnHandle resumeTurn(Long userId, AiConversationEntity conversation, AiTurnEntity turn) {
        AiTurnEntity current = awaitTurnMessageIds(turn);
        String assistantMessageId = current.getAssistantMessageId();
        if (assistantMessageId == null) {
            throw new DomainException(ErrorCode.CONFLICT, "AI turn failed before any message was stored; retry with a new message");
        }
        Long userMessageId = parseMessageId(current.getUserMessageId());
        AiChatMessageResponse user = aiConversationService.getMessage(userId, userMessageId);
        AiChatMessageResponse assistant = aiConversationService.getMessage(userId, parseMessageId(assistantMessageId));
        CompletableFuture<TurnResult> result;
        if (AiTurnService.STATUS_COMPLETED.equals(current.getStatus())) {
            result = CompletableFuture.completedFuture(replayCompletedTurn(userId, assistant));
        } else if (AiTurnService.isTerminal(current.getStatus())) {
            result = CompletableFuture.failedFuture(terminalTurnFailure(assistant));
        } else {
            result = inFlightTurns.get(current.getId());
            if (result == null) {
                // The generating instance is gone (restart) or this is a second instance:
                // poll the turn row until it reaches a terminal state.
                result = pollTurnCompletion(userId, current.getId());
            }
        }
        return new TurnHandle(conversation, new AiChatContext("", "", "", "", ""), user, assistant, result, current.getId());
    }

    private AiTurnEntity awaitTurnMessageIds(AiTurnEntity turn) {
        AiTurnEntity current = turn;
        long deadline = System.currentTimeMillis() + 3_000L;
        while (current.getAssistantMessageId() == null && !AiTurnService.isTerminal(current.getStatus())
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            AiTurnEntity reloaded = aiTurnService.findById(turn.getId());
            if (reloaded == null) {
                break;
            }
            current = reloaded;
        }
        return current;
    }

    private CompletableFuture<TurnResult> pollTurnCompletion(Long userId, String turnId) {
        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.currentTimeMillis() + turnTimeoutMs;
            while (System.currentTimeMillis() < deadline) {
                AiTurnEntity current = aiTurnService.findById(turnId);
                if (current == null) {
                    throw new CompletionException(new DomainException(ErrorCode.NOT_FOUND, "AI turn not found"));
                }
                if (AiTurnService.isTerminal(current.getStatus()) && current.getAssistantMessageId() != null) {
                    AiChatMessageResponse assistant = aiConversationService.getMessage(userId, parseMessageId(current.getAssistantMessageId()));
                    if (AiTurnService.STATUS_COMPLETED.equals(current.getStatus())) {
                        return replayCompletedTurn(userId, assistant);
                    }
                    throw new CompletionException(terminalTurnFailure(assistant));
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(turnTimeoutFailure());
                }
            }
            throw new CompletionException(turnTimeoutFailure());
        }, executor);
    }

    private TurnResult replayCompletedTurn(Long userId, AiChatMessageResponse assistant) {
        AiCompletion completion = new AiCompletion(assistant.content(), null, assistant.model(), 0, 0);
        AiAssistantResponseNormalizer.NormalizedResponse normalized = responseNormalizer.normalize(completion);
        return new TurnResult(completion, normalized, assistant, null, 0);
    }

    private DomainException terminalTurnFailure(AiChatMessageResponse assistant) {
        String message = assistant != null && assistant.errorMessage() != null && !assistant.errorMessage().isBlank()
                ? assistant.errorMessage()
                : "AI provider call failed";
        return new DomainException(ErrorCode.INTERNAL_ERROR, message);
    }

    private Long parseMessageId(String messageId) {
        try {
            return Long.parseLong(messageId);
        } catch (NumberFormatException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI turn message reference is invalid");
        }
    }

    /**
     * T1.11: what the code tools may run, bound by the server for this turn: the code the user
     * selected, the conversation's problem and the submission it is about. The model only names
     * a source; it never passes code.
     */
    static Map<String, String> codeToolBindings(AiChatRequest request, AiConversationEntity conversation) {
        Map<String, String> bindings = new java.util.LinkedHashMap<>();
        Long problemId = request.problemId() != null ? request.problemId() : conversation.getProblemId();
        if (problemId != null) {
            bindings.put("turn.problemId", String.valueOf(problemId));
        }
        if (request.submissionContext() != null && request.submissionContext().submissionId() != null) {
            bindings.put("turn.submissionId", String.valueOf(request.submissionContext().submissionId()));
        }
        String selected = request.selectionContext() == null ? null : request.selectionContext().selectedText();
        if (selected != null && !selected.isBlank()) {
            bindings.put("turn.selection", selected.length() > 65_536 ? selected.substring(0, 65_536) : selected);
        }
        return bindings;
    }

    private TurnResult complete(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            BootstrapContextBuilder.BootstrapContext bootstrap,
            PolicySnapshotService.PolicySnapshot snapshot,
            GuardVerdict msgVerdict,
            AiChatMessageResponse user,
            AiChatMessageResponse assistant,
            AiTurnEntity turn,
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext,
            RunningContestParticipation attributedParticipation,
            LoopBudget budget,
            TurnEventBuffer events,
            CancellationToken cancellation
    ) {
        try {
            // CAS non-terminal -> GENERATING. Losing this CAS means the timeout cleanup
            // already finalized the turn: the provider must not be called for a dead turn.
            if (!aiTurnService.advanceToGenerating(turn.getId(), turn.getId())) {
                throw turnTimeoutFailure();
            }
            // §6.2 (D1=B2): pre-turn understanding sets the tool-call floor only.
            // The judgement is a small model call on this worker thread; fail-open inside.
            boolean hasPriorTurns = bootstrap.sections().stream()
                    .anyMatch(section -> section.type() == ContextSectionType.RECENT_TURNS);
            TurnUnderstandingService.TurnUnderstanding understanding =
                    turnUnderstandingService.assess(request.message(), hasPriorTurns);
            recordContestAssistanceUsage(telemetryContext, "UNDERSTANDING",
                    ContestAiAssistanceTelemetryService.SOURCE_UNDERSTANDING, understanding.usage());
            if (hasPriorTurns && understanding.usage() == null) {
                markContestAssistanceTokensPartial(telemetryContext);
            }
            boolean requireToolCall = !understanding.requiresTools().isEmpty();
            // P3-3 (C5 wiring): the contest policy projection travels with the run so the
            // runtime can hide contest-only tools and every tool call can ABAC internally.
            // P3-4: the message-layer verdict travels too, so the context layer does not
            // re-inject rules for problems the message layer already constrained.
            ContestPolicyView contestPolicy = ContestPolicyView.from(snapshot);
            // P3-5 restricted-turn predicate (design doc §5.4 实施补充): participant and
            // at least one constrained problem in the snapshot. The context-layer match
            // result is only knowable after the run finishes, but the output protocol
            // must be chosen up front, so the predicate is deliberately conservative.
            // Non-restricted turns skip L4 entirely — zero cost, zero audit rows (same
            // contract as L3).
            boolean restrictedTurn = contestPolicy.isParticipant() && !contestPolicy.constrainedProblems().isEmpty();
            // P3-5 L4 and the P3-6 recheck decide the delivered answer inside the run: an
            // intercepted draft is never persisted to ai_messages; it is regenerated once in the
            // same run, and a second interception downgrades the reply to the server-authored
            // safe refusal text (ContestFinalAnswerCheck).
            ContestFinalAnswerCheck finalCheck = new ContestFinalAnswerCheck(contestOutputGuard,
                    policySnapshotService, turn.getId(), userId, conversation.getId(), snapshot, contestPolicy,
                    restrictedTurn);
            AttemptUsage attemptUsage = new AttemptUsage(finalCheck,
                    contestAssistanceUsageObserver(telemetryContext,
                            ContestAiAssistanceTelemetryService.SOURCE_AGENT_PRIMARY),
                    contestAssistanceUsageObserver(telemetryContext,
                            ContestAiAssistanceTelemetryService.SOURCE_AGENT_SAFE_REGENERATION),
                    contestAssistanceUsageObserver(telemetryContext,
                            ContestAiAssistanceTelemetryService.SOURCE_AGENT_RECHECK_REGENERATION));
            AgentRuntime.AgentRunResult runResult = agentRuntime.run(AgentRuntime.AgentRunRequest
                    .builder(turn.getId(), userId, conversation.getId())
                    .policySnapshotId(snapshot.id())
                    .grantedScopes(Set.of("AI_CHAT"))
                    .sections(bootstrap.sections())
                    .contextWarnings(bootstrap.warnings())
                    .requireToolCall(requireToolCall)
                    .output(restrictedTurn ? "BUFFERED" : "STREAM",
                            restrictedTurn ? CallProfile.CHAT_BUFFERED : CallProfile.CHAT_STREAM)
                    .budget(budget)
                    .turnProgress(!restrictedTurn, restrictedTurn ? null : events, cancellation)
                    .contest(contestPolicy, msgVerdict)
                    .serverBindings(codeToolBindings(request, conversation))
                    .usageObserver(attemptUsage)
                    .interceptor(finalCheck)
                    .build());
            boolean pseudoStream = finalCheck.pseudoStream();
            // Quota semantics (P3-5): exactly one usage record per turn, attributed to the
            // attempt that determined the delivered content (the first on pass, the
            // regeneration otherwise, including a race-triggered one). A refused turn is a
            // completed turn — the record stays success=true and is never duplicated.
            AiCompletion completion = new AiCompletion(
                    runResult.content(),
                    runResult.provider(), runResult.model(),
                    attemptUsage.promptTokens(), attemptUsage.completionTokens());
            AiAssistantResponseNormalizer.NormalizedResponse normalized = responseNormalizer.normalize(completion);
            completion = normalized.completion();
            // CAS GENERATING -> COMPLETED: only the winner may record usage and complete the
            // assistant message. A lost CAS means the timeout cleanup already finalized both.
            if (!aiTurnService.completeTurn(turn.getId())) {
                throw turnTimeoutFailure();
            }
            // The turn state CAS is the authoritative completion point. Finalize the
            // observer immediately after it wins so later best-effort quota/message/
            // digest side effects cannot leave a completed ai_turn as telemetry PENDING.
            finishContestAssistanceTelemetry(telemetryContext, request, conversation, snapshot,
                    attributedParticipation, msgVerdict, runResult.contestGuardVerdict());
            aiQuotaService.record(userId, completion.provider(), completion.model(),
                    completion.promptTokens(), completion.completionTokens(), true);
            AiChatMessageResponse completedAssistant = aiConversationService.completeMessage(
                    userId,
                    assistant.id(),
                    completion.content(),
                    completion.model()
            );
            String conversationMode = aiConversationService.updateAutomaticMode(userId, conversation.getId(), request, completion);
            // §6.3: synchronous stub digest + async curate enqueue; never throws, never
            // blocks the chat path. Message ids come from the freshly created message
            // objects: the in-memory turn entity predates attachMessages and still
            // carries nulls.
            turnDigestService.recordCompletedTurn(buildDigestInput(userId, turn, request, completion,
                    user.id(), assistant.id()));
            return new TurnResult(completion, normalized, completedAssistant, conversationMode, 0, pseudoStream);
        } catch (RuntimeException ex) {
            // CAS any non-terminal state -> terminal failure: exactly one of this failure
            // path and the timeout cleanup records the failed usage and fails the message.
            if (aiTurnService.failTurn(turn.getId(), failureStatus(ex), failureErrorCode(ex))) {
                aiQuotaService.record(userId, null, null, 0, 0, false);
                aiConversationService.failMessage(userId, assistant.id(), failureMessage(ex));
                finishContestAssistanceFailure(telemetryContext, failureStatus(ex));
            }
            throw new CompletionException(providerFailure(ex));
        }
    }

    private TurnDigestInput buildDigestInput(Long userId, AiTurnEntity turn, AiChatRequest request, AiCompletion completion,
            Long userMessageId, Long assistantMessageId) {
        java.util.List<Long> referencedProblemIds = new java.util.ArrayList<>();
        Long selectionProblemId = parseProblemId(request.selectionContext() != null
                && request.selectionContext().problemContext() != null
                ? request.selectionContext().problemContext().problemId() : null);
        if (selectionProblemId != null) {
            referencedProblemIds.add(selectionProblemId);
        }
        Long contextProblemId = parseProblemId(request.problemContext() != null ? request.problemContext().id() : null);
        if (contextProblemId != null) {
            referencedProblemIds.add(contextProblemId);
        }
        String entryPoint = "CHAT";
        if (request.contestContext() != null && request.contestContext().contestRunId() != null) {
            entryPoint = "CONTEST";
        } else if (request.problemId() != null) {
            entryPoint = "PROBLEM_PAGE";
        }
        return new TurnDigestInput(
                turn.getId(),
                turn.getConversationId(),
                userId,
                userMessageId == null ? null : String.valueOf(userMessageId),
                assistantMessageId == null ? null : String.valueOf(assistantMessageId),
                request.message(),
                completion.content(),
                completion.model(),
                request.problemId(),
                referencedProblemIds,
                request.selectionContext() != null ? request.selectionContext().selectedText() : null,
                request.selectionContext() != null ? request.selectionContext().sourceMessageId() : null,
                request.submissionContext() != null ? request.submissionContext().submissionId() : null,
                entryPoint
        );
    }

    private Long parseProblemId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void recordContestAssistanceUsage(
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext,
            String usageKey,
            String usageSource,
            ProviderUsage usage
    ) {
        if (contestAssistanceTelemetryService != null && usage != null) {
            try {
                contestAssistanceTelemetryService.recordUsage(telemetryContext, usageKey, usageSource, usage);
            } catch (RuntimeException ex) {
                log.warn("contest assistance token observation failed turn={} source={} error={}",
                        telemetryContext == null ? null : telemetryContext.turnId(), usageSource, ex.toString());
            }
        }
    }

    private void markContestAssistanceTokensPartial(
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext
    ) {
        if (contestAssistanceTelemetryService != null) {
            try {
                contestAssistanceTelemetryService.markTokenAccountingPartial(telemetryContext);
            } catch (RuntimeException ex) {
                log.warn("contest assistance partial-token observation failed turn={} error={}",
                        telemetryContext == null ? null : telemetryContext.turnId(), ex.toString());
            }
        }
    }

    private Consumer<ProviderUsage> contestAssistanceUsageObserver(
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext,
            String usageSource
    ) {
        if (contestAssistanceTelemetryService == null || telemetryContext == null || !telemetryContext.active()) {
            return null;
        }
        AtomicInteger sequence = new AtomicInteger();
        return usage -> {
            if (usage == null) {
                markContestAssistanceTokensPartial(telemetryContext);
                return;
            }
            recordContestAssistanceUsage(telemetryContext,
                    usageSource + "_" + sequence.incrementAndGet(), usageSource, usage);
        };
    }

    private void finishContestAssistanceTelemetry(
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext,
            AiChatRequest request,
            AiConversationEntity conversation,
            PolicySnapshotService.PolicySnapshot snapshot,
            RunningContestParticipation attributedParticipation,
            GuardVerdict messageVerdict,
            GuardVerdict runtimeVerdict
    ) {
        if (contestAssistanceTelemetryService != null) {
            try {
                contestAssistanceTelemetryService.finishSuccessful(
                        telemetryContext,
                        AiTurnService.STATUS_COMPLETED,
                        request.message(),
                        contestAssistanceTelemetryService.candidates(
                                request, conversation, snapshot, attributedParticipation,
                                messageVerdict, runtimeVerdict));
            } catch (RuntimeException ex) {
                log.warn("contest assistance completion observation failed turn={} error={}",
                        telemetryContext == null ? null : telemetryContext.turnId(), ex.toString());
            }
        }
    }

    private void finishContestAssistanceFailure(
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext,
            String terminalStatus
    ) {
        if (contestAssistanceTelemetryService != null) {
            try {
                contestAssistanceTelemetryService.finishFailed(telemetryContext, terminalStatus);
            } catch (RuntimeException ex) {
                log.warn("contest assistance failure observation failed turn={} error={}",
                        telemetryContext == null ? null : telemetryContext.turnId(), ex.toString());
            }
        }
    }

    private void failTimedOutTurn(Long userId, String turnId, Long assistantMessageId) {        try {
            if (aiTurnService.failTurn(turnId, AiTurnService.STATUS_FAILED_RETRYABLE, "TURN_TIMEOUT")) {
                aiQuotaService.record(userId, null, null, 0, 0, false);
                aiConversationService.failMessage(userId, assistantMessageId, "AI provider call timed out");
                if (contestAssistanceTelemetryService != null) {
                    contestAssistanceTelemetryService.finishFailedByTurnId(
                            turnId, AiTurnService.STATUS_FAILED_RETRYABLE);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("AI turn timeout cleanup failed user={} turn={} assistantMessage={} error={}",
                    userId, turnId, assistantMessageId, ex.toString());
        }
    }

    private String failureStatus(RuntimeException ex) {
        if (ex instanceof DomainException domainException
                && domainException.errorCode() != ErrorCode.TOO_MANY_REQUESTS
                && domainException.errorCode() != ErrorCode.SERVICE_UNAVAILABLE) {
            return AiTurnService.STATUS_FAILED_FINAL;
        }
        return AiTurnService.STATUS_FAILED_RETRYABLE;
    }

    private String failureErrorCode(RuntimeException ex) {
        if (ex instanceof DomainException domainException) {
            return domainException.errorCode().name();
        }
        return "PROVIDER_FAILURE";
    }

    private String clientMessageId(AiChatRequest request) {
        if (request.clientMessageId() == null || request.clientMessageId().isBlank()) {
            return null;
        }
        String normalized = request.clientMessageId().trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private String assistantClientMessageId(AiChatRequest request) {
        String clientMessageId = clientMessageId(request);
        if (clientMessageId == null) {
            return null;
        }
        String assistantId = clientMessageId + ":assistant";
        return assistantId.length() <= 80 ? assistantId : assistantId.substring(0, 80);
    }

    private RuntimeException providerFailure(RuntimeException ex) {
        if (ex instanceof DomainException) {
            return ex;
        }
        return new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider call failed");
    }

    private DomainException turnTimeoutFailure() {
        return new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider call timed out");
    }

    private CompletionException asCompletionException(Throwable ex) {
        if (ex instanceof CompletionException completionException) {
            return completionException;
        }
        return new CompletionException(ex);
    }

    private boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String failureMessage(RuntimeException ex) {
        if (ex instanceof DomainException domainException && domainException.getMessage() != null && !domainException.getMessage().isBlank()) {
            return domainException.getMessage();
        }
        return "AI provider call failed";
    }

    /**
     * A chat run's usage, attributed to the attempt that produced it: telemetry keeps every
     * attempt under its own source, and the turn's one quota record counts only the attempt
     * whose answer was delivered (P3-5, unchanged since the regeneration moved into the run).
     */
    private static final class AttemptUsage implements Consumer<ProviderUsage> {
        private final ContestFinalAnswerCheck check;
        private final Map<ContestFinalAnswerCheck.Attempt, Consumer<ProviderUsage>> telemetry =
                new EnumMap<>(ContestFinalAnswerCheck.Attempt.class);
        private ContestFinalAnswerCheck.Attempt counted = ContestFinalAnswerCheck.Attempt.PRIMARY;
        private long promptTokens;
        private long completionTokens;

        AttemptUsage(ContestFinalAnswerCheck check, Consumer<ProviderUsage> primary,
                     Consumer<ProviderUsage> safeRegeneration, Consumer<ProviderUsage> recheckRegeneration) {
            this.check = check;
            putIfPresent(ContestFinalAnswerCheck.Attempt.PRIMARY, primary);
            putIfPresent(ContestFinalAnswerCheck.Attempt.SAFE_REGENERATION, safeRegeneration);
            putIfPresent(ContestFinalAnswerCheck.Attempt.RECHECK_REGENERATION, recheckRegeneration);
        }

        @Override
        public synchronized void accept(ProviderUsage usage) {
            ContestFinalAnswerCheck.Attempt attempt = check.attempt();
            Consumer<ProviderUsage> observer = telemetry.get(attempt);
            if (observer != null) {
                observer.accept(usage);
            }
            if (usage == null) {
                return;
            }
            if (attempt != counted) {
                counted = attempt;
                promptTokens = 0;
                completionTokens = 0;
            }
            promptTokens += usage.promptTokens();
            completionTokens += usage.completionTokens();
        }

        synchronized long promptTokens() {
            return promptTokens;
        }

        synchronized long completionTokens() {
            return completionTokens;
        }

        private void putIfPresent(ContestFinalAnswerCheck.Attempt attempt, Consumer<ProviderUsage> observer) {
            if (observer != null) {
                telemetry.put(attempt, observer);
            }
        }
    }
}
