package com.aioj.next.agent.harness.context.api;

import java.util.List;

/**
 * Runs a scenario's contributors for one model context (plan §3.3, A3.2). Contributors run
 * one after another, each within its own {@link ContextContributor#timeBudget()} and on a
 * bounded pool, so a slow or broken one costs only its own section:
 * <ul>
 *   <li>an {@link ContextContributor.Kind#OPTIONAL} contributor that fails, runs out of time
 *   or finds the pool full is skipped, and {@code contributor_degraded:<name>:<reason>} is
 *   added to the warnings (reason {@code error}, {@code timeout}, {@code rejected} or
 *   {@code interrupted});</li>
 *   <li>a {@link ContextContributor.Kind#POLICY} contributor that does so throws
 *   {@link ContextPolicyException}: the run must not go ahead without its rules.</li>
 * </ul>
 */
public interface ContextAssembler extends AutoCloseable {
    String DEGRADED_WARNING_PREFIX = "contributor_degraded:";

    AssembledContext assemble(ContextRequest request, List<ContextContributor> contributors);

    /** Stops the pool; contributors still running are interrupted. */
    @Override
    void close();
}
