package com.aioj.next.problem.persistence.mapper;

/** One user's practice record on one problem, aggregated by {@link SubmissionMapper#practiceHistory}. */
public class PracticeProblemHistory {
    private Long problemId;
    private int attempts;
    /** Wrong answers, runtime errors and limit verdicts; compile and system errors are not knowledge failures. */
    private int failures;
    private boolean accepted;

    public Long getProblemId() { return problemId; }
    public void setProblemId(Long problemId) { this.problemId = problemId; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public int getFailures() { return failures; }
    public void setFailures(int failures) { this.failures = failures; }
    public boolean isAccepted() { return accepted; }
    public void setAccepted(boolean accepted) { this.accepted = accepted; }
}
