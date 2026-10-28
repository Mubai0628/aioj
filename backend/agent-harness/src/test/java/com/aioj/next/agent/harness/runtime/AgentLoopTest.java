package com.aioj.next.agent.harness.runtime;

import com.aioj.next.agent.harness.context.api.ModelCallManifest;
import com.aioj.next.agent.harness.runtime.api.AgentLoops;
import com.aioj.next.agent.harness.runtime.api.AgentScenario;
import com.aioj.next.agent.harness.runtime.api.CancellationToken;
import com.aioj.next.agent.harness.runtime.api.CompletionPolicy;
import com.aioj.next.agent.harness.runtime.api.FinalDecision;
import com.aioj.next.agent.harness.runtime.api.HarnessMessage;
import com.aioj.next.agent.harness.runtime.api.LoopContext;
import com.aioj.next.agent.harness.runtime.api.LoopInterceptor;
import com.aioj.next.agent.harness.runtime.api.ModelCall;
import com.aioj.next.agent.harness.runtime.api.ModelCallException;
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
import com.aioj.next.agent.harness.testing.ScriptedModelPort;
import com.aioj.next.agent.harness.testing.Tools;
import com.aioj.next.agent.harness.tool.api.AgentTool;
import com.aioj.next.agent.harness.tool.api.BulkheadLimits;
import com.aioj.next.agent.harness.tool.api.FailurePolicy;
import com.aioj.next.agent.harness.tool.api.ToolAuditSink;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolCatalog;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolOutcome;
import com.aioj.next.agent.harness.tool.api.ToolResult;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;
import com.aioj.next.agent.harness.tool.api.ToolRuntimes;
import com.aioj.next.agent.harness.tool.api.ToolStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aioj.next.agent.harness.testing.ScriptedModelPort.call;
import static org.assertj.core.api.Assertions.assertThat;

/** The generic loop against a scripted model and real tool pipeline (plan T1.5 skeleton). */
class AgentLoopTest {
    private static final String SYSTEM = "You are helpful.";
    private static final String USER = "Explain monotonic queues.";

    private final List<ToolRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void closeRuntimes() {
        runtimes.forEach(ToolRuntime::close);
    }

