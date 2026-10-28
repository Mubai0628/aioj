package com.aioj.next.agent.harness.profile.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A learner or user profile as the Harness sees it: search its signals and
 * submit new ones. Reads may fail open; explicit user changes are synchronous and fail closed.
 */
public interface ProfilePort {
    List<ProfileSignal> search(ProfileQuery query);

    void submit(ProfileSignal signal);

    /** Persist a caller-validated change in the caller's transaction. */
    default void update(ProfileChange change) {
        throw new UnsupportedOperationException("profile updates are not supported by this port");
    }

    record ProfileQuery(String subjectId, String topic, int limit) {
    }

    record ProfileSignal(String subjectId, String topic, String signal, double weight, String evidenceRef,
                         Instant observedAt) {
    }

    record ProfileChange(String subjectId, String itemId, String label, String state, BigDecimal confidence,
                         LocalDateTime disabledAt, LocalDateTime updatedAt, boolean clearDisabledAt) {
    }
}
