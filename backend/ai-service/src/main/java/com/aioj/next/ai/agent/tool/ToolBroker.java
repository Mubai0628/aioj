package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.common.error.DomainException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single entry point for every model-initiated tool call (design doc §4.1):
 * schema validation → authorization → execution → sanitization → audit.
 * The model only ever receives the sanitized JSON payload.
 */
@Service
public class ToolBroker {

    private static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(30);

    private final ToolRegistry registry;
    private final ToolAuthorizationService authorizationService;
    private final ToolResultSanitizer sanitizer;
    private final ToolAuditService auditService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final Map<InFlightKey, Future<?>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public ToolBroker(ToolRegistry registry, ToolAuthorizationService authorizationService,
                      ToolResultSanitizer sanitizer, ToolAuditService auditService, ObjectMapper objectMapper,
                      AiProperties aiProperties) {
        this(registry, authorizationService, sanitizer, auditService, objectMapper,
                createExecutor(aiProperties.getCapacity()));
    }

    /** Tests pass their own executor to pin down how a call and a cancel interleave. */
    ToolBroker(ToolRegistry registry, ToolAuthorizationService authorizationService,
               ToolResultSanitizer sanitizer, ToolAuditService auditService, ObjectMapper objectMapper,
               ExecutorService executor) {
        this.registry = registry;
        this.authorizationService = authorizationService;
        this.sanitizer = sanitizer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * @return ToolResult whose data is the model-facing JSON payload string
     *         (to be appended as the tool message content). Errors are data:
     *         the model sees them and may correct its call, bounded by the loop budget.
     */
    public ToolResult<String> execute(ToolExecutionContext context, long agentRunId, int callSeq,
                                      String callId, String toolName, String argumentsJson) {
        return execute(context, agentRunId, callSeq, callId, toolName, argumentsJson, null);
    }

    /**
     * Executes with the descriptor timeout capped by the remaining run time.
     * A timed-out or externally cancelled task is interrupted and returned as
     * structured tool data; it is never allowed to escape the loop as raw output.
     */
    public ToolResult<String> execute(ToolExecutionContext context, long agentRunId, int callSeq,
                                      String callId, String toolName, String argumentsJson,
                                      Duration remainingRunTime) {
        long started = System.nanoTime();
        AgentTool tool = registry.find(toolName);
        if (tool == null) {
            return finish(context, agentRunId, callSeq, null, toolName, callId, argumentsJson, null,
                    ToolResult.failure(callId, ToolStatus.NOT_FOUND, "TOOL_NOT_FOUND",
                            "Tool " + toolName + " is not available"), started);
        }
        ToolDescriptor descriptor = tool.descriptor();

        JsonNode arguments;
        try {
            arguments = argumentsJson == null || argumentsJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(argumentsJson);
        } catch (Exception ex) {
            return finish(context, agentRunId, callSeq, descriptor, toolName, callId, argumentsJson, null,
                    ToolResult.failure(callId, ToolStatus.SCHEMA_ERROR, "ARGUMENTS_NOT_JSON",
                            "Tool arguments are not valid JSON"), started);
        }

        List<String> schemaErrors = ToolInputSchemaValidator.validate(descriptor.inputSchema(), arguments);
        if (!schemaErrors.isEmpty()) {
            return finish(context, agentRunId, callSeq, descriptor, toolName, callId, argumentsJson, null,
                    ToolResult.failure(callId, ToolStatus.SCHEMA_ERROR, "ARGUMENTS_SCHEMA_VIOLATION",
                            "Tool arguments failed schema validation: " + String.join("; ", schemaErrors)), started);
        }

        ToolAuthorizationService.AuthorizationDecision decision = authorizationService.authorize(descriptor, context);
        if (!decision.allowed()) {
            return finish(context, agentRunId, callSeq, descriptor, toolName, callId, argumentsJson, decision.decisionId(),
                    ToolResult.failure(callId, ToolStatus.POLICY_DENIED, decision.reasonCode(),
                            "Tool call was denied by server policy"), started);
        }

        ToolAuditService.AuditHandle auditHandle = auditService.start(context, agentRunId, callSeq,
                descriptor, toolName, callId, argumentsJson, decision.decisionId());
        ToolResult<Object> result;
        InFlightKey inFlightKey = new InFlightKey(agentRunId, callId == null ? "" : callId);
        // Registered before the executor can start it, so a cancelRun that arrives
        // just as the call begins still finds and interrupts it.
        FutureTask<ToolResult<Object>> future = new FutureTask<>(() -> tool.execute(context, arguments));
        inFlight.put(inFlightKey, future);
        try {
            executor.execute(future);
            Duration timeout = effectiveTimeout(descriptor.timeout(), remainingRunTime);
            result = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (result == null) {
                result = ToolResult.failure(callId, ToolStatus.EXECUTION_ERROR, "TOOL_EMPTY_RESULT",
                        "Tool execution returned no result");
            }
        } catch (TimeoutException ex) {
            future.cancel(true);
            result = ToolResult.failure(callId, ToolStatus.TIMEOUT, "TOOL_EXECUTION_TIMEOUT",
                    "Tool execution exceeded its server timeout");
        } catch (CancellationException ex) {
            result = ToolResult.failure(callId, ToolStatus.CANCELLED, "TOOL_EXECUTION_CANCELLED",
                    "Tool execution was cancelled by the server");
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            result = ToolResult.failure(callId, ToolStatus.CANCELLED, "TOOL_EXECUTION_CANCELLED",
                    "Tool execution was cancelled by the server");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof DomainException domain) {
                result = ToolResult.failure(callId, ToolStatus.EXECUTION_ERROR,
                        "DOMAIN_" + domain.errorCode().name(), domain.getMessage());
            } else {
                result = ToolResult.failure(callId, ToolStatus.EXECUTION_ERROR, "TOOL_EXECUTION_ERROR",
                        "Tool execution failed");
            }
        } catch (RejectedExecutionException ex) {
            // Saturated bounded executor: surface a tool-level error on the normal
            // audit path instead of failing the whole Agent turn with a raw exception.
            result = ToolResult.failure(callId, ToolStatus.SERVICE_UNAVAILABLE, "TOOL_CAPACITY_SATURATED",
                    "Tool execution capacity is saturated; retry later");
        } catch (RuntimeException ex) {
            result = ToolResult.failure(callId, ToolStatus.EXECUTION_ERROR, "TOOL_EXECUTION_ERROR",
                    "Tool execution failed");
        } finally {
            // Only this call's entry: another call with the same key may have registered since.
            inFlight.remove(inFlightKey, future);
        }
        result = result.withPolicyDecisionId(decision.decisionId());
        return finish(context, agentRunId, callSeq, descriptor, toolName, callId, argumentsJson, decision.decisionId(),
                result, started, auditHandle);
    }

