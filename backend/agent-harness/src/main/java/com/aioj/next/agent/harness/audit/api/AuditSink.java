package com.aioj.next.agent.harness.audit.api;

import java.time.Instant;
import java.util.List;

/**
 * Where run and policy-decision audit records go (plan §3.3). A failed write
 * is logged and counted by the sink and never blocks the conversation; the one
 * exception, decisions a guard must record before it may allow an answer, is
 * the guard's to enforce (plan §3.5).
 */
public interface AuditSink {
    void runFinished(RunRecord record);

    void decision(DecisionRecord record);

    record RunRecord(String runId, String scenario, String status, int steps, int toolCalls, long inputTokens,
                     long outputTokens, List<String> warnings, Instant finishedAt) {
        public RunRecord {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** One policy decision; {@code policy} and {@code decision} are the deciding component's own names. */
    record DecisionRecord(String runId, String policy, String decision, String reasonCode, Instant decidedAt) {
    }
}
