package com.aioj.next.agent.harness.runtime.api;

import com.aioj.next.agent.harness.context.api.ManifestSink;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything one run needs, built in one place instead of today's seven
 * request constructors. The scenario's interceptors are shared by all its
 * runs and must keep no per-run state; per-run state goes into interceptors
 * added here.
 *
 * <p>Tools visible to the run are those whose required scopes are covered by
 * both the caller's granted scopes and the scenario's tool scopes, so a
 * scenario can never reach a tool beyond what it declares.</p>
 */
public final class RunSpec {
    private final String runId;
    private final AgentScenario scenario;
    private final List<HarnessMessage> messages;
    private final ToolExecutionContext toolContext;
    private final boolean requireToolCall;
    private final boolean streaming;
    private final List<LoopInterceptor> interceptors;
    private final RunListener listener;
    private final ManifestSink manifestSink;
    private final CancellationToken cancellation;
    private final Map<String, Object> attributes;

    private RunSpec(Builder builder) {
        this.runId = Objects.requireNonNull(builder.runId, "runId");
        this.scenario = Objects.requireNonNull(builder.scenario, "scenario");
        this.toolContext = Objects.requireNonNull(builder.toolContext, "toolContext");
        if (builder.messages.isEmpty()) {
            throw new IllegalArgumentException("a run needs at least one starting message");
        }
        this.messages = List.copyOf(builder.messages);
        this.requireToolCall = builder.requireToolCall;
        this.streaming = builder.streaming;
        List<LoopInterceptor> all = new ArrayList<>(scenario.interceptors());
        all.addAll(builder.interceptors);
        this.interceptors = List.copyOf(all);
        this.listener = builder.listener;
        this.manifestSink = builder.manifestSink;
        this.cancellation = builder.cancellation;
        this.attributes = Map.copyOf(builder.attributes);
    }

    public static Builder builder(String runId, AgentScenario scenario, ToolExecutionContext toolContext) {
        return new Builder(runId, scenario, toolContext);
    }

    public String runId() {
        return runId;
    }

    public AgentScenario scenario() {
        return scenario;
    }

    public List<HarnessMessage> messages() {
        return messages;
    }

    public ToolExecutionContext toolContext() {
        return toolContext;
    }

    public boolean requireToolCall() {
        return requireToolCall;
    }

    public boolean streaming() {
        return streaming;
    }

    /** Scenario interceptors first, then this run's. */
    public List<LoopInterceptor> interceptors() {
        return interceptors;
    }

    public RunListener listener() {
        return listener;
    }

    public ManifestSink manifestSink() {
        return manifestSink;
    }

    public CancellationToken cancellation() {
        return cancellation;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public static final class Builder {
        private final String runId;
        private final AgentScenario scenario;
        private final ToolExecutionContext toolContext;
        private final List<HarnessMessage> messages = new ArrayList<>();
        private final List<LoopInterceptor> interceptors = new ArrayList<>();
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private boolean requireToolCall;
        private boolean streaming;
        private RunListener listener = RunListener.NONE;
        private ManifestSink manifestSink = ManifestSink.NONE;
        private CancellationToken cancellation = CancellationToken.none();

        private Builder(String runId, AgentScenario scenario, ToolExecutionContext toolContext) {
            this.runId = runId;
            this.scenario = scenario;
            this.toolContext = toolContext;
        }

        public Builder message(HarnessMessage message) {
            messages.add(Objects.requireNonNull(message, "message"));
            return this;
        }

        public Builder messages(List<HarnessMessage> starting) {
            starting.forEach(this::message);
            return this;
        }

        /** At least one tool call before the answer: native where the port supports it, otherwise asked for once. */
        public Builder requireToolCall(boolean required) {
            this.requireToolCall = required;
            return this;
        }

        /**
         * Streams answer text to the listener. Deltas reach listeners before
         * {@link LoopInterceptor#beforeFinal} has checked the answer, so runs
         * whose answer must be checked first should not stream.
         */
        public Builder streaming(boolean streamed) {
            this.streaming = streamed;
            return this;
        }

        public Builder interceptor(LoopInterceptor interceptor) {
            interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        public Builder listener(RunListener runListener) {
            this.listener = runListener == null ? RunListener.NONE : runListener;
            return this;
        }

        public Builder manifestSink(ManifestSink sink) {
            this.manifestSink = sink == null ? ManifestSink.NONE : sink;
            return this;
        }

        public Builder cancellation(CancellationToken token) {
            this.cancellation = token == null ? CancellationToken.none() : token;
            return this;
        }

        public Builder attribute(String key, Object value) {
            attributes.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public RunSpec build() {
            return new RunSpec(this);
        }
    }
}
