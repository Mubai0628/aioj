package com.aioj.next.contract.ai;

import java.time.Instant;

/**
 * Plan 15.5: the limits of the self-test run, set by an administrator.
 *
 * @param maxConcurrency     runs at the same time, site-wide; a run past it is told the runner is busy
 * @param minIntervalMillis  the least time between two runs of one person; 0 turns the gap off
 */
public record AiCodeRunSettings(
        boolean enabled,
        int maxConcurrency,
        int perMinuteLimit,
        int perDayLimit,
        int minIntervalMillis,
        Instant updatedAt,
        Long updatedBy
) {
}
