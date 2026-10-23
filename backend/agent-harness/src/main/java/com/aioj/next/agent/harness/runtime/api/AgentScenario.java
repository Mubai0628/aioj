package com.aioj.next.agent.harness.runtime.api;

import java.util.List;
import java.util.Set;

/**
 * A kind of run the assembling service offers, such as a chat helper or a
 * stateful workflow: which tools it may use (by scope), its interceptors, its
 * completion policy, its budget and its model profiles. {@code profile} is
 * used for normal steps; {@code recoveryProfile} for the retry after an empty
 * answer and for the answer-only call after the budget runs out.
 */
public record AgentScenario(String name, Set<String> toolScopes, List<LoopInterceptor> interceptors,
                            CompletionPolicy completionPolicy, RunBudget budget, String profile,
                            String recoveryProfile) {
    public AgentScenario {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("scenario name must not be blank");
        }
        toolScopes = toolScopes == null ? Set.of() : Set.copyOf(toolScopes);
        interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        completionPolicy = completionPolicy == null ? CompletionPolicy.anyAnswer() : completionPolicy;
        if (budget == null) {
            throw new IllegalArgumentException("scenario budget must not be null");
        }
        recoveryProfile = recoveryProfile == null ? profile : recoveryProfile;
    }
}
