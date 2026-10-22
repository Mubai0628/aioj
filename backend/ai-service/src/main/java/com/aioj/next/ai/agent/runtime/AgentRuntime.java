package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.agent.context.BootstrapContextBuilder;
import com.aioj.next.ai.agent.context.ContextManifestService;
import com.aioj.next.ai.agent.context.ContextSection;
import com.aioj.next.ai.agent.context.ContextSectionRenderer;
import com.aioj.next.ai.agent.guard.ContextFingerprintGuard;
import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.model.CallProfile;
import com.aioj.next.ai.agent.model.GatewayMessage;
import com.aioj.next.ai.agent.model.GatewayRequest;
import com.aioj.next.ai.agent.model.GatewayResponse;
import com.aioj.next.ai.agent.model.GatewayToolCall;
import com.aioj.next.ai.agent.model.ModelGateway;
import com.aioj.next.ai.agent.model.ModelUsage;
import com.aioj.next.ai.agent.model.ToolChoiceMode;
import com.aioj.next.ai.agent.model.ThinkingModeUnavailableException;
import com.aioj.next.ai.agent.model.UsageMeter;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.tool.ToolBroker;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolRegistry;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.persistence.entity.AiAgentRunEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The agent loop (design doc §4.2): model call → tool execution → model call,
 * bounded by {@link LoopBudget}. Every model call is manifest-audited, every
 * tool call goes through the ToolBroker pipeline (validation → authorization →
 * execution → sanitization → audit).
 *
 * <p>tool_choice=REQUIRED is native only where the provider supports it (Kimi);
 * for other providers the runtime simulates it once (system nudge + one retry)
 * and reports {@code missed_required_tool} instead of silently pretending.</p>
 *
 * <p>Empty completions (typically thinking exhausting max_tokens, §3.4) get one
 * thinking-off recovery retry ({@link CallProfile#RECOVERY_ANSWER}); a second
 * empty response fails the run as retryable instead of storing an empty answer.</p>
 *
 * <p>For contest participants the L3 second layer (§5.3, P3-4) re-fingerprints the
 * assembled context before every model call and appends newly matched problems'
 * rules as a trailing system message that persists for the rest of the run.</p>
 */
@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final ModelGateway modelGateway;
    private final ToolRegistry toolRegistry;
    private final ToolBroker toolBroker;
    private final ContextSectionRenderer renderer;
    private final ContextManifestService manifestService;
    private final AgentRunStateMachine stateMachine;
    private final ObjectMapper objectMapper;
    private final ContextFingerprintGuard contextGuard;
    private final ExecutorService boundedModelExecutor;

    public AgentRuntime(ModelGateway modelGateway, ToolRegistry toolRegistry, ToolBroker toolBroker,
                        ContextSectionRenderer renderer, ContextManifestService manifestService,
                        AgentRunStateMachine stateMachine, ObjectMapper objectMapper,
                        ContextFingerprintGuard contextGuard, AiProperties aiProperties) {
        this.modelGateway = modelGateway;
        this.toolRegistry = toolRegistry;
        this.toolBroker = toolBroker;
        this.renderer = renderer;
        this.manifestService = manifestService;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
        this.contextGuard = contextGuard;
        this.boundedModelExecutor = createModelExecutor(aiProperties.getCapacity());
    }

    public record AgentRunRequest(
            String turnId,
            long userId,
            String conversationId,
            String policySnapshotId,
            Set<String> grantedScopes,
            List<ContextSection> sections,
            boolean requireToolCall,
            String outputMode,
            CallProfile profile,
            LoopBudget budget,
            ContestPolicyView contestPolicy,
            /** P3-4: the L3 message-layer verdict; its problems seed the context layer's constrained set. */
            GuardVerdict messageLayerVerdict,
            /** Optional observer for one actual provider invocation; it must never affect the loop. */
            Consumer<ModelUsage> usageObserver,
            /** DB-backed model scope; existing tutor runs default to TEXT_GENERATION. */
            AiModelScope modelScope,
            /** Trusted resource bindings injected into tool context, never model arguments. */
            Map<String, String> serverBindings,
            /** Permit one explicit thinking-capability fallback to standard tools/tool_calls. */
            boolean allowStandardToolFallback,
            /** Optional server-owned terminal gate; model prose cannot satisfy it. */
            AgentCompletionPolicy completionPolicy
    ) {
        public AgentRunRequest {
            modelScope = modelScope == null ? AiModelScope.TEXT_GENERATION : modelScope;
            serverBindings = serverBindings == null ? Map.of() : Map.copyOf(serverBindings);
            completionPolicy = completionPolicy == null ? AgentCompletionPolicy.none() : completionPolicy;
        }

        /** Signature before model-scope/resource-binding/fallback support. */
        public AgentRunRequest(String turnId, long userId, String conversationId, String policySnapshotId,
                               Set<String> grantedScopes, List<ContextSection> sections, boolean requireToolCall,
                               String outputMode, CallProfile profile, LoopBudget budget,
                               ContestPolicyView contestPolicy, GuardVerdict messageLayerVerdict,
                               Consumer<ModelUsage> usageObserver) {
            this(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections, requireToolCall,
                    outputMode, profile, budget, contestPolicy, messageLayerVerdict, usageObserver,
                    AiModelScope.TEXT_GENERATION, Map.of(), false, AgentCompletionPolicy.none());
        }

        /** Pre-P3-3 signature, kept so existing callers/tests compile unchanged. */
        public AgentRunRequest(String turnId, long userId, String conversationId, String policySnapshotId,
                               Set<String> grantedScopes, List<ContextSection> sections, boolean requireToolCall,
                               String outputMode, CallProfile profile, LoopBudget budget) {
            this(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections, requireToolCall,
                    outputMode, profile, budget, null, null, null,
                    AiModelScope.TEXT_GENERATION, Map.of(), false, AgentCompletionPolicy.none());
        }

        /** Draft/custom-scope signature without contest-only fields. */
        public AgentRunRequest(String turnId, long userId, String conversationId, String policySnapshotId,
                               Set<String> grantedScopes, List<ContextSection> sections, boolean requireToolCall,
                               String outputMode, CallProfile profile, LoopBudget budget,
                               AiModelScope modelScope, Map<String, String> serverBindings,
                               boolean allowStandardToolFallback) {
            this(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections, requireToolCall,
                    outputMode, profile, budget, null, null, null, modelScope, serverBindings,
                    allowStandardToolFallback, AgentCompletionPolicy.none());
        }

        /** Draft/custom-scope signature with a server-owned terminal gate. */
        public AgentRunRequest(String turnId, long userId, String conversationId, String policySnapshotId,
                               Set<String> grantedScopes, List<ContextSection> sections, boolean requireToolCall,
                               String outputMode, CallProfile profile, LoopBudget budget,
                               AiModelScope modelScope, Map<String, String> serverBindings,
                               boolean allowStandardToolFallback, AgentCompletionPolicy completionPolicy) {
            this(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections, requireToolCall,
                    outputMode, profile, budget, null, null, null, modelScope, serverBindings,
                    allowStandardToolFallback, completionPolicy);
        }

        /** P3-3 signature (no message-layer verdict), kept for the same reason. */
        public AgentRunRequest(String turnId, long userId, String conversationId, String policySnapshotId,
                               Set<String> grantedScopes, List<ContextSection> sections, boolean requireToolCall,
                               String outputMode, CallProfile profile, LoopBudget budget,
                               ContestPolicyView contestPolicy) {
            this(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections, requireToolCall,
                    outputMode, profile, budget, contestPolicy, null, null,
                    AiModelScope.TEXT_GENERATION, Map.of(), false, AgentCompletionPolicy.none());
        }

        /** P3-4 signature before token-observer support. */
        public AgentRunRequest(String turnId, long userId, String conversationId, String policySnapshotId,
                               Set<String> grantedScopes, List<ContextSection> sections, boolean requireToolCall,
                               String outputMode, CallProfile profile, LoopBudget budget,
                               ContestPolicyView contestPolicy, GuardVerdict messageLayerVerdict) {
            this(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections, requireToolCall,
                    outputMode, profile, budget, contestPolicy, messageLayerVerdict, null,
                    AiModelScope.TEXT_GENERATION, Map.of(), false, AgentCompletionPolicy.none());
        }
    }

    /**
     * A terminal condition enforced by the server. This keeps a model's natural-language
     * "done" response from ending a stateful workflow before its authoritative final tool
     * has actually succeeded.
     */
    public record AgentCompletionPolicy(String requiredSuccessfulToolName) {
        public AgentCompletionPolicy {
            requiredSuccessfulToolName = requiredSuccessfulToolName == null
                    ? null : requiredSuccessfulToolName.trim();
            if (requiredSuccessfulToolName != null && requiredSuccessfulToolName.isEmpty()) {
                requiredSuccessfulToolName = null;
            }
        }

        public static AgentCompletionPolicy none() {
            return new AgentCompletionPolicy(null);
        }

        public static AgentCompletionPolicy requireSuccessfulTool(String toolName) {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("Required completion tool name must not be blank");
            }
            return new AgentCompletionPolicy(toolName);
        }

        public boolean isRequired() {
            return requiredSuccessfulToolName != null;
        }
    }

    public record AgentRunResult(
            long runId,
            String content,
            String provider,
            String model,
            long promptTokens,
            long completionTokens,
            long cacheHitTokens,
            int steps,
            int toolCallCount,
            boolean missedRequiredTool,
            List<String> warnings,
            /** P3-4: merged L3 message+context layer verdict, for the P3-5 L4 output guard. */
            GuardVerdict contestGuardVerdict,
            AgentExecutionMode executionMode
    ) {
        /** Signature before execution-mode reporting. */
        public AgentRunResult(long runId, String content, String provider, String model,
                              long promptTokens, long completionTokens, long cacheHitTokens,
                              int steps, int toolCallCount, boolean missedRequiredTool, List<String> warnings,
                              GuardVerdict contestGuardVerdict) {
            this(runId, content, provider, model, promptTokens, completionTokens, cacheHitTokens,
                    steps, toolCallCount, missedRequiredTool, warnings, contestGuardVerdict,
                    AgentExecutionMode.STANDARD);
        }

        /** Pre-P3-4 signature, kept so existing callers/tests compile unchanged. */
        public AgentRunResult(long runId, String content, String provider, String model,
                              long promptTokens, long completionTokens, long cacheHitTokens,
                              int steps, int toolCallCount, boolean missedRequiredTool, List<String> warnings) {
            this(runId, content, provider, model, promptTokens, completionTokens, cacheHitTokens,
                    steps, toolCallCount, missedRequiredTool, warnings, null, AgentExecutionMode.STANDARD);
        }
    }

    public AgentRunResult run(AgentRunRequest spec) {
        AiModelEffectiveConfig config = modelGateway.configFor(spec.modelScope());
        List<ToolDescriptor> tools = toolRegistry.descriptorsForScopes(spec.grantedScopes());
        // C5 (frozen): problem.search only exists for contest participants — its search
        // space is the running-contest snapshot set. Non-participants never see the tool.
        ContestPolicyView contestPolicy = spec.contestPolicy();
        if (contestPolicy == null || !contestPolicy.isParticipant()) {
            tools = tools.stream()
                    .filter(descriptor -> !"problem.search".equals(descriptor.name()))
                    .toList();
        }
        LoopBudget budget = spec.budget();
        AiAgentRunEntity run = stateMachine.start(spec.turnId(), spec.conversationId(), spec.userId(),
                config.provider(), config.model(), spec.policySnapshotId(), spec.outputMode(), budget);
        stateMachine.bindWorkspace(run.getId(), spec.serverBindings().get("workspaceId"), budget.maxRunDuration());
        UsageMeter usage = new UsageMeter();
        List<String> warnings = new ArrayList<>();
        int toolCallCount = 0;
        int searchCalls = 0;
        int fetchCalls = 0;
        int sandboxCalls = 0;
        int testcaseGenerations = 0;
        int testcasePackageRuns = 0;
        int manifestSeq = 0;
        int toolSeq = 0;
        int steps = 0;
        boolean anyToolCallExecuted = false;
        boolean simulatedRetryUsed = false;
        boolean emptyRecoveryUsed = false;
        boolean toolBudgetExhausted = false;
        boolean completionToolSucceeded = false;
        String finalContent = null;
        String provider = config.provider();
        String model = config.model();
        boolean fallbackAllowed = spec.allowStandardToolFallback() || spec.profile() == CallProfile.PROBLEM_DRAFT;
        boolean standardFallback = fallbackAllowed && !modelGateway.usesThinking(config, spec.profile());
        AgentExecutionMode executionMode = standardFallback
                ? AgentExecutionMode.STANDARD_TOOL_FALLBACK
                : (modelGateway.usesThinking(config, spec.profile())
                    ? AgentExecutionMode.THINKING
                    : AgentExecutionMode.STANDARD);
        if (standardFallback) {
            warnings.add(AgentExecutionMode.STANDARD_TOOL_FALLBACK.name());
        }
        long deadlineNanos = deadlineNanos(budget);

        List<GatewayMessage> messages = new ArrayList<>(renderer.render(spec.sections()));
        int bootstrapMessageCount = messages.size();
        // L3 second layer state (§5.3, P3-4): problems already constrained by the
        // message layer (or an earlier step of this layer) are not re-injected.
        Set<Long> constrainedProblemIds = new LinkedHashSet<>();
        GuardVerdict contestGuardVerdict = spec.messageLayerVerdict();
        if (contestGuardVerdict != null && contestGuardVerdict.hasMatches()) {
            contestGuardVerdict.matchedProblems().forEach(ref -> {
                if (ref.problemId() != null) {
                    constrainedProblemIds.add(ref.problemId());
                }
            });
        }
        String toolDefinitionsHash = ContextManifestService.sha256(
                tools.stream().map(ToolDescriptor::name).collect(Collectors.joining(",")));
        boolean nativeRequired = modelGateway.capabilities(config).toolChoiceRequiredNative();
        Map<String, String> toolBindings = new java.util.LinkedHashMap<>(spec.serverBindings());
        toolBindings.put("agentRunId", String.valueOf(run.getId()));
        ToolExecutionContext toolContext = new ToolExecutionContext(spec.userId(), spec.conversationId(),
                spec.turnId(), 0L, spec.policySnapshotId(), spec.grantedScopes(), Instant.now(), spec.turnId(),
                contestPolicy, toolBindings);
        stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_CONTEXT_PLANNED);

        try {
            for (int step = 1; step <= budget.maxAgentSteps(); step++) {
                ensureBeforeDeadline(deadlineNanos);
                steps = step;
                stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_GENERATING);
                ToolChoiceMode choice = spec.requireToolCall() && !anyToolCallExecuted && nativeRequired
                        ? ToolChoiceMode.REQUIRED
                        : ToolChoiceMode.AUTO;
                contestGuardVerdict = applyContextLayerGuard(spec, contestPolicy, constrainedProblemIds,
                        contestGuardVerdict, messages, bootstrapMessageCount, step);
                boolean draftGateStillOpen = spec.completionPolicy().isRequired() && !completionToolSucceeded;
                CallProfile loopProfile = emptyRecoveryUsed && !draftGateStillOpen
                        ? CallProfile.RECOVERY_ANSWER : spec.profile();
                GatewayRequest request = new GatewayRequest(List.copyOf(messages), tools, choice, loopProfile);
                recordManifest(spec, run.getId(), ++manifestSeq, config, request, toolDefinitionsHash, warnings);
                ModelCallOutcome modelCall = callModel(config, request, standardFallback, fallbackAllowed,
                        remainingDuration(deadlineNanos));
                GatewayResponse response = modelCall.response();
                if (modelCall.fallbackActivated()) {
                    standardFallback = true;
                    executionMode = AgentExecutionMode.STANDARD_TOOL_FALLBACK;
                    if (!warnings.contains(AgentExecutionMode.STANDARD_TOOL_FALLBACK.name())) {
                        warnings.add(AgentExecutionMode.STANDARD_TOOL_FALLBACK.name());
                    }
                }
                usage.add(response);
                observeUsage(spec, response);
                if (response.provider() != null) {
                    provider = response.provider();
                }
                if (response.model() != null) {
                    model = response.model();
                }

                if (!response.hasToolCalls()) {
                    if (spec.completionPolicy().isRequired() && !completionToolSucceeded) {
                        if (isBlank(response.content())) {
                            if (!emptyRecoveryUsed) {
                                emptyRecoveryUsed = true;
                                warnings.add("empty_completion_recovery");
                                standardFallback = true;
                                executionMode = AgentExecutionMode.STANDARD_TOOL_FALLBACK;
                                if (!warnings.contains(AgentExecutionMode.STANDARD_TOOL_FALLBACK.name())) {
                                    warnings.add(AgentExecutionMode.STANDARD_TOOL_FALLBACK.name());
                                }
                                log.warn("Agent run got an empty pre-finalization completion turn={} run={} step={} finishReason={}",
                                        spec.turnId(), run.getId(), step, response.finishReason());
                                messages.add(GatewayMessage.system(completionRecoveryInstruction(
                                        spec.completionPolicy().requiredSuccessfulToolName(), true)));
                                continue;
                            }
                            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                                    "AI provider returned an empty response before the required completion gate");
                        }
                        // A long prose-only answer can exhaust the provider's output budget and was the
                        // original cause of one-step draft runs. Do not replay that non-authoritative
                        // text into the next context. Move the remaining controlled loop to standard
                        // tool mode, where DeepSeek no longer spends the response budget on reasoning.
                        if (fallbackAllowed) {
                            standardFallback = true;
                            executionMode = AgentExecutionMode.STANDARD_TOOL_FALLBACK;
                            if (!warnings.contains(AgentExecutionMode.STANDARD_TOOL_FALLBACK.name())) {
                                warnings.add(AgentExecutionMode.STANDARD_TOOL_FALLBACK.name());
                            }
                        }
                        if (!warnings.contains("premature_completion_recovered")) {
                            warnings.add("premature_completion_recovered");
                        }
                        messages.add(GatewayMessage.system(completionRecoveryInstruction(
                                spec.completionPolicy().requiredSuccessfulToolName(), false)));
                        continue;
                    }
                    if (spec.requireToolCall() && !anyToolCallExecuted && !simulatedRetryUsed && !nativeRequired) {
                        simulatedRetryUsed = true;
                        messages.add(GatewayMessage.assistant(response.content(), response.reasoningContent(), List.of()));
                        messages.add(GatewayMessage.system(
                                "Server policy requires at least one relevant tool call before the final answer "
                                        + "for this turn. Call the most relevant tool now."));
                        continue;
                    }
                    if (isBlank(response.content())) {
                        if (!emptyRecoveryUsed) {
                            // Thinking can burn the whole max_tokens budget on hard turns
                            // (spike fact §3.4): retry once with thinking off. The empty
                            // assistant message is deliberately not appended to the context.
                            emptyRecoveryUsed = true;
                            warnings.add("empty_completion_recovery");
                            log.warn("Agent run got an empty completion turn={} run={} step={} finishReason={}",
                                    spec.turnId(), run.getId(), step, response.finishReason());
                            messages.add(GatewayMessage.system(
                                    "Your previous reply was empty. Provide the complete final answer "
                                            + "to the user now, in the user's language."));
                            continue;
                        }
                        throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                                "AI provider returned an empty response");
                    }
                    finalContent = response.content();
                    break;
                }

                messages.add(GatewayMessage.assistant(response.content(), response.reasoningContent(), response.toolCalls()));
                stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_TOOL_CALLING);
                for (GatewayToolCall call : response.toolCalls()) {
                    if (toolCallCount >= budget.maxToolCalls()) {
                        toolBudgetExhausted = true;
                        break;
                    }
                    ensureBeforeDeadline(deadlineNanos);
                    if (budget.categoryExhausted(call.name(), searchCalls, fetchCalls,
                            sandboxCalls, testcaseGenerations, testcasePackageRuns)) {
                        messages.add(GatewayMessage.toolResult(call.callId(), budgetExceededPayload(call.name())));
                        continue;
                    }
                    ToolResult<String> result = toolBroker.execute(toolContext, run.getId(), ++toolSeq,
                            call.callId(), call.name(), call.argumentsJson(), remainingDuration(deadlineNanos));
                    toolCallCount++;
                    anyToolCallExecuted = true;
                    if (result.ok() && spec.completionPolicy().isRequired()
                            && spec.completionPolicy().requiredSuccessfulToolName().equals(call.name())) {
                        completionToolSucceeded = true;
                    }
                    switch (budget.categoryOf(call.name())) {
                        case "search" -> searchCalls++;
                        case "fetch" -> fetchCalls++;
                        case "sandbox" -> sandboxCalls++;
                        case "testcase_generate" -> {
                            sandboxCalls++;
                            testcaseGenerations++;
                        }
                        case "testcase_run" -> {
                            sandboxCalls++;
                            testcasePackageRuns++;
                        }
                        default -> {
                        }
                    }
                    messages.add(GatewayMessage.toolResult(call.callId(), result.data()));
                }
                stateMachine.recordProgress(run.getId(), step, toolCallCount);
                if (toolBudgetExhausted) {
                    warnings.add("tool_budget_exhausted");
                    break;
                }
            }

            if (finalContent == null) {
                ensureBeforeDeadline(deadlineNanos);
                // Step or tool budget exhausted: one final answer-only call without tools.
                if (!toolBudgetExhausted) {
                    warnings.add("step_budget_exhausted");
                }
                if (spec.completionPolicy().isRequired() && !completionToolSucceeded) {
                    warnings.add("required_completion_tool_missing");
                    messages.add(GatewayMessage.system(
                            "The controlled run budget ended before the required tool "
                                    + spec.completionPolicy().requiredSuccessfulToolName()
                                    + " succeeded. Do not continue designing the problem and do not expose private reasoning. "
                                    + "Return only a short, factual progress summary naming the remaining authoritative gate."));
                }
                stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_GENERATING);
                contestGuardVerdict = applyContextLayerGuard(spec, contestPolicy, constrainedProblemIds,
                        contestGuardVerdict, messages, bootstrapMessageCount, steps + 1);
                GatewayRequest finalRequest = new GatewayRequest(List.copyOf(messages), List.of(),
                        ToolChoiceMode.AUTO, CallProfile.RECOVERY_ANSWER);
                recordManifest(spec, run.getId(), ++manifestSeq, config, finalRequest, toolDefinitionsHash, warnings);
                ModelCallOutcome finalModelCall = callModel(config, finalRequest, standardFallback, fallbackAllowed,
                        remainingDuration(deadlineNanos));
                GatewayResponse finalResponse = finalModelCall.response();
                if (finalModelCall.fallbackActivated()) {
                    standardFallback = true;
                    executionMode = AgentExecutionMode.STANDARD_TOOL_FALLBACK;
                    if (!warnings.contains(AgentExecutionMode.STANDARD_TOOL_FALLBACK.name())) {
                        warnings.add(AgentExecutionMode.STANDARD_TOOL_FALLBACK.name());
                    }
                }
                usage.add(finalResponse);
                observeUsage(spec, finalResponse);
                if (finalResponse.provider() != null) {
                    provider = finalResponse.provider();
                }
                if (finalResponse.model() != null) {
                    model = finalResponse.model();
                }
                finalContent = finalResponse.content();
                if (isBlank(finalContent)) {
                    throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                            "AI provider returned an empty response");
                }
            }

            boolean missedRequired = spec.requireToolCall() && !anyToolCallExecuted;
            if (missedRequired) {
                warnings.add("missed_required_tool");
                log.warn("Agent run missed required tool call turn={} run={}", spec.turnId(), run.getId());
            }
            stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_FINAL_DRAFTED);
            // P0 output check is a pass-through marker; the real buffered check lands with P3 output modes.
            stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_OUTPUT_CHECKED);
            stateMachine.complete(run.getId(), steps, toolCallCount);
            String runSummary = spec.completionPolicy().isRequired() && !completionToolSucceeded
                    ? "Agent run ended before required completion tool succeeded"
                    : "Agent run completed with " + toolCallCount + " controlled tool calls";
            stateMachine.annotate(run.getId(), executionMode.name(), runSummary);
            return new AgentRunResult(run.getId(), finalContent, provider, model,
                    usage.promptTokens(), usage.completionTokens(), usage.cacheHitTokens(),
                    steps, toolCallCount, missedRequired, List.copyOf(warnings), contestGuardVerdict,
                    executionMode);
        } catch (RuntimeException ex) {
            toolBroker.cancelRun(run.getId());
            stateMachine.fail(run.getId(), errorCodeOf(ex), steps, toolCallCount);
            throw ex;
        }
    }

    private String completionRecoveryInstruction(String requiredTool, boolean emptyResponse) {
        String prefix = emptyResponse
                ? "The previous model response exhausted its answer without a tool call. "
                : "The previous response was only an unfinished progress note. ";
        return prefix
                + "Server policy does not allow this workflow to end until " + requiredTool
                + " succeeds. Continue the controlled tool loop now: inspect the latest authoritative tool result, "
                + "call the next missing draft tool, repair failed gates, and call " + requiredTool
                + " only after every gate passes. Do not answer with more design prose before using a tool.";
    }

    /**
     * L3 second layer (§5.3, P3-4): re-fingerprint the assembled messages right
     * before a model call. Newly matched problems' rules are appended as one
     * trailing system message, which then persists in the list for every later
     * step of this run. Returns the merged verdict for L4 (P3-5).
     */
    private GuardVerdict applyContextLayerGuard(AgentRunRequest spec, ContestPolicyView contestPolicy,
                                                Set<Long> constrainedProblemIds, GuardVerdict mergedVerdict,
                                                List<GatewayMessage> messages, int bootstrapMessageCount,
                                                int agentStep) {
        ContextFingerprintGuard.Evaluation evaluation = contextGuard.evaluate(spec.turnId(), spec.userId(),
                spec.conversationId(), contestPolicy, constrainedProblemIds, messages, bootstrapMessageCount, agentStep);
        if (evaluation == null) {
            return mergedVerdict;
        }
        if (evaluation.injectionText() != null) {
            messages.add(GatewayMessage.system(evaluation.injectionText()));
            constrainedProblemIds.addAll(evaluation.newlyMatchedProblemIds());
        }
        return mergedVerdict == null ? evaluation.verdict() : mergedVerdict.mergedWith(evaluation.verdict());
    }

    private void observeUsage(AgentRunRequest spec, GatewayResponse response) {
        if (spec.usageObserver() == null) {
            return;
        }
        try {
            spec.usageObserver().accept(ModelUsage.from(response));
        } catch (RuntimeException ex) {
            // Statistical observation must never change the Agent's control flow.
            log.warn("agent model-usage observer failed turn={} error={}", spec.turnId(), ex.toString());
        }
    }

    private ModelCallOutcome callModel(AiModelEffectiveConfig config, GatewayRequest request,
                                       boolean standardFallback, boolean fallbackAllowed,
                                       Duration remainingRunTime) {
        if (remainingRunTime == null) {
            return callModelUnbounded(config, request, standardFallback, fallbackAllowed);
        }
        Future<ModelCallOutcome> future;
        try {
            future = boundedModelExecutor.submit(() ->
                    callModelUnbounded(config, request, standardFallback, fallbackAllowed));
        } catch (RejectedExecutionException ex) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "AI model capacity saturated; retry later");
        }
        try {
            return future.get(remainingRunTime.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Agent run deadline exceeded");
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Agent run was cancelled");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI model call failed");
        }
    }

    private ModelCallOutcome callModelUnbounded(AiModelEffectiveConfig config, GatewayRequest request,
                                                boolean standardFallback, boolean fallbackAllowed) {
        if (standardFallback) {
            return new ModelCallOutcome(modelGateway.callWithoutThinking(config, request), false);
        }
        try {
            return new ModelCallOutcome(modelGateway.call(config, request), false);
        } catch (ThinkingModeUnavailableException ex) {
            if (!fallbackAllowed) {
                throw ex;
            }
            // Exactly one controlled downgrade. The original provider body and
            // reasoning are neither logged nor persisted.
            return new ModelCallOutcome(modelGateway.callWithoutThinking(config, request), true);
        }
    }

    private long deadlineNanos(LoopBudget budget) {
        if (budget == null || !budget.hasDeadline()) {
            return Long.MAX_VALUE;
        }
        long now = System.nanoTime();
        long duration;
        try {
            duration = budget.maxRunDuration().toNanos();
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
        return duration >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + duration;
    }

    private Duration remainingDuration(long deadlineNanos) {
        if (deadlineNanos == Long.MAX_VALUE) {
            return null;
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Agent run deadline exceeded");
        }
        return Duration.ofNanos(remaining);
    }

    private void ensureBeforeDeadline(long deadlineNanos) {
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Agent run deadline exceeded");
        }
    }

    private record ModelCallOutcome(GatewayResponse response, boolean fallbackActivated) {
    }

    @PreDestroy
    void shutdownModelExecutor() {
        boundedModelExecutor.shutdownNow();
    }

    private static ExecutorService createModelExecutor(AiProperties.Capacity capacity) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "ai-model-bounded-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(capacity.getModelCorePoolSize(), capacity.getModelMaxPoolSize(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity.getModelQueueCapacity()), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void recordManifest(AgentRunRequest spec, long runId, int callSeq, AiModelEffectiveConfig config,
                                GatewayRequest request, String toolDefinitionsHash, List<String> warnings) {
        StringBuilder contextMaterial = new StringBuilder();
        for (GatewayMessage message : request.messages()) {
            contextMaterial.append(message.role()).append('\n');
            if (message.content() != null) {
                contextMaterial.append(message.content());
            }
            contextMaterial.append('\n');
        }
        manifestService.record(spec.turnId(), runId, callSeq, config.model(), BootstrapContextBuilder.PROMPT_VERSION,
                spec.policySnapshotId(), spec.sections(), toolDefinitionsHash,
                ContextManifestService.sha256(contextMaterial.toString()), null, null, warnings);
    }

    /** Category budget overflow is returned to the model as data, never executed. */
    private String budgetExceededPayload(String toolName) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "BUDGET_EXCEEDED");
        payload.putNull("data");
        payload.put("tool", toolName == null ? "" : toolName);
        payload.put("instructionAllowed", false);
        payload.putArray("warnings").add("tool category budget exhausted; answer with what you have");
        return payload.toString();
    }

    private String errorCodeOf(RuntimeException ex) {
        if (ex instanceof DomainException domainException) {
            return domainException.errorCode().name();
        }
        return "AGENT_RUN_FAILURE";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