    @Test
    void aToolCallAndItsResultGoBackToTheModelBeforeTheAnswer() {
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        ScriptedModelPort model = new ScriptedModelPort(false)
                .toolCalls(call("c1", "kb.lookup", "{\"query\":\"deque\"}"))
                .answer("done");

        RunResult result = run(model, List.of(lookup), spec(scenario(budget(6, 4)), false));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.content()).isEqualTo("done");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(result.usage()).isEqualTo(new TokenUsage(200, 20, 100));
        assertThat(result.provider()).isEqualTo("fake");
        ModelCall first = model.call(1);
        assertThat(first.tools()).extracting(ModelTool::name).containsExactly("kb.lookup");
        assertThat(first.toolChoice()).isEqualTo(ToolChoice.AUTO);
        assertThat(first.profile()).isEqualTo("normal");
        List<HarnessMessage> second = model.call(2).messages();
        assertThat(second).extracting(HarnessMessage::role).containsExactly(HarnessMessage.Role.SYSTEM,
                HarnessMessage.Role.USER, HarnessMessage.Role.ASSISTANT, HarnessMessage.Role.TOOL);
        assertThat(second.get(2).reasoningContent()).as("reasoning goes back with the tool call").isEqualTo("reasoning-1");
        assertThat(second.get(2).toolCalls()).containsExactly(call("c1", "kb.lookup", "{\"query\":\"deque\"}"));
        assertThat(second.get(3).toolCallId()).isEqualTo("c1");
        assertThat(second.get(3).content()).contains("\"status\":\"SUCCESS\"", "\"echo\"", "\"instructionAllowed\":false");
    }

    @Test
    void theToolCallBudgetEndsWithAnAnswerOnlyCall() {
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        ScriptedModelPort model = new ScriptedModelPort(false)
                .toolCalls(call("c1", "kb.lookup", "{\"query\":\"a\"}"), call("c2", "kb.lookup", "{\"query\":\"b\"}"))
                .answer("summary");

        RunResult result = run(model, List.of(lookup), spec(scenario(budget(6, 1)), false));

        assertThat(result.content()).isEqualTo("summary");
        assertThat(lookup.calls()).isEqualTo(1);
        assertThat(result.warnings()).contains("tool_budget_exhausted").doesNotContain("step_budget_exhausted");
        ModelCall last = model.call(2);
        assertThat(last.tools()).isEmpty();
        assertThat(last.profile()).isEqualTo("recovery");
    }

    @Test
    void aChildCategoryCountsAgainstItsParent() {
        Tools.Fake single = Tools.descriptor("code.run").category("execute").echo();
        Tools.Fake batch = Tools.descriptor("code.run_batch").category("execute.batch").echo();
        RunBudget budget = RunBudget.builder(6, 10).limit("execute", 2).within("execute.batch", "execute").build();
        ScriptedModelPort model = new ScriptedModelPort(false)
                .toolCalls(call("c1", "code.run_batch", "{\"query\":\"1\"}"),
                        call("c2", "code.run_batch", "{\"query\":\"2\"}"),
                        call("c3", "code.run", "{\"query\":\"3\"}"))
                .answer("ok");

        RunResult result = run(model, List.of(single, batch), spec(scenario(budget), false));

        assertThat(batch.calls()).isEqualTo(2);
        assertThat(single.calls()).as("the parent budget is used up").isZero();
        assertThat(result.toolCallCount()).isEqualTo(2);
        assertThat(result.warnings()).contains("tool_category_budget_exhausted");
        assertThat(model.call(2).messages().get(5).content()).contains("\"status\":\"BUDGET_EXCEEDED\"", "code.run");
    }

    @Test
    void theStepBudgetEndsWithAnAnswerOnlyCall() {
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        ScriptedModelPort model = new ScriptedModelPort(false)
                .toolCalls(call("c1", "kb.lookup", "{\"query\":\"a\"}"))
                .toolCalls(call("c2", "kb.lookup", "{\"query\":\"b\"}"))
                .answer("what I have");

        RunResult result = run(model, List.of(lookup), spec(scenario(budget(2, 10)), false));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.content()).isEqualTo("what I have");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.warnings()).contains("step_budget_exhausted");
        assertThat(model.calls()).hasSize(3);
        assertThat(model.call(3).tools()).isEmpty();
    }

    @Test
    void aRequiredToolCallIsAskedForOnceWhenThePortCannotEnforceIt() {
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        ScriptedModelPort model = new ScriptedModelPort(false)
                .answer("from memory")
                .toolCalls(call("c1", "kb.lookup", "{\"query\":\"a\"}"))
                .answer("checked");

        RunResult result = run(model, List.of(lookup), spec(scenario(budget(6, 4)), true));

        assertThat(result.content()).isEqualTo("checked");
        assertThat(result.missedRequiredTool()).isFalse();
        assertThat(model.call(1).toolChoice()).isEqualTo(ToolChoice.AUTO);
        List<HarnessMessage> second = model.call(2).messages();
        assertThat(second.get(2).content()).isEqualTo("from memory");
        assertThat(second.get(3).content()).contains("requires at least one relevant tool call");
    }

    @Test
    void aNativeRequiredToolChoiceIsSentUntilAToolHasRun() {
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        ScriptedModelPort model = new ScriptedModelPort(true)
                .toolCalls(call("c1", "kb.lookup", "{\"query\":\"a\"}"))
                .answer("checked");

        run(model, List.of(lookup), spec(scenario(budget(6, 4)), true));

        assertThat(model.call(1).toolChoice()).isEqualTo(ToolChoice.REQUIRED);
        assertThat(model.call(2).toolChoice()).isEqualTo(ToolChoice.AUTO);
    }

    @Test
    void anEmptyAnswerIsRetriedOnceWithTheRecoveryProfile() {
        ScriptedModelPort model = new ScriptedModelPort(false).answer("").answer("ok");

        RunResult result = run(model, List.of(), spec(scenario(budget(6, 4)), false));

        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.warnings()).contains("empty_completion_recovery");
        assertThat(model.call(2).profile()).isEqualTo("recovery");
        assertThat(model.call(2).messages()).last().extracting(HarnessMessage::content).asString()
                .contains("previous reply was empty");
    }

    @Test
    void twoEmptyAnswersFailTheRun() {
        ScriptedModelPort model = new ScriptedModelPort(false).answer("").answer(" ");

        RunResult result = run(model, List.of(), spec(scenario(budget(6, 4)), false));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.failure().kind()).isEqualTo(RunFailure.Kind.EMPTY_RESPONSE);
    }

    @Test
    void aCompletionPolicyKeepsTheRunGoingUntilItsToolSucceeds() {
        Tools.Fake finish = Tools.descriptor("work.finish").echo();
        AgentScenario workflow = new AgentScenario("workflow", Set.of("CHAT"), List.of(),
                CompletionPolicy.requireSuccessfulTool("work.finish", "Call work.finish now."),
                budget(6, 4), "normal", "recovery");
        ScriptedModelPort model = new ScriptedModelPort(false)
                .answer("I think I am done")
                .toolCalls(call("c1", "work.finish", "{\"query\":\"x\"}"))
                .answer("finished");

        RunResult result = run(model, List.of(finish), spec(workflow, false));

        assertThat(result.content()).isEqualTo("finished");
        assertThat(result.completionSatisfied()).isTrue();
        assertThat(result.warnings()).contains("premature_completion_recovered");
        List<HarnessMessage> second = model.call(2).messages();
        assertThat(second).extracting(HarnessMessage::content).doesNotContain("I think I am done")
                .contains("Call work.finish now.");
    }

    @Test
    void theBudgetEndingBeforeTheRequiredToolIsReported() {
        Tools.Fake step = Tools.descriptor("work.step").echo();
        Tools.Fake finish = Tools.descriptor("work.finish").echo();
        AgentScenario workflow = new AgentScenario("workflow", Set.of("CHAT"), List.of(),
                CompletionPolicy.requireSuccessfulTool("work.finish", "Call work.finish now."),
                budget(1, 4), "normal", "recovery");
        ScriptedModelPort model = new ScriptedModelPort(false)
                .toolCalls(call("c1", "work.step", "{\"query\":\"x\"}"))
                .answer("progress so far");

        RunResult result = run(model, List.of(step, finish), spec(workflow, false));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.completionSatisfied()).isFalse();
        assertThat(result.warnings()).contains("step_budget_exhausted", "required_completion_tool_missing");
        assertThat(model.call(2).messages()).last().extracting(HarnessMessage::content).asString()
                .contains("completion condition was met");
    }

    @Test
    void aPolicyCanWordTheBudgetEndingItself() {
        Tools.Fake step = Tools.descriptor("work.step").echo();
        CompletionPolicy required = CompletionPolicy.requireSuccessfulTool("work.finish", "Call work.finish now.");
        CompletionPolicy worded = new CompletionPolicy() {
            @Override
            public Verdict onAnswer(LoopContext context, String answer) {
                return required.onAnswer(context, answer);
            }

            @Override
            public boolean satisfied(LoopContext context) {
                return required.satisfied(context);
            }

            @Override
            public String budgetEndInstruction() {
                return "Summarize what work.finish still needs.";
            }
        };
        AgentScenario workflow = new AgentScenario("workflow", Set.of("CHAT"), List.of(), worded, budget(1, 4),
                "normal", "recovery");
        ScriptedModelPort model = new ScriptedModelPort(false)
                .toolCalls(call("c1", "work.step", "{\"query\":\"x\"}"))
                .answer("progress so far");

        RunResult result = run(model, List.of(step), spec(workflow, false));

        assertThat(result.completionSatisfied()).isFalse();
        assertThat(model.call(2).messages()).last().extracting(HarnessMessage::content)
                .isEqualTo("Summarize what work.finish still needs.");
    }

    @Test
    void interceptorsFilterToolsAppendMessagesAndSeeResults() {
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        Tools.Fake hidden = Tools.descriptor("kb.hidden").echo();
        List<String> seen = new CopyOnWriteArrayList<>();
        LoopInterceptor interceptor = new LoopInterceptor() {
            @Override
            public List<ToolDescriptor> filterTools(LoopContext context, List<ToolDescriptor> tools) {
                return tools.stream().filter(tool -> !tool.name().equals("kb.hidden")).toList();
            }

            @Override
            public void beforeModelCall(LoopContext context, OutgoingMessages messages) {
                if (context.step() == 1) {
                    messages.append(HarnessMessage.system("constraint for step 1"));
                }
            }

            @Override
            public void afterToolResult(LoopContext context, ToolCallRequest call, ToolOutcome outcome) {
                seen.add(call.toolName() + ":" + outcome.result().status());
            }
        };
        ScriptedModelPort model = new ScriptedModelPort(false)
                .toolCalls(call("c1", "kb.lookup", "{\"query\":\"a\"}"))
                .toolCalls(call("c2", "kb.hidden", "{\"query\":\"b\"}"))
                .answer("ok");

        RunResult result = run(model, List.of(lookup, hidden),
                RunSpec.builder("run-1", scenario(budget(6, 4)), Tools.context(Set.of("CHAT")))
                        .message(HarnessMessage.system(SYSTEM)).message(HarnessMessage.user(USER))
                        .interceptor(interceptor).build());

        assertThat(result.content()).isEqualTo("ok");
        assertThat(model.call(1).tools()).extracting(ModelTool::name).containsExactly("kb.lookup");
        assertThat(model.call(1).messages()).extracting(HarnessMessage::content).contains("constraint for step 1");
        assertThat(hidden.calls()).as("a tool not offered never runs").isZero();
        assertThat(model.call(3).messages()).last().extracting(HarnessMessage::content).asString()
                .contains("TOOL_NOT_OFFERED");
        assertThat(seen).containsExactly("kb.lookup:SUCCESS");
        assertThat(model.call(2).messages()).extracting(HarnessMessage::content)
                .filteredOn("constraint for step 1"::equals).hasSize(1);
    }

    @Test
    void beforeFinalCanRetryOnceAndThenReplace() {
        LoopInterceptor guard = new LoopInterceptor() {
            @Override
            public FinalDecision beforeFinal(LoopContext context, String content) {
                return context.finalRetries() == 0
                        ? FinalDecision.retry("Answer again without the code.")
                        : FinalDecision.replace("A safe answer.");
            }
        };
        ScriptedModelPort model = new ScriptedModelPort(false).answer("full code").answer("still code");

        RunResult result = run(model, List.of(), specWith(guard));

        assertThat(result.content()).isEqualTo("A safe answer.");
        assertThat(result.warnings()).contains("final_answer_retried", "final_answer_replaced");
        assertThat(model.call(2).messages()).extracting(HarnessMessage::content)
                .doesNotContain("full code").contains("Answer again without the code.");
    }

    @Test
    void aSecondRetryRequestFailsTheRun() {
        LoopInterceptor guard = new LoopInterceptor() {
            @Override
            public FinalDecision beforeFinal(LoopContext context, String content) {
                return FinalDecision.retry("Again.");
            }
        };
        ScriptedModelPort model = new ScriptedModelPort(false).answer("one").answer("two");

        RunResult result = run(model, List.of(), specWith(guard));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.failure().kind()).isEqualTo(RunFailure.Kind.INTERCEPTOR_ERROR);
    }

    @Test
    void anInterceptorThatThrowsFailsTheRun() {
        LoopInterceptor broken = new LoopInterceptor() {
            @Override
            public void beforeModelCall(LoopContext context, OutgoingMessages messages) {
                throw new IllegalStateException("policy store down");
            }
        };
        ScriptedModelPort model = new ScriptedModelPort(false);

        RunResult result = run(model, List.of(), specWith(broken));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.failure().kind()).isEqualTo(RunFailure.Kind.INTERCEPTOR_ERROR);
        assertThat(result.failure().detail()).doesNotContain("policy store down");
        assertThat(model.calls()).as("no model call without the policy").isEmpty();
    }

    @Test
    void modelErrorsBecomeTypedFailures() {
        ScriptedModelPort model = new ScriptedModelPort(false).then(call -> {
            throw new ModelCallException(ModelCallException.Kind.RATE_LIMITED, "provider HTTP 429");
        });
        List<RunResult> finished = new ArrayList<>();
        RunListener listener = new RunListener() {
            @Override
            public void onRunFinished(LoopContext context, RunResult result) {
                finished.add(result);
            }
        };

        RunResult result = run(model, List.of(), spec(scenario(budget(6, 4)), false, listener));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.failure().kind()).isEqualTo(RunFailure.Kind.MODEL_ERROR);
        assertThat(result.failure().modelErrorKind()).isEqualTo(ModelCallException.Kind.RATE_LIMITED);
        assertThat(finished).containsExactly(result);
    }

    @Test
    void cancellingDuringAToolCallInterruptsItAndEndsTheRun() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Tools.Fake slow = Tools.descriptor("kb.slow").timeout(Duration.ofSeconds(10)).behaving((context, input) -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ex) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return ToolResult.failure(ToolStatus.CANCELLED, "INTERRUPTED", "interrupted");
        });
        ScriptedModelPort model = new ScriptedModelPort(false).toolCalls(call("c1", "kb.slow", "{\"query\":\"a\"}"));
        CancellationToken token = new CancellationToken();
        Thread canceller = new Thread(() -> {
            try {
                if (started.await(5, TimeUnit.SECONDS)) {
                    token.cancel();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        canceller.start();

        long begun = System.nanoTime();
        RunResult result = run(model, List.of(slow),
                RunSpec.builder("run-1", scenario(budget(6, 4)), Tools.context(Set.of("CHAT")))
                        .message(HarnessMessage.user(USER)).cancellation(token).build());
        canceller.join();

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.failure().kind()).isEqualTo(RunFailure.Kind.CANCELLED);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun)).isLessThan(5_000);
        assertThat(model.calls()).hasSize(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!interrupted.get() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(interrupted).as("the tool thread was interrupted").isTrue();
    }

    @Test
    void theRunDeadlineCutsAToolShortAndEndsTheRun() {
        Tools.Fake slow = Tools.descriptor("kb.slow").timeout(Duration.ofSeconds(10)).behaving((context, input) -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.failure(ToolStatus.CANCELLED, "INTERRUPTED", "interrupted");
        });
        RunBudget budget = RunBudget.builder(6, 4).maxRunDuration(Duration.ofMillis(300)).build();
        ScriptedModelPort model = new ScriptedModelPort(false).toolCalls(call("c1", "kb.slow", "{\"query\":\"a\"}"));

        long begun = System.nanoTime();
        RunResult result = run(model, List.of(slow), spec(scenario(budget), false));

        assertThat(result.status()).isEqualTo(RunStatus.DEADLINE_EXCEEDED);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun)).isLessThan(1_500);
        assertThat(model.call(1).timeout()).isNotNull().isLessThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void aFailClosedToolFailureEndsTheRunAndAFailOpenOneDoesNot() {
        Tools.Fake closed = Tools.descriptor("guard.check").failurePolicy(FailurePolicy.FAIL_CLOSED)
                .behaving((context, input) -> {
                    throw new IllegalStateException("store down");
                });
        Tools.Fake open = Tools.descriptor("kb.search").behaving((context, input) -> {
            throw new IllegalStateException("index down");
        });

        ScriptedModelPort closedModel = new ScriptedModelPort(false)
                .toolCalls(call("c1", "guard.check", "{\"query\":\"a\"}"));
        RunResult closedResult = run(closedModel, List.of(closed), spec(scenario(budget(6, 4)), false));
        ScriptedModelPort openModel = new ScriptedModelPort(false)
                .toolCalls(call("c1", "kb.search", "{\"query\":\"a\"}")).answer("without the index");
        RunResult openResult = run(openModel, List.of(open), spec(scenario(budget(6, 4)), false));

        assertThat(closedResult.status()).isEqualTo(RunStatus.FAILED);
        assertThat(closedResult.failure().kind()).isEqualTo(RunFailure.Kind.TOOL_FAILED_CLOSED);
        assertThat(closedModel.calls()).hasSize(1);
        assertThat(openResult.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(openModel.call(2).messages()).last().extracting(HarnessMessage::content).asString()
                .contains("EXECUTION_ERROR").doesNotContain("index down");
    }

    @Test
    void aBrokenOrSlowToolIsListedInTheNextManifestAndTheRunGoesOn() {
        Tools.Fake broken = Tools.descriptor("kb.search").behaving((context, input) -> {
            throw new IllegalStateException("index down");
        });
        Tools.Fake slow = Tools.descriptor("kb.slow").timeout(Duration.ofMillis(150)).behaving((context, input) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("late", List.of(), null, null);
        });
        List<ModelCallManifest> manifests = new CopyOnWriteArrayList<>();
        ScriptedModelPort model = new ScriptedModelPort(false)
                .toolCalls(call("c1", "kb.search", "{\"query\":\"a\"}"), call("c2", "kb.slow", "{\"query\":\"b\"}"),
                        call("c3", "kb.search", "not json"))
                .answer("answered without both tools");

        RunResult result = run(model, List.of(broken, slow), RunSpec.builder("run-1", scenario(budget(6, 4)),
                        Tools.context(Set.of("CHAT"))).message(HarnessMessage.user(USER))
                .manifestSink(manifests::add).build());

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.content()).isEqualTo("answered without both tools");
        assertThat(manifests).extracting(ModelCallManifest::callSeq).containsExactly(1, 2);
        assertThat(manifests.get(0).warnings()).isEmpty();
        // A bad argument is the model's mistake, not a degraded tool.
        assertThat(manifests.get(1).warnings()).containsExactly("tool_degraded:kb.search:execution_error",
                "tool_degraded:kb.slow:timeout");
        assertThat(result.warnings()).containsExactly("tool_degraded:kb.search:execution_error",
                "tool_degraded:kb.slow:timeout");
    }

    @Test
    void listenersSeeTheRunInOrderAndCannotBreakIt() {
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        List<String> events = new CopyOnWriteArrayList<>();
        RunListener listener = new RunListener() {
            @Override
            public void onRunStarted(LoopContext context) {
                events.add("started");
            }

            @Override
            public void onModelCall(LoopContext context, int callSeq, ModelCall call) {
                events.add("call" + callSeq);
            }

            @Override
            public void onModelTurn(LoopContext context, int callSeq, ModelTurn turn) {
                events.add("turn" + callSeq);
                throw new IllegalStateException("state row write failed");
            }

            @Override
            public void onToolOutcome(LoopContext context, ToolCallRequest call, ToolOutcome outcome) {
                events.add("tool:" + call.toolName());
            }

            @Override
            public void onRunFinished(LoopContext context, RunResult result) {
                events.add("finished:" + result.status());
            }
        };
        ScriptedModelPort model = new ScriptedModelPort(false)
                .toolCalls(call("c1", "kb.lookup", "{\"query\":\"a\"}")).answer("done");

        RunResult result = run(model, List.of(lookup), spec(scenario(budget(6, 4)), false, listener));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.warnings()).contains("listener_failed");
        assertThat(events).containsExactly("started", "call1", "turn1", "tool:kb.lookup", "call2", "turn2",
                "finished:COMPLETED");
    }

    @Test
    void streamingRunsForwardTextDeltas() {
        List<String> deltas = new CopyOnWriteArrayList<>();
        ScriptedModelPort model = new ScriptedModelPort(false).answer("streamed answer");

        RunResult result = run(model, List.of(),
                RunSpec.builder("run-1", scenario(budget(6, 4)), Tools.context(Set.of("CHAT")))
                        .message(HarnessMessage.user(USER)).streaming(true)
                        .listener(new RunListener() {
                            @Override
                            public void onTextDelta(LoopContext context, String delta) {
                                deltas.add(delta);
                            }
                        }).build());

        assertThat(result.content()).isEqualTo("streamed answer");
        assertThat(deltas).containsExactly("streamed answer");
    }

    @Test
    void theScenarioCapsTheScopesTheCallerHolds() {
        Tools.Fake chat = Tools.descriptor("kb.lookup").echo();
        Tools.Fake admin = Tools.descriptor("ops.reset").scopes("ADMIN").echo();
        ScriptedModelPort model = new ScriptedModelPort(false).answer("ok");

        run(model, List.of(chat, admin), RunSpec.builder("run-1", scenario(budget(6, 4)),
                Tools.context(Set.of("CHAT", "ADMIN"))).message(HarnessMessage.user(USER)).build());

        assertThat(model.call(1).tools()).extracting(ModelTool::name).containsExactly("kb.lookup");
    }

    @Test
    void everyModelCallLeavesAManifestWithoutMessageText() {
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        List<ModelCallManifest> manifests = new CopyOnWriteArrayList<>();
        ScriptedModelPort model = new ScriptedModelPort(false)
                .toolCalls(call("c1", "kb.lookup", "{\"query\":\"a\"}")).answer("done");

        run(model, List.of(lookup), RunSpec.builder("run-1", scenario(budget(6, 4)), Tools.context(Set.of("CHAT")))
                .message(HarnessMessage.system(SYSTEM)).message(HarnessMessage.user(USER))
                .manifestSink(manifests::add).build());

        assertThat(manifests).extracting(ModelCallManifest::callSeq).containsExactly(1, 2);
        assertThat(manifests.get(0).toolNames()).containsExactly("kb.lookup");
        assertThat(manifests.get(1).messageCount()).isEqualTo(4);
        assertThat(manifests.toString()).doesNotContain(USER);
    }

    private RunResult run(ScriptedModelPort model, List<? extends AgentTool> tools, RunSpec spec) {
        ToolRuntime runtime = ToolRuntimes.create(ToolCatalog.of(tools), List.of(), ToolAuditSink.NONE,
                BulkheadLimits.defaults());
        runtimes.add(runtime);
        return AgentLoops.create(model, runtime).run(spec);
    }

    private static AgentScenario scenario(RunBudget budget) {
        return new AgentScenario("chat", Set.of("CHAT"), List.of(), CompletionPolicy.anyAnswer(), budget,
                "normal", "recovery");
    }

    private static RunBudget budget(int steps, int toolCalls) {
        return RunBudget.builder(steps, toolCalls).build();
    }

    private static RunSpec spec(AgentScenario scenario, boolean requireToolCall) {
        return spec(scenario, requireToolCall, RunListener.NONE);
    }

    private static RunSpec spec(AgentScenario scenario, boolean requireToolCall, RunListener listener) {
        return RunSpec.builder("run-1", scenario, Tools.context(Set.of("CHAT")))
                .message(HarnessMessage.system(SYSTEM))
                .message(HarnessMessage.user(USER))
                .requireToolCall(requireToolCall)
                .listener(listener)
                .build();
    }

    private static RunSpec specWith(LoopInterceptor interceptor) {
        return RunSpec.builder("run-1", scenario(budget(6, 4)), Tools.context(Set.of("CHAT")))
                .message(HarnessMessage.user(USER)).interceptor(interceptor).build();
    }
}
