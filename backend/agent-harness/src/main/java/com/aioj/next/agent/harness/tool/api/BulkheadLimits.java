package com.aioj.next.agent.harness.tool.api;

import java.util.EnumMap;
import java.util.Map;

/**
 * Threads and queue slots per {@link ExecutionClass}. A call that finds its
 * bulkhead full fails at once with {@link ToolStatus#SERVICE_UNAVAILABLE}.
 */
public record BulkheadLimits(Map<ExecutionClass, Integer> threads, Map<ExecutionClass, Integer> queueSlots) {
    public BulkheadLimits {
        threads = complete(threads, 4, 1);
        queueSlots = complete(queueSlots, 16, 0);
    }

    public static BulkheadLimits defaults() {
        return new BulkheadLimits(Map.of(), Map.of());
    }

    public int threadsFor(ExecutionClass executionClass) {
        return threads.get(executionClass);
    }

    public int queueSlotsFor(ExecutionClass executionClass) {
        return queueSlots.get(executionClass);
    }

    private static Map<ExecutionClass, Integer> complete(Map<ExecutionClass, Integer> given, int fallback, int minimum) {
        Map<ExecutionClass, Integer> all = new EnumMap<>(ExecutionClass.class);
        for (ExecutionClass executionClass : ExecutionClass.values()) {
            Integer value = given == null ? null : given.get(executionClass);
            all.put(executionClass, Math.max(minimum, value == null ? fallback : value));
        }
        return Map.copyOf(all);
    }
}
