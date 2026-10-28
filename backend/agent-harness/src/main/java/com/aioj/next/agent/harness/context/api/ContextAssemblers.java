package com.aioj.next.agent.harness.context.api;

import com.aioj.next.agent.harness.context.internal.DefaultContextAssembler;

import java.util.function.UnaryOperator;

public final class ContextAssemblers {
    private ContextAssemblers() {
    }

    /**
     * @param threads    contributor threads; one assembly uses one at a time, so this caps the
     *                   assemblies that run at once
     * @param queueSlots assemblies that may wait for a thread; beyond that a contributor is
     *                   rejected (optional ones are skipped, policy ones fail the run)
     */
    public static ContextAssembler create(int threads, int queueSlots) {
        return new DefaultContextAssembler(threads, queueSlots, UnaryOperator.identity());
    }

    /**
     * @param taskDecorator wraps each contributor task, for example to carry the caller's
     *                      logging context onto the contributor thread
     */
    public static ContextAssembler create(int threads, int queueSlots, UnaryOperator<Runnable> taskDecorator) {
        return new DefaultContextAssembler(threads, queueSlots, taskDecorator);
    }
}
