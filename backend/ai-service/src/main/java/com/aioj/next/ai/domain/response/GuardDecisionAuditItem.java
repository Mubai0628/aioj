package com.aioj.next.ai.domain.response;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * One row of the staff guard-decision audit feed (design doc §5.6, P3-7).
 * Ids are snowflakes and are exposed as strings (plan S6); matchedProblemRefs/detail
 * are parsed JSON, never string-wrapped JSON, with their 16+ digit integers as strings.
 */
public record GuardDecisionAuditItem(
        String id,
        String turnId,
        String userId,
        String conversationId,
        String contestRunId,
        String layer,
        String decision,
        String reasonCode,
        JsonNode matchedProblemRefs,
        JsonNode detail,
        Boolean degraded,
        Integer latencyMs,
        Instant createdAt
) {
}
