package com.aioj.next.contract.ai;

/** Plan 15.5: new run limits; every field is required, and each has its range. */
public record AiCodeRunSettingsUpdateRequest(
        Boolean enabled,
        Integer maxConcurrency,
        Integer perMinuteLimit,
        Integer perDayLimit,
        Integer minIntervalMillis
) {
}
