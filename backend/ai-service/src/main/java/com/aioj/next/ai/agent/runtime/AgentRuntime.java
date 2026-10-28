package com.aioj.next.ai.agent.runtime;

import com.aioj.next.agent.harness.runtime.api.LoopInterceptor;
import com.aioj.next.agent.harness.runtime.api.CancellationToken;
import com.aioj.next.ai.agent.assembly.HarnessAgentRunner;
import com.aioj.next.ai.agent.context.ContextSection;
import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.model.api.CallProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            List<LoopInterceptor> interceptors,
            /** T1.6: how the sections were assembled (degraded contributors, budget trims); every manifest lists them. */
            List<String> contextWarnings,
            boolean streamingRequested,
            TurnEventBuffer progressEvents,
            CancellationToken cancellation
    ) {
        public AgentRunRequest {
            modelScope = modelScope == null ? AiModelScope.TEXT_GENERATION : modelScope;
            serverBindings = serverBindings == null ? Map.of() : Map.copyOf(serverBindings);
            completionPolicy = completionPolicy == null ? AgentCompletionPolicy.none() : completionPolicy;
            interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
            contextWarnings = contextWarnings == null ? List.of() : List.copyOf(contextWarnings);
            cancellation = cancellation == null ? CancellationToken.none() : cancellation;
        }

        /** One request built in one place (plan A2.2), instead of the positional constructors it replaced. */
        public static Builder builder(String turnId, long userId, String conversationId) {
            return new Builder(turnId, userId, conversationId);
        }

        public static final class Builder {
            private final String turnId;
            private final long userId;
            private final String conversationId;
            private String policySnapshotId;
            private Set<String> grantedScopes = Set.of();
            private List<ContextSection> sections = List.of();
            private boolean requireToolCall;
            private String outputMode;
            private CallProfile profile;
            private LoopBudget budget;
            private ContestPolicyView contestPolicy;
            private GuardVerdict messageLayerVerdict;
            private Consumer<ProviderUsage> usageObserver;
            private AiModelScope modelScope;
            private Map<String, String> serverBindings = Map.of();
            private boolean allowStandardToolFallback;
            private AgentCompletionPolicy completionPolicy;
            private final List<LoopInterceptor> interceptors = new ArrayList<>();
            private List<String> contextWarnings = List.of();
            private boolean streamingRequested;
            private TurnEventBuffer progressEvents;
            private CancellationToken cancellation;

            private Builder(String turnId, long userId, String conversationId) {
                this.turnId = turnId;
                this.userId = userId;
                this.conversationId = conversationId;
            }

            public Builder policySnapshotId(String value) {
                this.policySnapshotId = value;
                return this;
            }

            public Builder grantedScopes(Set<String> value) {
                this.grantedScopes = value;
                return this;
            }

            public Builder sections(List<ContextSection> value) {
                this.sections = value;
                return this;
            }

            public Builder requireToolCall(boolean value) {
                this.requireToolCall = value;
                return this;
            }

            /** How the answer leaves the run (for example STREAM or BUFFERED) and the profile its calls use. */
            public Builder output(String mode, CallProfile callProfile) {
                this.outputMode = mode;
                this.profile = callProfile;
                return this;
            }

            public Builder budget(LoopBudget value) {
                this.budget = value;
                return this;
            }

            /** The contest policy projection and, for P3-4, the message layer's L3 verdict. */
            public Builder contest(ContestPolicyView policy, GuardVerdict messageVerdict) {
                this.contestPolicy = policy;
                this.messageLayerVerdict = messageVerdict;
                return this;
            }

            public Builder usageObserver(Consumer<ProviderUsage> value) {
                this.usageObserver = value;
                return this;
            }

            public Builder modelScope(AiModelScope value) {
                this.modelScope = value;
                return this;
            }

            public Builder serverBindings(Map<String, String> value) {
                this.serverBindings = value;
                return this;
            }

            public Builder allowStandardToolFallback(boolean value) {
                this.allowStandardToolFallback = value;
                return this;
            }

            public Builder completionPolicy(AgentCompletionPolicy value) {
                this.completionPolicy = value;
                return this;
            }

            public Builder interceptor(LoopInterceptor value) {
                interceptors.add(Objects.requireNonNull(value, "interceptor"));
                return this;
            }

            public Builder contextWarnings(List<String> value) {
                this.contextWarnings = value;
                return this;
            }

            /** The turn coordinator owns cancellation and decides whether output may stream. */
            public Builder turnProgress(boolean streaming, TurnEventBuffer events, CancellationToken token) {
                this.streamingRequested = streaming;
                this.progressEvents = events;
                this.cancellation = token;
                return this;
            }

            public AgentRunRequest build() {
                return new AgentRunRequest(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections,
                        requireToolCall, outputMode, Objects.requireNonNull(profile, "profile"),
                        Objects.requireNonNull(budget, "budget"), contestPolicy, messageLayerVerdict, usageObserver,
                        modelScope, serverBindings, allowStandardToolFallback, completionPolicy, interceptors,
                        contextWarnings, streamingRequested, progressEvents, cancellation);
            }
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
