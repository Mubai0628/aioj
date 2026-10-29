package com.aioj.next.agent.harness.tool;

import com.aioj.next.agent.harness.context.api.TrustLevel;
import com.aioj.next.agent.harness.testing.Tools;
import com.aioj.next.agent.harness.tool.api.AgentTool;
import com.aioj.next.agent.harness.tool.api.BulkheadLimits;
import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.FailurePolicy;
import com.aioj.next.agent.harness.tool.api.ToolAuditSink;
import com.aioj.next.agent.harness.tool.api.ToolAuthorizer;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;
import com.aioj.next.agent.harness.tool.api.ToolCatalog;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** The tool pipeline (plan T1.5): every failure is an outcome, never an exception. */
class ToolRuntimeTest {
    private static final ToolExecutionContext CONTEXT = Tools.context(Set.of("CHAT"));

    private final List<ToolRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void closeRuntimes() {
        runtimes.forEach(ToolRuntime::close);
    }

    @Test
    void anUnknownToolIsNotFoundAndAuditedAsRejected() {
        RecordingAudit audit = new RecordingAudit();

        ToolOutcome outcome = runtime(List.of(), List.of(), audit, BulkheadLimits.defaults())
                .execute(CONTEXT, 1, new ToolCallRequest("c1", "kb.missing", "{}"), null);

        assertThat(outcome.result().status()).isEqualTo(ToolStatus.NOT_FOUND);
        assertThat(outcome.modelPayload()).contains("\"errorCode\":\"TOOL_NOT_FOUND\"", "\"instructionAllowed\":false");
        assertThat(audit.events).containsExactly("rejected:kb.missing:NOT_FOUND");
    }

