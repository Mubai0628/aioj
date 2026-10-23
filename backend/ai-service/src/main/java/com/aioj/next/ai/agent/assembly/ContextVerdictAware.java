package com.aioj.next.ai.agent.assembly;

import com.aioj.next.ai.agent.guard.GuardVerdict;

import java.util.function.Supplier;

/** A run interceptor that reads the run's merged L3 verdict; the runner hands it over when the run starts. */
public interface ContextVerdictAware {
    void useContextVerdict(Supplier<GuardVerdict> verdict);
}
