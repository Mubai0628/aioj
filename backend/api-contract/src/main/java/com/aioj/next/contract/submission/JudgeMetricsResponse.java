package com.aioj.next.contract.submission;

import java.time.Instant;

/**
 * Judge pipeline health for the admin monitor. Worker counters are null when the
 * judge worker could not be reached; queue depths are null when the broker could
 * not be asked.
 */
public record JudgeMetricsResponse(
        boolean workerReachable,
        Long started,
        Long finished,
        Long failed,
        Long systemErrors,
        Integer inFlight,
        Long averageQueueWaitMillis,
        Long averageJudgeMillis,
        Instant lastFinishedAt,
        Instant lastFailureAt,
        String lastFailureType,
        Long queueReady,
        Integer queueConsumers,
        Long deadLetterReady,
        Instant collectedAt
) {
}
