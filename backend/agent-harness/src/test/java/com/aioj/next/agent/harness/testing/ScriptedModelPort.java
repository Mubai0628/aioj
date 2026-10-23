package com.aioj.next.agent.harness.testing;

import com.aioj.next.agent.harness.runtime.api.ModelCall;
import com.aioj.next.agent.harness.runtime.api.ModelPort;
import com.aioj.next.agent.harness.runtime.api.ModelTurn;
import com.aioj.next.agent.harness.runtime.api.TokenUsage;
import com.aioj.next.agent.harness.tool.api.ToolCallRequest;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A model that answers from a script and records every call it gets. */
public final class ScriptedModelPort implements ModelPort {
    public static final TokenUsage USAGE_PER_CALL = new TokenUsage(100, 10, 50);

    private final Deque<Function<ModelCall, ModelTurn>> script = new ArrayDeque<>();
    private final List<ModelCall> calls = new CopyOnWriteArrayList<>();
    private final boolean nativeRequired;

    public ScriptedModelPort(boolean nativeRequired) {
        this.nativeRequired = nativeRequired;
    }

    public ScriptedModelPort answer(String text) {
        return then(call -> turn(text, null, List.of()));
    }

    public ScriptedModelPort toolCalls(ToolCallRequest... toolCalls) {
        return then(call -> turn("", "reasoning-" + (calls.size()), List.of(toolCalls)));
    }

    public ScriptedModelPort then(Function<ModelCall, ModelTurn> step) {
        script.add(step);
        return this;
    }

    public List<ModelCall> calls() {
        return List.copyOf(calls);
    }

    public ModelCall call(int number) {
        return calls.get(number - 1);
    }

    @Override
    public ModelTurn complete(ModelCall call) {
        calls.add(call);
        Function<ModelCall, ModelTurn> step = script.poll();
        if (step == null) {
            throw new AssertionError("model called more often than scripted: call " + calls.size());
        }
        return step.apply(call);
    }

    @Override
    public boolean supportsRequiredToolChoice() {
        return nativeRequired;
    }

    public static ModelTurn turn(String content, String reasoning, List<ToolCallRequest> toolCalls) {
        return new ModelTurn(content, reasoning, toolCalls, toolCalls.isEmpty() ? "stop" : "tool_calls",
                USAGE_PER_CALL, "fake", "fake-model", List.of());
    }

    public static ToolCallRequest call(String id, String tool, String arguments) {
        return new ToolCallRequest(id, tool, arguments);
    }
}
