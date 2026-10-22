package com.aioj.next.contract.problem;

/** Why a problem was recommended. The client words it from the code and its params. */
public enum ProblemRecommendationReason {
    /** Matches a confirmed weakness in the learning profile; param {@code tag}. */
    PROFILE_WEAKNESS,
    /** Attempted in practice and not solved yet; param {@code attempts}. */
    UNSOLVED_ATTEMPT,
    /** Shares a topic with problems the student failed and has not solved; param {@code tag}. */
    WEAK_TAG,
    /** Not attempted yet. */
    NEW_PROBLEM,
    /** A first problem for a student with no practice history, picked easy to hard. */
    COLD_START
}