    private ToolResult<String> finish(ToolExecutionContext context, long agentRunId, int callSeq,
                                      ToolDescriptor descriptor, String toolName, String callId,
                                      String argumentsJson, String policyDecisionId,
                                      ToolResult<Object> result, long startedNanos) {
        return finish(context, agentRunId, callSeq, descriptor, toolName, callId, argumentsJson,
                policyDecisionId, result, startedNanos, null);
    }

    private ToolResult<String> finish(ToolExecutionContext context, long agentRunId, int callSeq,
                                      ToolDescriptor descriptor, String toolName, String callId,
                                      String argumentsJson, String policyDecisionId,
                                      ToolResult<Object> result, long startedNanos,
                                      ToolAuditService.AuditHandle auditHandle) {
        String payload = sanitizer.toModelPayload(result, descriptor);
        long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
        if (auditHandle == null) {
            auditService.record(context, agentRunId, callSeq, descriptor, toolName, callId, argumentsJson,
                    policyDecisionId, result.status(), result.classification(), payload, latencyMs, result.errorCode());
        } else {
            auditService.complete(auditHandle, result.status(), result.classification(), payload,
                    latencyMs, result.errorCode());
        }
        return new ToolResult<>(callId, result.status(), payload, List.of(), result.classification(), result.trustLevel(),
                policyDecisionId, result.truncated(), result.nextCursor(), result.resultHash(), result.warnings(),
                result.errorCode(), result.errorMessage());
    }

    /** Cancels all in-flight tool work for one Agent run. */
    public int cancelRun(long agentRunId) {
        int cancelled = 0;
        for (Map.Entry<InFlightKey, Future<?>> entry : inFlight.entrySet()) {
            if (entry.getKey().agentRunId() == agentRunId && entry.getValue().cancel(true)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private Duration effectiveTimeout(Duration descriptorTimeout, Duration remainingRunTime) {
        Duration timeout = descriptorTimeout == null || descriptorTimeout.isZero() || descriptorTimeout.isNegative()
                ? DEFAULT_TOOL_TIMEOUT
                : descriptorTimeout;
        if (remainingRunTime != null && !remainingRunTime.isNegative() && !remainingRunTime.isZero()
                && remainingRunTime.compareTo(timeout) < 0) {
            timeout = remainingRunTime;
        }
        return timeout;
    }

    private static ExecutorService createExecutor(AiProperties.Capacity capacity) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "ai-tool-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(capacity.getToolCorePoolSize(), capacity.getToolMaxPoolSize(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity.getToolQueueCapacity()), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private record InFlightKey(long agentRunId, String callId) {
    }
}
