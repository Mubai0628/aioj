package com.aioj.next.agent.harness.runtime.internal;

import com.aioj.next.agent.harness.runtime.api.LoopContext;
import com.aioj.next.agent.harness.runtime.api.RunSpec;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The loop's mutable state, exposed read-only to interceptors and policies. */
final class LoopState implements LoopContext {
    private final RunSpec spec;
    private final Set<String> succeededTools = new LinkedHashSet<>();
    private final Set<String> warnings = new LinkedHashSet<>();
    private int step;
    private int toolCallCount;
    private int finalRetries;

    LoopState(RunSpec spec) {
        this.spec = spec;
    }

    @Override
    public String runId() {
        return spec.runId();
    }

    @Override
    public String scenario() {
        return spec.scenario().name();
    }

    @Override
    public int step() {
        return step;
    }

    @Override
    public int toolCallCount() {
        return toolCallCount;
    }

    @Override
    public Set<String> succeededTools() {
        return Collections.unmodifiableSet(succeededTools);
    }

    @Override
    public int finalRetries() {
        return finalRetries;
    }

    @Override
    public Map<String, Object> attributes() {
        return spec.attributes();
    }

    @Override
    public void warn(String warning) {
        if (warning != null && !warning.isBlank()) {
            warnings.add(warning);
        }
    }

    void step(int current) {
        this.step = current;
    }

    void countToolCall() {
        toolCallCount++;
    }

    void succeeded(String toolName) {
        succeededTools.add(toolName);
    }

    void countFinalRetry() {
        finalRetries++;
    }

    List<String> warnings() {
        return List.copyOf(warnings);
    }
}
