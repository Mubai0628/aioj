package com.aioj.next.ai.agent.runtime;

import com.aioj.next.agent.harness.runtime.api.LoopInterceptor;
import com.aioj.next.ai.agent.assembly.HarnessAgentRunner;
import com.aioj.next.ai.agent.context.ContextSection;
import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.model.api.CallProfile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The agent run entry point of ai-service (design doc §4.2): one request in, one
 * result out. Since T1.5 every run executes on the Harness loop through
 * {@link HarnessAgentRunner}; the request and result types here are what the turn
 * coordinators build and read.
 *
 * <p>tool_choice=REQUIRED is native only where the provider supports it (Kimi);
 * for other providers the loop simulates it once (system nudge + one retry)
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

    private final HarnessAgentRunner harnessRunner;

    public AgentRuntime(HarnessAgentRunner harnessRunner) {
        this.harnessRunner = harnessRunner;
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
            Consumer<ProviderUsage> usageObserver,
            /** DB-backed model scope; existing tutor runs default to TEXT_GENERATION. */
            AiModelScope modelScope,
            /** Trusted resource bindings injected into tool context, never model arguments. */
            Map<String, String> serverBindings,
            /** Permit one explicit thinking-capability fallback to standard tools/tool_calls. */
            boolean allowStandardToolFallback,
            /** Optional server-owned terminal gate; model prose cannot satisfy it. */
            AgentCompletionPolicy completionPolicy,
            /** Per-run Harness interceptors of the caller, for example the L4 final-answer check. */
            List<LoopInterceptor> interceptors
    ) {
        public AgentRunRequest {
            modelScope = modelScope == null ? AiModelScope.TEXT_GENERATION : modelScope;
            serverBindings = serverBindings == null ? Map.of() : Map.copyOf(serverBindings);
            completionPolicy = completionPolicy == null ? AgentCompletionPolicy.none() : completionPolicy;
            interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        }

        public AgentRunRequest(String turnId, long userId, String conversationId, String policySnapshotId,
                               Set<String> grantedScopes, List<ContextSection> sections, boolean requireToolCall,
                               String outputMode, CallProfile profile, LoopBudget budget,
                               ContestPolicyView contestPolicy, GuardVerdict messageLayerVerdict,
                               Consumer<ProviderUsage> usageObserver, AiModelScope modelScope,
                               Map<String, String> serverBindings, boolean allowStandardToolFallback,
                               AgentCompletionPolicy completionPolicy) {
            this(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections, requireToolCall,
                    outputMode, profile, budget, contestPolicy, messageLayerVerdict, usageObserver, modelScope,
                    serverBindings, allowStandardToolFallback, completionPolicy, List.of());
        }

        public AgentRunRequest withInterceptors(List<LoopInterceptor> runInterceptors) {
            return new AgentRunRequest(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections,
                    requireToolCall, outputMode, profile, budget, contestPolicy, messageLayerVerdict, usageObserver,
                    modelScope, serverBindings, allowStandardToolFallback, completionPolicy, runInterceptors);
        }

        /** Signature before model-scope/resource-binding/fallback support. */
        public AgentRunRequest(String turnId, long userId, String conversationId, String policySnapshotId,
                               Set<String> grantedScopes, List<ContextSection> sections, boolean requireToolCall,
                               String outputMode, CallProfile profile, LoopBudget budget,
                               ContestPolicyView contestPolicy, GuardVerdict messageLayerVerdict,
                               Consumer<ProviderUsage> usageObserver) {
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
        return harnessRunner.run(spec);
    }
}
