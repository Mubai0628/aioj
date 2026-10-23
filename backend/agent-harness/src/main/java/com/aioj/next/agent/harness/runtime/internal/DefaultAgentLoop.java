package com.aioj.next.agent.harness.runtime.internal;

import com.aioj.next.agent.harness.context.api.ModelCallManifest;
import com.aioj.next.agent.harness.runtime.api.AgentLoop;
import com.aioj.next.agent.harness.runtime.api.AgentScenario;
import com.aioj.next.agent.harness.runtime.api.CompletionPolicy;
import com.aioj.next.agent.harness.runtime.api.FinalDecision;
import com.aioj.next.agent.harness.runtime.api.HarnessMessage;
import com.aioj.next.agent.harness.runtime.api.LoopInterceptor;
import com.aioj.next.agent.harness.runtime.api.ModelCall;
import com.aioj.next.agent.harness.runtime.api.ModelCallException;
import com.aioj.next.agent.harness.runtime.api.ModelPort;
import com.aioj.next.agent.harness.runtime.api.ModelTool;
import com.aioj.next.agent.harness.runtime.api.ModelTurn;
import com.aioj.next.agent.harness.runtime.api.OutgoingMessages;
import com.aioj.next.agent.harness.runtime.api.RunBudget;
import com.aioj.next.agent.harness.runtime.api.RunFailure;
import com.aioj.next.agent.harness.runtime.api.RunListener;
import com.aioj.next.agent.harness.runtime.api.RunResult;
import com.aioj.next.agent.harness.runtime.api.RunSpec;
import com.aioj.next.agent.harness.runtime.api.RunStatus;
import com.aioj.next.agent.harness.runtime.api.TokenUsage;
import com.aioj.next.agent.harness.runtime.api.ToolChoice;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The domain-free agent loop (plan §3.3), taken from ai-service's
 * AgentRuntime: model steps with tool calls in between, bounded by the run
 * budget and deadline, with the scenario's interceptors and completion policy
 * at fixed points. Every way a run can end becomes a {@link RunResult}.
 */
public final class DefaultAgentLoop implements AgentLoop {
    static final String REQUIRE_TOOL_INSTRUCTION = "Server policy requires at least one relevant tool call before "
            + "the final answer for this turn. Call the most relevant tool now.";
    static final String EMPTY_ANSWER_INSTRUCTION = "Your previous reply was empty. Provide the complete final answer "
            + "to the user now, in the user's language.";
    static final String BUDGET_END_INSTRUCTION = "The run budget ended before the server's completion condition was "
            + "met. Do not continue the task and do not expose private reasoning. Return only a short, factual "
            + "progress summary that names what is still missing.";

    private final ModelPort modelPort;
    private final ToolRuntime toolRuntime;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultAgentLoop(ModelPort modelPort, ToolRuntime toolRuntime) {
        this.modelPort = modelPort;
        this.toolRuntime = toolRuntime;
    }

    @Override
    public RunResult run(RunSpec spec) {
        Run run = new Run(spec);
        RunResult result = run.execute();
        run.listen(listener -> listener.onRunFinished(run.state, result));
        return result;
    }

    /** Thrown inside a run to end it; never leaves {@link Run#execute()}. */
    private static final class Stop extends RuntimeException {
        private final RunStatus status;
        private final RunFailure failure;

        Stop(RunStatus status, RunFailure.Kind kind, String detail) {
            super(detail, null, false, false);
            this.status = status;
            this.failure = new RunFailure(kind, detail, null);
        }
    }

    private final class Run {
        private final RunSpec spec;
        private final AgentScenario scenario;
        private final RunBudget budget;
        private final LoopState state;
        private final BudgetLedger ledger;
        private final List<HarnessMessage> messages;
        private final int startingMessageCount;
        private final boolean hasDeadline;
        private final long deadlineNanos;
        private List<ToolDescriptor> runTools = List.of();
        private int callSeq;
        private int toolSeq;
        private boolean anyToolExecuted;
        private boolean simulatedRequireUsed;
        private boolean emptyRecoveryUsed;
        private boolean toolBudgetExhausted;
        private TokenUsage usage = TokenUsage.ZERO;
        private String provider;
        private String model;

        Run(RunSpec spec) {
            this.spec = spec;
            this.scenario = spec.scenario();
            this.budget = scenario.budget();
            this.state = new LoopState(spec);
            this.ledger = new BudgetLedger(budget);
            this.messages = new ArrayList<>(spec.messages());
            this.startingMessageCount = messages.size();
            this.hasDeadline = budget.hasDeadline();
            this.deadlineNanos = hasDeadline ? System.nanoTime() + budget.maxRunDuration().toNanos() : 0L;
        }

