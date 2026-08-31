package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.agent.policy.ContestPolicyView;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Server-generated execution context for every tool call. The model can never
 * supply or influence these fields (design doc §4.1): identity, policy, and time
 * come from the trusted control plane, not from tool arguments.
 *
 * @param contestPolicy optional contest policy projection (P3-3); null for
 *                      callers that predate the wiring and for turns without
 *                      any contest involvement. Tool-internal ABAC treats null
 *                      as "no contest participation".
 */
public record ToolExecutionContext(
        long userId,
        String conversationId,
        String turnId,
        long turnSeq,
        String policySnapshotId,
        Set<String> grantedScopes,
        Instant serverTime,
        String traceId,
        ContestPolicyView contestPolicy,
        /** Trusted resource bindings (for example workspaceId), never model input. */
        Map<String, String> serverBindings
) {
    public ToolExecutionContext {
        grantedScopes = grantedScopes == null ? Set.of() : Set.copyOf(grantedScopes);
        serverBindings = serverBindings == null ? Map.of() : Map.copyOf(serverBindings);
    }

    /** Pre-resource-binding signature. */
    public ToolExecutionContext(long userId, String conversationId, String turnId, long turnSeq,
                                String policySnapshotId, Set<String> grantedScopes,
                                Instant serverTime, String traceId, ContestPolicyView contestPolicy) {
        this(userId, conversationId, turnId, turnSeq, policySnapshotId, grantedScopes,
                serverTime, traceId, contestPolicy, Map.of());
    }

    /** Pre-P3-3 signature, kept so existing callers/tests compile unchanged. */
    public ToolExecutionContext(long userId, String conversationId, String turnId, long turnSeq,
                                String policySnapshotId, Set<String> grantedScopes,
                                Instant serverTime, String traceId) {
        this(userId, conversationId, turnId, turnSeq, policySnapshotId, grantedScopes,
                serverTime, traceId, null, Map.of());
    }

    public String serverBinding(String name) {
        return name == null ? null : serverBindings.get(name);
    }
}
