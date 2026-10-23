package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ModelStreamListener;
import com.aioj.next.ai.model.api.ModelUsage;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.api.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds one answer out of stream chunks: text and reasoning in order, tool calls
 * merged by index (only the first fragment of a call carries its id and name),
 * the finish reason and the usage wherever the provider puts it.
 */
public final class StreamAccumulator {
    private final ModelStreamListener listener;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, PendingCall> calls = new TreeMap<>();
    private String finishReason;
    private ModelUsage usage = ModelUsage.NONE;
    private boolean outputStarted;

    public StreamAccumulator(ModelStreamListener listener) {
        this.listener = listener == null ? ModelStreamListener.NONE : listener;
    }

    void text(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        outputStarted = true;
        text.append(delta);
        listener.onTextDelta(delta);
    }

    /** Reasoning stays here: it is never handed to the listener. */
    void reasoning(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        outputStarted = true;
        reasoning.append(delta);
    }

    void toolCall(int index, String id, String wireName, String argumentsFragment) {
        outputStarted = true;
        PendingCall call = calls.computeIfAbsent(index, key -> new PendingCall());
        if (id != null && !id.isEmpty() && call.id == null) {
            call.id = id;
        }
        if (wireName != null && !wireName.isEmpty() && call.name == null) {
            call.name = ToolNameCodec.toInternal(wireName);
            listener.onToolCallStarted(index, call.name);
        }
        if (argumentsFragment != null) {
            call.arguments.append(argumentsFragment);
        }
    }

    void finish(String reason) {
        if (reason != null && !reason.isEmpty()) {
            finishReason = reason;
        }
    }

    void usage(ModelUsage value) {
        if (value != null && value.reported()) {
            usage = value;
        }
    }

    /** Whether any delta arrived; a failure after that is never retried. */
    public boolean outputStarted() {
        return outputStarted;
    }

    public boolean finished() {
        return finishReason != null;
    }

    public ModelResponse toResponse(ProviderId provider, String model) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (PendingCall call : calls.values()) {
            String arguments = call.arguments.length() == 0 ? "{}" : call.arguments.toString();
            toolCalls.add(new ToolCall(call.id == null ? "" : call.id, call.name == null ? "" : call.name, arguments));
        }
        return new ModelResponse(text.toString(), reasoning.length() == 0 ? null : reasoning.toString(), toolCalls,
                finishReason, usage, provider, model);
    }

    private static final class PendingCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
