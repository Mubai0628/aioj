package com.aioj.next.agent.harness.tool.api;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The tool pipeline the loop calls (plan §3.3): parse → schema → scopes and
 * authorizers → bulkhead execution with a timeout → trimming → audit. Every
 * failure comes back as a {@link ToolOutcome}; nothing is thrown at the loop.
 */
public interface ToolRuntime extends AutoCloseable {
    List<ToolDescriptor> visibleTools(Set<String> grantedScopes);

    /**
     * @param remaining time left in the run; the call's timeout is the
     *                  smaller of this and the descriptor's
     */
    ToolOutcome execute(ToolExecutionContext context, int callSeq, ToolCallRequest call, Duration remaining);

    /** Interrupts the run's calls still in flight. */
    void cancelRun(String runId);

    @Override
    void close();
}
