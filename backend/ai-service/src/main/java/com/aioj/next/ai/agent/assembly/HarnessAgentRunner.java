package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.AgentLoops;
import com.aioj.next.agent.harness.runtime.api.AgentScenario;
import com.aioj.next.agent.harness.runtime.api.CompletionPolicy;
import com.aioj.next.agent.harness.runtime.api.HarnessMessage;
import com.aioj.next.agent.harness.runtime.api.LoopInterceptor;
import com.aioj.next.agent.harness.runtime.api.ModelCallException;
import com.aioj.next.agent.harness.runtime.api.RunBudget;
import com.aioj.next.agent.harness.runtime.api.RunFailure;
import com.aioj.next.agent.harness.runtime.api.RunResult;
import com.aioj.next.agent.harness.runtime.api.RunSpec;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;
import com.aioj.next.ai.agent.context.ContextManifestService;
import com.aioj.next.ai.agent.context.ContextSectionRenderer;
import com.aioj.next.ai.agent.guard.ContextFingerprintGuard;
import com.aioj.next.ai.agent.runtime.AgentRunStateMachine;
import com.aioj.next.ai.agent.runtime.AgentRuntime;
import com.aioj.next.ai.agent.runtime.LoopBudget;
import com.aioj.next.ai.agent.runtime.ProviderUsage;
import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.persistence.entity.AiAgentRunEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs an agent request on the Harness loop (plan §3.3, T1.5): the per-run model port,
 * the ai_agent_runs row and its progress, the run's interceptors (C5 tool visibility,
 * the L3 context layer, and whatever the caller adds), the domain-free loop, and back to
 * the result and exceptions the callers of {@link AgentRuntime} always got. A run with a
 * required completion tool (problem drafts) gets that gate as its completion policy.
 */
@Service
public class HarnessAgentRunner {
    private static final Logger log = LoggerFactory.getLogger(HarnessAgentRunner.class);

    private final ModelClient modelClient;
    private final ToolRuntime toolRuntime;
    private final ContextSectionRenderer renderer;
    private final ContextManifestService manifestService;
    private final AgentRunStateMachine stateMachine;
    private final ContextFingerprintGuard contextGuard;

    public HarnessAgentRunner(ModelClient modelClient, ToolRuntime toolRuntime, ContextSectionRenderer renderer,
                              ContextManifestService manifestService, AgentRunStateMachine stateMachine,
                              ContextFingerprintGuard contextGuard) {
        this.modelClient = modelClient;
        this.toolRuntime = toolRuntime;
        this.renderer = renderer;
        this.manifestService = manifestService;
        this.stateMachine = stateMachine;
        this.contextGuard = contextGuard;
    }