        RunResult execute() {
            spec.cancellation().onCancel(() -> toolRuntime.cancelRun(spec.runId()));
            listen(listener -> listener.onRunStarted(state));
            try {
                runTools = toolRuntime.visibleTools(runScopes());
                String content = loop();
                boolean missedRequired = spec.requireToolCall() && !anyToolExecuted;
                if (missedRequired) {
                    state.warn("missed_required_tool");
                }
                return result(RunStatus.COMPLETED, content, null, missedRequired);
            } catch (Stop stop) {
                toolRuntime.cancelRun(spec.runId());
                return result(stop.status, "", stop.failure, false);
            } catch (ModelCallException ex) {
                toolRuntime.cancelRun(spec.runId());
                if (ex.kind() == ModelCallException.Kind.CANCELLED || spec.cancellation().isCancelled()) {
                    return result(RunStatus.CANCELLED, "",
                            new RunFailure(RunFailure.Kind.CANCELLED, "run cancelled", ex.kind()), false);
                }
                return result(RunStatus.FAILED, "",
                        new RunFailure(RunFailure.Kind.MODEL_ERROR, ex.getMessage(), ex.kind()), false);
            } catch (RuntimeException ex) {
                toolRuntime.cancelRun(spec.runId());
                return result(RunStatus.FAILED, "",
                        new RunFailure(RunFailure.Kind.INTERNAL_ERROR, ex.getClass().getSimpleName(), null), false);
            }
        }

        private String loop() {
            for (int step = 1; step <= budget.maxSteps(); step++) {
                checkpoint();
                state.step(step);
                List<ToolDescriptor> offered = offeredTools();
                ToolChoice choice = spec.requireToolCall() && !anyToolExecuted && modelPort.supportsRequiredToolChoice()
                        ? ToolChoice.REQUIRED : ToolChoice.AUTO;
                CompletionPolicy completion = scenario.completionPolicy();
                String profile = emptyRecoveryUsed && completion.satisfied(state)
                        ? scenario.recoveryProfile() : scenario.profile();
                ModelTurn turn = callModel(offered, choice, profile);

                if (!turn.hasToolCalls()) {
                    CompletionPolicy.Verdict verdict = completion.onAnswer(state, turn.content());
                    if (!verdict.accept()) {
                        if (blank(turn.content())) {
                            if (emptyRecoveryUsed) {
                                throw new Stop(RunStatus.FAILED, RunFailure.Kind.EMPTY_RESPONSE,
                                        "empty answer before the completion condition was met");
                            }
                            emptyRecoveryUsed = true;
                            state.warn("empty_completion_recovery");
                        } else {
                            // The unfinished prose is not replayed; it would only invite more of the same.
                            state.warn("premature_completion_recovered");
                        }
                        messages.add(HarnessMessage.system(verdict.instruction()));
                        continue;
                    }
                    if (spec.requireToolCall() && !anyToolExecuted && !simulatedRequireUsed
                            && !modelPort.supportsRequiredToolChoice()) {
                        simulatedRequireUsed = true;
                        messages.add(HarnessMessage.assistant(turn.content(), turn.reasoningContent(), List.of()));
                        messages.add(HarnessMessage.system(REQUIRE_TOOL_INSTRUCTION));
                        continue;
                    }
                    if (blank(turn.content())) {
                        if (emptyRecoveryUsed) {
                            throw new Stop(RunStatus.FAILED, RunFailure.Kind.EMPTY_RESPONSE, "model returned an empty answer");
                        }
                        // A model can spend its whole output budget thinking; ask once more. The empty
                        // message is not added to the conversation.
                        emptyRecoveryUsed = true;
                        state.warn("empty_completion_recovery");
                        messages.add(HarnessMessage.system(EMPTY_ANSWER_INSTRUCTION));
                        continue;
                    }
                    String decided = decideFinal(turn.content());
                    if (decided != null) {
                        return decided;
                    }
                    continue;
                }

                messages.add(HarnessMessage.assistant(turn.content(), turn.reasoningContent(), turn.toolCalls()));
                runToolCalls(turn.toolCalls(), offered);
                if (toolBudgetExhausted) {
                    state.warn("tool_budget_exhausted");
                    break;
                }
            }
            return answerOnly();
        }