    @Test
    void argumentsMustBeJsonAndMatchTheSchema() {
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        ToolRuntime runtime = runtime(List.of(lookup));

        ToolOutcome notJson = runtime.execute(CONTEXT, 1, new ToolCallRequest("c1", "kb.lookup", "{query"), null);
        ToolOutcome wrongShape = runtime.execute(CONTEXT, 2,
                new ToolCallRequest("c2", "kb.lookup", "{\"query\":\"\",\"extra\":1}"), null);

        assertThat(notJson.result().errorCode()).isEqualTo("ARGUMENTS_NOT_JSON");
        assertThat(wrongShape.result().status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
        assertThat(wrongShape.result().errorMessage()).contains("$.query: shorter than minLength 1",
                "$.extra: is not allowed");
        assertThat(lookup.calls()).isZero();
    }

    @Test
    void scopesAndAuthorizersDecideBeforeTheToolRuns() {
        Tools.Fake admin = Tools.descriptor("ops.reset").scopes("ADMIN").echo();
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        ToolAuthorizer ownerOnly = (descriptor, context) -> "kb.lookup".equals(descriptor.name())
                && !"owner".equals(context.serverBinding("role"))
                ? ToolAuthorizer.Decision.deny("NOT_OWNER") : ToolAuthorizer.Decision.allow();
        ToolAuthorizer broken = (descriptor, context) -> {
            if (context.serverBinding("break") != null) {
                throw new IllegalStateException("acl service down");
            }
            return ToolAuthorizer.Decision.allow();
        };
        ToolRuntime runtime = runtime(List.of(admin, lookup), List.of(ownerOnly, broken), ToolAuditSink.NONE,
                BulkheadLimits.defaults());

        String missingScope = runtime.execute(CONTEXT, 1, new ToolCallRequest("c1", "ops.reset", "{\"query\":\"a\"}"), null)
                .result().errorCode();
        String denied = runtime.execute(CONTEXT, 2, new ToolCallRequest("c2", "kb.lookup", "{\"query\":\"a\"}"), null)
                .result().errorCode();
        String unavailable = runtime.execute(withBindings(Map.of("role", "owner", "break", "1")), 3,
                new ToolCallRequest("c3", "kb.lookup", "{\"query\":\"a\"}"), null).result().errorCode();
        ToolOutcome allowed = runtime.execute(withBindings(Map.of("role", "owner")), 4,
                new ToolCallRequest("c4", "kb.lookup", "{\"query\":\"a\"}"), null);

        assertThat(missingScope).isEqualTo("MISSING_SCOPE");
        assertThat(denied).isEqualTo("NOT_OWNER");
        assertThat(unavailable).as("an authorizer that throws denies").isEqualTo("AUTHORIZER_UNAVAILABLE");
        assertThat(allowed.result().ok()).isTrue();
        assertThat(allowed.decisionId()).startsWith("hd-");
        assertThat(admin.calls()).isZero();
        assertThat(lookup.calls()).isEqualTo(1);
    }

    @Test
    void aSlowToolTimesOutAndIsInterrupted() throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        Tools.Fake slow = Tools.descriptor("kb.slow").timeout(Duration.ofMillis(200)).behaving((context, input) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                interrupted.set(true);
            } finally {
                done.countDown();
            }
            return ToolResult.success("late", List.of(), null, null);
        });

        long started = System.nanoTime();
        ToolOutcome outcome = runtime(List.of(slow)).execute(CONTEXT, 1,
                new ToolCallRequest("c1", "kb.slow", "{\"query\":\"a\"}"), null);

        assertThat(outcome.result().status()).isEqualTo(ToolStatus.TIMEOUT);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000);
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    void theRunsRemainingTimeShortensTheToolTimeout() {
        Tools.Fake slow = Tools.descriptor("kb.slow").timeout(Duration.ofSeconds(10)).behaving((context, input) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("late", List.of(), null, null);
        });

        long started = System.nanoTime();
        ToolOutcome outcome = runtime(List.of(slow)).execute(CONTEXT, 1,
                new ToolCallRequest("c1", "kb.slow", "{\"query\":\"a\"}"), Duration.ofMillis(150));

        assertThat(outcome.result().status()).isEqualTo(ToolStatus.TIMEOUT);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000);
    }

    @Test
    void aThrowingToolIsLoggedWhereItWasThrownButWithoutItsMessage() {
        Tools.Fake broken = Tools.descriptor("kb.broken").behaving((context, input) -> {
            throw new IllegalStateException("wrapped", new NumberFormatException("student code: int x = 1/0"));
        });
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(
                "com.aioj.next.agent.harness.tool.internal.DefaultToolRuntime");
        List<java.util.logging.LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(capture);
        try {
            runtime(List.of(broken)).execute(CONTEXT, 7, new ToolCallRequest("c1", "kb.broken", "{\"query\":\"a\"}"), null);
        } finally {
            log.removeHandler(capture);
        }

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getLevel()).isEqualTo(java.util.logging.Level.WARNING);
        assertThat(records.get(0).getMessage())
                .contains("Tool kb.broken threw in run", "call 7", "java.lang.IllegalStateException",
                        "Caused by: java.lang.NumberFormatException", "\tat ")
                .doesNotContain("wrapped", "student code");
    }

    @Test
    void aThrowingToolBecomesAnExecutionErrorWithoutItsMessage() {
        Tools.Fake broken = Tools.descriptor("kb.broken").behaving((context, input) -> {
            throw new IllegalStateException("jdbc:mysql://db/secret-host");
        });
        Tools.Fake guarded = Tools.descriptor("guard.check").failurePolicy(FailurePolicy.FAIL_CLOSED)
                .behaving((context, input) -> {
                    throw new IllegalStateException("down");
                });
        ToolRuntime runtime = runtime(List.of(broken, guarded));

        ToolOutcome open = runtime.execute(CONTEXT, 1, new ToolCallRequest("c1", "kb.broken", "{\"query\":\"a\"}"), null);
        ToolOutcome closed = runtime.execute(CONTEXT, 2, new ToolCallRequest("c2", "guard.check", "{\"query\":\"a\"}"), null);

        assertThat(open.result().status()).isEqualTo(ToolStatus.EXECUTION_ERROR);
        assertThat(open.modelPayload()).doesNotContain("secret-host");
        assertThat(open.failedClosed()).isFalse();
        assertThat(closed.failedClosed()).isTrue();
    }

    @Test
    void aSaturatedBulkheadRejectsOnlyItsOwnClass() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Tools.Fake sandbox = Tools.descriptor("code.run").executionClass(ExecutionClass.SANDBOX)
                .timeout(Duration.ofSeconds(10)).behaving((context, input) -> {
                    running.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolResult.success("ran", List.of(), null, null);
                });
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        BulkheadLimits limits = new BulkheadLimits(Map.of(ExecutionClass.SANDBOX, 1), Map.of(ExecutionClass.SANDBOX, 0));
        ToolRuntime runtime = runtime(List.of(sandbox, lookup), List.of(), ToolAuditSink.NONE, limits);

        CompletableFuture<ToolOutcome> first = CompletableFuture.supplyAsync(() -> runtime.execute(CONTEXT, 1,
                new ToolCallRequest("c1", "code.run", "{\"query\":\"a\"}"), null));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        ToolOutcome second = runtime.execute(CONTEXT, 2, new ToolCallRequest("c2", "code.run", "{\"query\":\"b\"}"), null);
        ToolOutcome readOnly = runtime.execute(CONTEXT, 3, new ToolCallRequest("c3", "kb.lookup", "{\"query\":\"c\"}"), null);
        release.countDown();

        assertThat(second.result().status()).isEqualTo(ToolStatus.SERVICE_UNAVAILABLE);
        assertThat(second.result().errorCode()).isEqualTo("TOOL_CAPACITY_SATURATED");
        assertThat(readOnly.result().ok()).as("the read-only bulkhead is unaffected").isTrue();
        assertThat(first.get(5, TimeUnit.SECONDS).result().ok()).isTrue();
    }

    @Test
    void largeResultsAreCutToTheTokenBudget() {
        Tools.Fake big = Tools.descriptor("kb.big").maxResultTokens(10).behaving((context, input) ->
                ToolResult.success("x".repeat(200), List.of(), "PUBLIC", TrustLevel.SERVER_AUTHORITATIVE));

        ToolOutcome outcome = runtime(List.of(big)).execute(CONTEXT, 1,
                new ToolCallRequest("c1", "kb.big", "{\"query\":\"a\"}"), null);

        assertThat(outcome.result().truncated()).isTrue();
        assertThat(outcome.result().warnings()).anySatisfy(warning -> assertThat(warning).contains("truncated"));
        assertThat(outcome.modelPayload()).contains("…[truncated]", "\"truncated\":true",
                "\"classification\":\"PUBLIC\"", "\"trustLevel\":\"SERVER_AUTHORITATIVE\"");
    }

    @Test
    void errorMessagesAreRedactedBeforeTheModelSeesThem() {
        Tools.Fake leaky = Tools.descriptor("kb.leaky").behaving((context, input) ->
                ToolResult.failure(ToolStatus.SERVICE_UNAVAILABLE, "UPSTREAM", "upstream said api_key=abc123 sk-abcdefghijkl"));

        ToolOutcome outcome = runtime(List.of(leaky)).execute(CONTEXT, 1,
                new ToolCallRequest("c1", "kb.leaky", "{\"query\":\"a\"}"), null);

        assertThat(outcome.modelPayload()).contains("api_key=***", "sk-***").doesNotContain("abc123", "abcdefghijkl");
    }

    @Test
    void cancelRunInterruptsOnlyThatRunsCalls() throws Exception {
        CountDownLatch bothRunning = new CountDownLatch(2);
        Tools.Fake slow = Tools.descriptor("kb.slow").timeout(Duration.ofSeconds(10)).behaving((context, input) -> {
            bothRunning.countDown();
            try {
                Thread.sleep(context.runId().equals("run-a") ? 5_000 : 300);
                return ToolResult.success("finished", List.of(), null, null);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return ToolResult.failure(ToolStatus.CANCELLED, "INTERRUPTED", "interrupted");
            }
        });
        ToolRuntime runtime = runtime(List.of(slow));

        CompletableFuture<ToolOutcome> runA = CompletableFuture.supplyAsync(() -> runtime.execute(
                Tools.context("run-a", Set.of("CHAT")), 1, new ToolCallRequest("c1", "kb.slow", "{\"query\":\"a\"}"), null));
        CompletableFuture<ToolOutcome> runB = CompletableFuture.supplyAsync(() -> runtime.execute(
                Tools.context("run-b", Set.of("CHAT")), 1, new ToolCallRequest("c1", "kb.slow", "{\"query\":\"b\"}"), null));
        assertThat(bothRunning.await(5, TimeUnit.SECONDS)).isTrue();
        runtime.cancelRun("run-a");

        assertThat(runA.get(5, TimeUnit.SECONDS).result().status()).isEqualTo(ToolStatus.CANCELLED);
        assertThat(runB.get(5, TimeUnit.SECONDS).result().ok()).isTrue();
    }

    @Test
    void theAuditSinkSeesStartAndCompletionAndItsFailuresDoNotFailTheCall() {
        Tools.Fake lookup = Tools.descriptor("kb.lookup").echo();
        RecordingAudit audit = new RecordingAudit();
        ToolAuditSink failing = new ToolAuditSink() {
            @Override
            public Object start(ToolExecutionContext context, int callSeq, ToolDescriptor descriptor,
                                ToolCallRequest call, String decisionId) {
                throw new IllegalStateException("audit table locked");
            }

            @Override
            public void complete(Object handle, ToolOutcome outcome) {
                throw new IllegalStateException("audit table locked");
            }

            @Override
            public void rejected(ToolExecutionContext context, int callSeq, ToolCallRequest call, ToolOutcome outcome) {
            }
        };

        runtime(List.of(lookup), List.of(), audit, BulkheadLimits.defaults())
                .execute(CONTEXT, 7, new ToolCallRequest("c1", "kb.lookup", "{\"query\":\"a\"}"), null);
        ToolOutcome stillRuns = runtime(List.of(lookup), List.of(), failing, BulkheadLimits.defaults())
                .execute(CONTEXT, 8, new ToolCallRequest("c2", "kb.lookup", "{\"query\":\"a\"}"), null);

        assertThat(audit.events).containsExactly("start:7:kb.lookup", "complete:handle-7:SUCCESS");
        assertThat(stillRuns.result().ok()).isTrue();
    }

    private ToolRuntime runtime(List<? extends AgentTool> tools) {
        return runtime(tools, List.of(), ToolAuditSink.NONE, BulkheadLimits.defaults());
    }

    private ToolRuntime runtime(List<? extends AgentTool> tools, List<ToolAuthorizer> authorizers, ToolAuditSink audit,
                                BulkheadLimits limits) {
        ToolRuntime runtime = ToolRuntimes.create(ToolCatalog.of(tools), authorizers, audit, limits);
        runtimes.add(runtime);
        return runtime;
    }

    private static ToolExecutionContext withBindings(Map<String, String> bindings) {
        return new ToolExecutionContext("run-1", "u-7", "c-1", "t-1", "ps-1", Set.of("CHAT"), CONTEXT.serverTime(),
                "trace-1", bindings, Map.of());
    }

    private static final class RecordingAudit implements ToolAuditSink {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public Object start(ToolExecutionContext context, int callSeq, ToolDescriptor descriptor, ToolCallRequest call,
                            String decisionId) {
            events.add("start:" + callSeq + ":" + call.toolName());
            return "handle-" + callSeq;
        }

        @Override
        public void complete(Object handle, ToolOutcome outcome) {
            events.add("complete:" + handle + ":" + outcome.result().status());
        }

        @Override
        public void rejected(ToolExecutionContext context, int callSeq, ToolCallRequest call, ToolOutcome outcome) {
            events.add("rejected:" + call.toolName() + ":" + outcome.result().status());
        }
    }
}