    public AgentRuntime.AgentRunResult run(AgentRuntime.AgentRunRequest spec) {
        boolean fallbackAllowed = spec.allowStandardToolFallback() || spec.profile() == CallProfile.PROBLEM_DRAFT;
        CallOptions options = CallOptions.of("agent." + spec.profile().name().toLowerCase(Locale.ROOT))
                .withCorrelation(spec.turnId(), spec.userId());
        ModelClientPort port = ModelClientPort.open(modelClient, spec.modelScope().name(), spec.profile(), options,
                fallbackAllowed, response -> observeUsage(spec, response));
        ModelEndpointConfig endpoint = port.endpoint();
        LoopBudget budget = spec.budget();
        AiAgentRunEntity run = stateMachine.start(spec.turnId(), spec.conversationId(), spec.userId(),
                endpoint.provider().configName(), endpoint.model(), spec.policySnapshotId(), spec.outputMode(), budget);
        stateMachine.bindWorkspace(run.getId(), spec.serverBindings().get("workspaceId"), budget.maxRunDuration());

        DraftCompletionPolicy gate = spec.completionPolicy().isRequired()
                ? new DraftCompletionPolicy(spec.completionPolicy().requiredSuccessfulToolName(), port,
                        port.fallbackAllowed(), spec.turnId())
                : null;
        List<HarnessMessage> starting = HarnessMessages.fromChat(renderer.render(spec.sections()));
        InterceptorFailures failures = new InterceptorFailures();
        ContextLayerGuard contextLayer = new ContextLayerGuard(contextGuard, spec.turnId(), spec.userId(),
                spec.conversationId(), spec.contestPolicy(), spec.messageLayerVerdict());
        List<LoopInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new ExecutionModeWarning(port));
        interceptors.add(new ContestToolFilter(spec.contestPolicy()));
        interceptors.add(contextLayer);
        for (LoopInterceptor interceptor : spec.interceptors()) {
            if (interceptor instanceof ContextVerdictAware aware) {
                aware.useContextVerdict(contextLayer::mergedVerdict);
            }
            interceptors.add(interceptor);
        }
        RunSpec.Builder runSpec = RunSpec.builder(String.valueOf(run.getId()), scenario(spec, gate), toolContext(spec, run))
                .messages(starting)
                .requireToolCall(spec.requireToolCall())
                .listener(new RunProgressListener(stateMachine, manifestService, run.getId(), spec.turnId(),
                        endpoint.model(), spec.policySnapshotId(), spec.sections(), spec.contextWarnings()));
        interceptors.forEach(interceptor -> runSpec.interceptor(failures.watch(interceptor)));
        stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_CONTEXT_PLANNED);
        Instant deadline = budget.hasDeadline() ? Instant.now().plus(budget.maxRunDuration()) : null;

        RunResult result = AgentLoops.create(port, toolRuntime).run(runSpec.build());

        if (!result.completed()) {
            RuntimeException failure = failureOf(result, port, failures, deadline,
                    gate != null && gate.emptyTwiceBeforeGate());
            stateMachine.fail(run.getId(), errorCodeOf(failure), result.steps(), result.toolCallCount());
            throw failure;
        }
        if (result.missedRequiredTool()) {
            log.warn("Agent run missed required tool call turn={} run={}", spec.turnId(), run.getId());
        }
        if (result.warnings().contains("empty_completion_recovery")) {
            log.warn("Agent run recovered from an empty completion turn={} run={}", spec.turnId(), run.getId());
        }
        stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_FINAL_DRAFTED);
        stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_OUTPUT_CHECKED);
        stateMachine.complete(run.getId(), result.steps(), result.toolCallCount());
        stateMachine.annotate(run.getId(), port.executionMode().name(), gate != null && !result.completionSatisfied()
                ? "Agent run ended before required completion tool succeeded"
                : "Agent run completed with " + result.toolCallCount() + " controlled tool calls");
        return new AgentRuntime.AgentRunResult(run.getId(), result.content(),
                result.provider() == null ? endpoint.provider().configName() : result.provider(),
                result.model() == null ? endpoint.model() : result.model(),
                result.usage().inputTokens(), result.usage().outputTokens(), result.usage().cacheHitTokens(),
                result.steps(), result.toolCallCount(), result.missedRequiredTool(), result.warnings(),
                contextLayer.mergedVerdict(), port.executionMode());
    }

    private static AgentScenario scenario(AgentRuntime.AgentRunRequest spec, DraftCompletionPolicy gate) {
        return new AgentScenario(gate == null ? AgentScenarios.TUTOR : AgentScenarios.PROBLEM_DRAFT,
                spec.grantedScopes(), List.of(),
                gate == null ? CompletionPolicy.anyAnswer() : gate, runBudget(spec.budget()), spec.profile().name(),
                CallProfile.RECOVERY_ANSWER.name());
    }

    /** The loop budget's categories, with batch execution also charged to the plain sandbox budget. */
    static RunBudget runBudget(LoopBudget budget) {
        return RunBudget.builder(budget.maxAgentSteps(), budget.maxToolCalls())
                .limit("search", budget.maxSearchCalls())
                .limit("fetch", budget.maxFetchCalls())
                .limit("sandbox", budget.maxSandboxCalls())
                .limit("testcase_generate", budget.maxTestcaseGenerations())
                .limit("testcase_run", budget.maxTestcasePackageRuns())
                .within("testcase_generate", "sandbox")
                .within("testcase_run", "sandbox")
                .maxRunDuration(Duration.ofMillis(budget.maxRunDurationMillis()))
                .build();
    }

    private static ToolExecutionContext toolContext(AgentRuntime.AgentRunRequest spec, AiAgentRunEntity run) {
        Map<String, String> bindings = new LinkedHashMap<>(spec.serverBindings());
        bindings.put("agentRunId", String.valueOf(run.getId()));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(BridgedTool.TURN_SEQ, 0L);
        if (spec.contestPolicy() != null) {
            attributes.put(BridgedTool.CONTEST_POLICY, spec.contestPolicy());
        }
        return new ToolExecutionContext(String.valueOf(run.getId()), String.valueOf(spec.userId()),
                spec.conversationId(), spec.turnId(), spec.policySnapshotId(), spec.grantedScopes(), Instant.now(),
                spec.turnId(), bindings, attributes);
    }

    private static void observeUsage(AgentRuntime.AgentRunRequest spec, ModelResponse response) {
        if (spec.usageObserver() != null) {
            spec.usageObserver().accept(ProviderUsage.from(response));
        }
    }

    /** The exception the old loop threw for the same ending. */
    static RuntimeException failureOf(RunResult result, ModelClientPort port, InterceptorFailures failures,
                                      Instant deadline, boolean emptyBeforeGate) {
        RunFailure failure = result.failure();
        RunFailure.Kind kind = failure == null ? RunFailure.Kind.INTERNAL_ERROR : failure.kind();
        return switch (kind) {
            case MODEL_ERROR -> {
                if (deadline != null && failure.modelErrorKind() == ModelCallException.Kind.TIMEOUT
                        && !Instant.now().isBefore(deadline.minusMillis(50))) {
                    yield deadlineExceeded();
                }
                yield port.lastFailure().<RuntimeException>map(ex -> ex)
                        .orElseGet(() -> new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "AI model call failed"));
            }
            case EMPTY_RESPONSE -> new DomainException(ErrorCode.SERVICE_UNAVAILABLE, emptyBeforeGate
                    ? "AI provider returned an empty response before the required completion gate"
                    : "AI provider returned an empty response");
            case DEADLINE_EXCEEDED -> deadlineExceeded();
            case CANCELLED -> new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Agent run was cancelled");
            case INTERCEPTOR_ERROR -> failures.first()
                    .orElseGet(() -> new DomainException(ErrorCode.INTERNAL_ERROR, "Agent run failed"));
            case TOOL_FAILED_CLOSED -> new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                    "A required agent tool is unavailable");
            case INTERNAL_ERROR -> new DomainException(ErrorCode.INTERNAL_ERROR, "Agent run failed");
        };
    }

    private static DomainException deadlineExceeded() {
        return new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Agent run deadline exceeded");
    }

    private static String errorCodeOf(RuntimeException ex) {
        if (ex instanceof DomainException domainException) {
            return domainException.errorCode().name();
        }
        return "AGENT_RUN_FAILURE";
    }
}