        /** The step or tool budget ran out: one more call, without tools, for an answer. */
        private String answerOnly() {
            checkpoint();
            if (!toolBudgetExhausted) {
                state.warn("step_budget_exhausted");
            }
            CompletionPolicy completion = scenario.completionPolicy();
            if (!completion.satisfied(state)) {
                state.warn("required_completion_tool_missing");
                String instruction = completion.budgetEndInstruction();
                messages.add(HarnessMessage.system(instruction == null ? BUDGET_END_INSTRUCTION : instruction));
            }
            while (true) {
                ModelTurn turn = callModel(List.of(), ToolChoice.AUTO, scenario.recoveryProfile());
                if (blank(turn.content())) {
                    throw new Stop(RunStatus.FAILED, RunFailure.Kind.EMPTY_RESPONSE, "model returned an empty answer");
                }
                String decided = decideFinal(turn.content());
                if (decided != null) {
                    return decided;
                }
                checkpoint();
            }
        }

        private void runToolCalls(List<ToolCallRequest> calls, List<ToolDescriptor> offered) {
            Map<String, ToolDescriptor> offeredByName = new LinkedHashMap<>();
            offered.forEach(descriptor -> offeredByName.put(descriptor.name(), descriptor));
            for (ToolCallRequest call : calls) {
                if (state.toolCallCount() >= budget.maxToolCalls()) {
                    toolBudgetExhausted = true;
                    break;
                }
                checkpoint();
                ToolDescriptor descriptor = offeredByName.get(call.toolName());
                if (descriptor == null) {
                    // Only the tools offered in this step may run, whatever the model asks for.
                    messages.add(HarnessMessage.toolResult(call.callId(), notOfferedPayload(call.toolName())));
                    continue;
                }
                if (ledger.exhausted(descriptor.budgetCategory())) {
                    state.warn("tool_category_budget_exhausted");
                    messages.add(HarnessMessage.toolResult(call.callId(), budgetExceededPayload(call.toolName())));
                    continue;
                }
                ToolOutcome outcome = toolRuntime.execute(spec.toolContext(), ++toolSeq, call, remaining());
                state.countToolCall();
                anyToolExecuted = true;
                ledger.charge(descriptor.budgetCategory());
                if (outcome.result().ok()) {
                    state.succeeded(call.toolName());
                }
                listen(listener -> listener.onToolOutcome(state, call, outcome));
                for (LoopInterceptor interceptor : spec.interceptors()) {
                    intercept(interceptor, () -> {
                        interceptor.afterToolResult(state, call, outcome);
                        return null;
                    });
                }
                messages.add(HarnessMessage.toolResult(call.callId(), outcome.modelPayload()));
                if (outcome.failedClosed()) {
                    throw new Stop(RunStatus.FAILED, RunFailure.Kind.TOOL_FAILED_CLOSED,
                            call.toolName() + " failed with " + outcome.result().status());
                }
            }
        }

        /** Returns the final text, or null when an interceptor sent the answer back for its one retry. */
        private String decideFinal(String content) {
            for (LoopInterceptor interceptor : spec.interceptors()) {
                FinalDecision decision = intercept(interceptor, () -> interceptor.beforeFinal(state, content));
                if (decision == null || decision.type() == FinalDecision.Type.ACCEPT) {
                    continue;
                }
                if (decision.type() == FinalDecision.Type.REPLACE) {
                    state.warn("final_answer_replaced");
                    return decision.text();
                }
                if (state.finalRetries() >= 1) {
                    throw new Stop(RunStatus.FAILED, RunFailure.Kind.INTERCEPTOR_ERROR,
                            "final answer rejected again after its one retry");
                }
                state.countFinalRetry();
                state.warn("final_answer_retried");
                messages.add(HarnessMessage.system(decision.text()));
                return null;
            }
            return content;
        }

        private ModelTurn callModel(List<ToolDescriptor> offered, ToolChoice choice, String profile) {
            checkpoint();
            OutgoingMessages outgoing = new Outgoing();
            for (LoopInterceptor interceptor : spec.interceptors()) {
                intercept(interceptor, () -> {
                    interceptor.beforeModelCall(state, outgoing);
                    return null;
                });
            }
            List<ModelTool> tools = offered.stream()
                    .map(descriptor -> new ModelTool(descriptor.name(), descriptor.description(), descriptor.inputSchema()))
                    .toList();
            ModelCall call = new ModelCall(messages, tools, choice, profile, remaining(), spec.cancellation());
            int seq = ++callSeq;
            try {
                spec.manifestSink().record(new ModelCallManifest(spec.runId(), seq, profile, call.messages().size(),
                        tools.stream().map(ModelTool::name).toList(), state.warnings()));
            } catch (RuntimeException ex) {
                state.warn("manifest_failed");
            }
            listen(listener -> listener.onModelCall(state, seq, call));
            ModelTurn turn = spec.streaming()
                    ? modelPort.stream(call, delta -> listen(listener -> listener.onTextDelta(state, delta)))
                    : modelPort.complete(call);
            if (turn == null) {
                throw new ModelCallException(ModelCallException.Kind.INVALID_RESPONSE, "model port returned no turn");
            }
            usage = usage.plus(turn.usage());
            provider = turn.provider() == null ? provider : turn.provider();
            model = turn.model() == null ? model : turn.model();
            turn.warnings().forEach(state::warn);
            listen(listener -> listener.onModelTurn(state, seq, turn));
            return turn;
        }

