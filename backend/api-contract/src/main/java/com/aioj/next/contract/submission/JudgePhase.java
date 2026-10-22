package com.aioj.next.contract.submission;

/**
 * Judge pipeline stage a submission or testcase result came from.
 */
public enum JudgePhase {
    COMPILE,
    RUN,
    CHECKER
}
