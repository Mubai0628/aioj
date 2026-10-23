package com.aioj.next.agent.harness.profile.api;

import java.time.Instant;
import java.util.List;

/**
 * A learner or user profile as the Harness sees it: search its signals and
 * submit new ones. Like {@code MemoryPort}, it fails open.
 */
public interface ProfilePort {
    List<ProfileSignal> search(ProfileQuery query);

    void submit(ProfileSignal signal);

    record ProfileQuery(String subjectId, String topic, int limit) {
    }

    record ProfileSignal(String subjectId, String topic, String signal, double weight, String evidenceRef,
                         Instant observedAt) {
    }
}
