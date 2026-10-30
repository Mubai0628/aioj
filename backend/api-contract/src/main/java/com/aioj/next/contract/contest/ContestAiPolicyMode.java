package com.aioj.next.contract.contest;

/**
 * Per-run AI guard policy for contest problems during a running contest run.
 * Configured on the contest blueprint and snapshotted into the run at publish
 * time.
 *
 * <ul>
 *   <li>DEFAULT: private problems are refused; public problems get idea-level
 *       guidance only (no complete submittable code).</li>
 *   <li>STRICT: any message materially referencing a problem of this run is
 *       refused, regardless of problem visibility.</li>
 *   <li>NO_AI: participants of this run get no AI assistance at all while the
 *       run's guard window is open, whatever they ask.</li>
 *   <li>DISABLED: problems of this run are excluded from AI interception.
 *       Retired for blueprints (2026-10-30); it remains only so that runs
 *       published earlier keep reading their snapshot.</li>
 * </ul>
 */
public enum ContestAiPolicyMode {
    DEFAULT,
    STRICT,
    DISABLED,
    NO_AI;

    /**
     * Whether a participant's question materially about one of the run's problems is refused. NO_AI
     * denies at least what STRICT denies, although its own participants are refused earlier, at L1.
     * Run policies bind participants only: a non-participant account is outside every one of them.
     */
    public boolean refusesProblemQuestions() {
        return this == STRICT || this == NO_AI;
    }

    /** The string form of {@link #refusesProblemQuestions()}, for guard records that carry the mode as text. */
    public static boolean refusesProblemQuestions(String mode) {
        return STRICT.name().equals(mode) || NO_AI.name().equals(mode);
    }
}
