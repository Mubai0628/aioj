package com.aioj.next.contract.operation;

import java.time.Instant;

/** Ids are snowflakes and go out as strings; so do the 16+ digit integers inside {@code summaryJson} (plan S6). */
public record OperationAuditEventResponse(
        String id,
        String actorUserId,
        String action,
        String actionDisplayName,
        String resourceType,
        String resourceId,
        String contestId,
        String contestRunId,
        String targetUserId,
        String status,
        String traceId,
        String summaryJson,
        Instant createdAt
) {
}
