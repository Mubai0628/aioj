package com.aioj.next.contract.contest;

import java.time.Instant;
import java.util.Map;

/** Ids are snowflakes and go out as strings; so do the 16+ digit integers inside {@code evidence} (plan S6). */
public record FairnessAlertResponse(
        String id,
        String contestId,
        String contestRunId,
        FairnessAlertType type,
        FairnessAlertSeverity severity,
        FairnessAlertStatus status,
        String primaryParticipantId,
        String secondaryParticipantId,
        String plagiarismPairId,
        String primaryDisplayName,
        String secondaryDisplayName,
        String title,
        String summary,
        Map<String, Object> evidence,
        String teacherNote,
        String reviewedBy,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
