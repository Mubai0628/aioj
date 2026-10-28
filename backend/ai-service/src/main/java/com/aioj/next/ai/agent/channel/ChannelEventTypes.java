package com.aioj.next.ai.agent.channel;

import com.aioj.next.ai.domain.memory.AiMemoryJobTypes;

/** Event types on the unified channel (plan §3.4) and the legacy names they replace. */
public final class ChannelEventTypes {
    public static final String TURN_COMPLETED = "TURN_COMPLETED";
    public static final String DIGEST_READY = "DIGEST_READY";
    public static final String PROFILE_SIGNAL_OBSERVED = "PROFILE_SIGNAL_OBSERVED";
    public static final String SUBMISSION_JUDGED = "SUBMISSION_JUDGED";
    public static final String MEMORY_CANDIDATE_PROPOSED = "MEMORY_CANDIDATE_PROPOSED";

    private ChannelEventTypes() {
    }

    /** The channel name for an event type the memory services still record under its old name. */
    public static String fromLegacy(String legacyType) {
        if (AiMemoryJobTypes.EVENT_SUBMISSION_JUDGED_SAFE.equals(legacyType)) {
            return SUBMISSION_JUDGED;
        }
        if (AiMemoryJobTypes.EVENT_AI_MEMORY_MERGE_REQUESTED.equals(legacyType)) {
            return MEMORY_CANDIDATE_PROPOSED;
        }
        return legacyType;
    }
}