        /** Scenario-filtered tools of the run; an interceptor can remove tools but never add one. */
        private List<ToolDescriptor> offeredTools() {
            List<ToolDescriptor> tools = runTools;
            for (LoopInterceptor interceptor : spec.interceptors()) {
                List<ToolDescriptor> current = tools;
                List<ToolDescriptor> filtered = intercept(interceptor, () -> interceptor.filterTools(state, current));
                tools = filtered == null ? List.of() : filtered;
            }
            Set<String> allowed = new HashSet<>();
            runTools.forEach(descriptor -> allowed.add(descriptor.name()));
            return tools.stream().filter(descriptor -> allowed.contains(descriptor.name())).toList();
        }

        private Set<String> runScopes() {
            Set<String> scopes = new HashSet<>(spec.toolContext().grantedScopes());
            scopes.retainAll(scenario.toolScopes());
            return scopes;
        }

        private void checkpoint() {
            if (spec.cancellation().isCancelled()) {
                throw new Stop(RunStatus.CANCELLED, RunFailure.Kind.CANCELLED, "run cancelled");
            }
            if (hasDeadline && System.nanoTime() - deadlineNanos >= 0) {
                throw new Stop(RunStatus.DEADLINE_EXCEEDED, RunFailure.Kind.DEADLINE_EXCEEDED,
                        "run exceeded " + budget.maxRunDuration());
            }
        }

        private Duration remaining() {
            return hasDeadline ? Duration.ofNanos(Math.max(0L, deadlineNanos - System.nanoTime())) : null;
        }

        private <T> T intercept(LoopInterceptor interceptor, Supplier<T> hook) {
            try {
                return hook.get();
            } catch (Stop stop) {
                throw stop;
            } catch (RuntimeException ex) {
                throw new Stop(RunStatus.FAILED, RunFailure.Kind.INTERCEPTOR_ERROR,
                        interceptor.getClass().getSimpleName() + " failed: " + ex.getClass().getSimpleName());
            }
        }

        void listen(Consumer<RunListener> event) {
            try {
                event.accept(spec.listener());
            } catch (RuntimeException ex) {
                state.warn("listener_failed");
            }
        }

        private RunResult result(RunStatus status, String content, RunFailure failure, boolean missedRequired) {
            boolean satisfied;
            try {
                satisfied = status == RunStatus.COMPLETED && scenario.completionPolicy().satisfied(state);
            } catch (RuntimeException ex) {
                satisfied = false;
            }
            return new RunResult(status, content, state.step(), state.toolCallCount(), usage, state.warnings(),
                    missedRequired, satisfied, failure, provider, model);
        }

        private String budgetExceededPayload(String toolName) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("status", "BUDGET_EXCEEDED");
            payload.putNull("data");
            payload.put("tool", toolName == null ? "" : toolName);
            payload.put("instructionAllowed", false);
            payload.putArray("warnings").add("tool category budget exhausted; answer with what you have");
            return payload.toString();
        }

        private String notOfferedPayload(String toolName) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("status", "NOT_FOUND");
            payload.put("errorCode", "TOOL_NOT_OFFERED");
            payload.put("errorMessage", "Tool " + toolName + " is not available in this step");
            payload.put("truncated", false);
            payload.put("instructionAllowed", false);
            return payload.toString();
        }

        /** What interceptors see before a model call: the conversation so far, open to appending only. */
        private final class Outgoing implements OutgoingMessages {
            @Override
            public List<HarnessMessage> view() {
                return Collections.unmodifiableList(messages);
            }

            @Override
            public int startingMessageCount() {
                return startingMessageCount;
            }

            @Override
            public void append(HarnessMessage message) {
                messages.add(java.util.Objects.requireNonNull(message, "message"));
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
