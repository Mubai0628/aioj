package com.aioj.next.agent.harness.tool.internal;

import com.aioj.next.agent.harness.tool.api.AgentTool;
import com.aioj.next.agent.harness.tool.api.BulkheadLimits;
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
import com.aioj.next.agent.harness.tool.api.ToolStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The tool pipeline: parse → schema → scopes and authorizers → execution in
 * the descriptor's bulkhead under a timeout → trimming → audit. Ported from
 * ai-service's ToolBroker; the one thread pool became one pool per execution
 * class.
 */
public final class DefaultToolRuntime implements ToolRuntime {
    private final ToolCatalog catalog;
    private final List<ToolAuthorizer> authorizers;
    private final ToolAuditSink auditSink;
    private final Bulkheads bulkheads;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelPayloadRenderer renderer = new ModelPayloadRenderer(objectMapper);
    private final ConcurrentMap<String, Set<FutureTask<ToolResult>>> inFlight = new ConcurrentHashMap<>();

    public DefaultToolRuntime(ToolCatalog catalog, List<ToolAuthorizer> authorizers, ToolAuditSink auditSink,
                              BulkheadLimits limits) {
        this.catalog = catalog;
        this.authorizers = authorizers == null ? List.of() : List.copyOf(authorizers);
        this.auditSink = auditSink == null ? ToolAuditSink.NONE : auditSink;
        this.bulkheads = new Bulkheads(limits == null ? BulkheadLimits.defaults() : limits);
    }

    @Override
    public List<ToolDescriptor> visibleTools(Set<String> grantedScopes) {
        return catalog.visible(grantedScopes);
    }

    @Override
    public ToolOutcome execute(ToolExecutionContext context, int callSeq, ToolCallRequest call, Duration remaining) {
        long started = System.nanoTime();
        Optional<AgentTool> found = catalog.find(call.toolName());
        if (found.isEmpty()) {
            return rejected(context, callSeq, call, null, null, ToolResult.failure(ToolStatus.NOT_FOUND,
                    "TOOL_NOT_FOUND", "Tool " + call.toolName() + " is not available"), started);
        }
        AgentTool tool = found.get();
        ToolDescriptor descriptor = tool.descriptor();

        JsonNode arguments;
        try {
            arguments = call.argumentsJson() == null || call.argumentsJson().isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(call.argumentsJson());
        } catch (RuntimeException ex) {
            return rejected(context, callSeq, call, descriptor, null, ToolResult.failure(ToolStatus.SCHEMA_ERROR,
                    "ARGUMENTS_NOT_JSON", "Tool arguments are not valid JSON"), started);
        }
        List<String> schemaErrors = SchemaValidator.validate(descriptor.inputSchema(), arguments);
        if (!schemaErrors.isEmpty()) {
            return rejected(context, callSeq, call, descriptor, null, ToolResult.failure(ToolStatus.SCHEMA_ERROR,
                    "ARGUMENTS_SCHEMA_VIOLATION",
                    "Tool arguments failed schema validation: " + String.join("; ", schemaErrors)), started);
        }

        String decisionId = "hd-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String denial = authorize(descriptor, context);
        if (denial != null) {
            return rejected(context, callSeq, call, descriptor, decisionId, ToolResult.failure(ToolStatus.POLICY_DENIED,
                    denial, "Tool call was denied by server policy"), started);
        }

        Object handle = startAudit(context, callSeq, descriptor, call, decisionId);
        FutureTask<ToolResult> task = new FutureTask<>(() -> tool.execute(context, arguments));
        // Registered before it can start, so a cancelRun arriving as the call begins still finds it.
        String runKey = context.runId() == null ? "" : context.runId();
        inFlight.computeIfAbsent(runKey, key -> ConcurrentHashMap.newKeySet()).add(task);
        ToolResult result;
        try {
            bulkheads.pool(descriptor.executionClass()).execute(task);
            result = task.get(effectiveTimeout(descriptor.timeout(), remaining).toNanos(), TimeUnit.NANOSECONDS);
            if (result == null) {
                result = ToolResult.failure(ToolStatus.EXECUTION_ERROR, "TOOL_EMPTY_RESULT",
                        "Tool execution returned no result");
            }
        } catch (TimeoutException ex) {
            task.cancel(true);
            result = ToolResult.failure(ToolStatus.TIMEOUT, "TOOL_EXECUTION_TIMEOUT",
                    "Tool execution exceeded its server timeout");
        } catch (CancellationException ex) {
            result = ToolResult.failure(ToolStatus.CANCELLED, "TOOL_EXECUTION_CANCELLED",
                    "Tool execution was cancelled by the server");
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            result = ToolResult.failure(ToolStatus.CANCELLED, "TOOL_EXECUTION_CANCELLED",
                    "Tool execution was cancelled by the server");
        } catch (ExecutionException ex) {
            // Tools report their own failures as results; an exception is a bug, and its text stays out of the model.
            result = ToolResult.failure(ToolStatus.EXECUTION_ERROR, "TOOL_EXECUTION_ERROR", "Tool execution failed");
        } catch (RejectedExecutionException ex) {
            result = ToolResult.failure(ToolStatus.SERVICE_UNAVAILABLE, "TOOL_CAPACITY_SATURATED",
                    "Tool execution capacity is saturated; retry later");
        } finally {
            Set<FutureTask<ToolResult>> calls = inFlight.get(runKey);
            if (calls != null) {
                calls.remove(task);
            }
        }
        ToolOutcome outcome = outcome(call, descriptor, decisionId, result, started);
        try {
            auditSink.complete(handle, outcome);
        } catch (RuntimeException ignored) {
            // An audit write failure must not fail the call.
        }
        return outcome;
    }

