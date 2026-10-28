package com.aioj.next.ai.agent.channel;

/** Event types on the unified channel (plan §3.4). */
public final class ChannelEventTypes {
    public static final String TURN_COMPLETED = "TURN_COMPLETED";
    public static final String DIGEST_READY = "DIGEST_READY";
    public static final String PROFILE_SIGNAL_OBSERVED = "PROFILE_SIGNAL_OBSERVED";
    public static final String SUBMISSION_JUDGED = "SUBMISSION_JUDGED";
    public static final String MEMORY_CANDIDATE_PROPOSED = "MEMORY_CANDIDATE_PROPOSED";

    private ChannelEventTypes() {
    }

}