    @Override
    public void cancelRun(String runId) {
        Set<FutureTask<ToolResult>> calls = inFlight.remove(runId == null ? "" : runId);
        if (calls != null) {
            calls.forEach(task -> task.cancel(true));
        }
    }

    @Override
    public void close() {
        bulkheads.close();
    }

    /** Returns the denial reason, or null when every check allows the call. */
    private String authorize(ToolDescriptor descriptor, ToolExecutionContext context) {
        if (!context.grantedScopes().containsAll(descriptor.requiredScopes())) {
            return "MISSING_SCOPE";
        }
        for (ToolAuthorizer authorizer : authorizers) {
            ToolAuthorizer.Decision decision;
            try {
                decision = authorizer.authorize(descriptor, context);
            } catch (RuntimeException ex) {
                return "AUTHORIZER_UNAVAILABLE";
            }
            if (decision == null) {
                return "AUTHORIZER_NO_DECISION";
            }
            if (!decision.allowed()) {
                return decision.reasonCode() == null || decision.reasonCode().isBlank()
                        ? "DENIED" : decision.reasonCode();
            }
        }
        return null;
    }

    private Object startAudit(ToolExecutionContext context, int callSeq, ToolDescriptor descriptor,
                              ToolCallRequest call, String decisionId) {
        try {
            return auditSink.start(context, callSeq, descriptor, call, decisionId);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ToolOutcome rejected(ToolExecutionContext context, int callSeq, ToolCallRequest call,
                                 ToolDescriptor descriptor, String decisionId, ToolResult result, long started) {
        ToolOutcome outcome = outcome(call, descriptor, decisionId, result, started);
        try {
            auditSink.rejected(context, callSeq, call, outcome);
        } catch (RuntimeException ignored) {
            // An audit write failure must not fail the call.
        }
        return outcome;
    }

    private ToolOutcome outcome(ToolCallRequest call, ToolDescriptor descriptor, String decisionId,
                                ToolResult result, long started) {
        ModelPayloadRenderer.Rendered rendered = renderer.render(result, descriptor);
        boolean failedClosed = descriptor != null && descriptor.failurePolicy() == FailurePolicy.FAIL_CLOSED
                && rendered.result().status().infrastructureFailure();
        return new ToolOutcome(call.callId(), call.toolName(), rendered.result(), rendered.payload(), decisionId,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), failedClosed);
    }

    private static Duration effectiveTimeout(Duration toolTimeout, Duration remaining) {
        if (remaining == null || remaining.compareTo(toolTimeout) >= 0) {
            return toolTimeout;
        }
        return remaining.isNegative() || remaining.isZero() ? Duration.ofMillis(1) : remaining;
    }
}
