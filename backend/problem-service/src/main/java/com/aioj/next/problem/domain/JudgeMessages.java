package com.aioj.next.problem.domain;

import com.aioj.next.common.error.UserErrorFeedback;
import com.aioj.next.contract.submission.JudgePhase;
import com.aioj.next.contract.submission.SubmissionStatus;

/**
 * Plan S7: what a client sees of a judge message. A system error is one of judge-worker's error keys;
 * older rows stored the failure's text, which becomes a key here and is never handed out. The other
 * verdicts keep their message (the student's own compile output, the checker's verdict text).
 */
final class JudgeMessages {
    static final String SYSTEM_ERROR = "judge.systemError";

    private JudgeMessages() {
    }

    static String visible(SubmissionStatus status, String message) {
        return status == SubmissionStatus.SYSTEM_ERROR ? UserErrorFeedback.storedKey(message, SYSTEM_ERROR) : message;
    }

    /** A broken custom checker once left its own stderr here; that is the setter's, not the student's. */
    static String visibleStderr(SubmissionStatus status, JudgePhase phase, String stderr) {
        return status == SubmissionStatus.SYSTEM_ERROR && phase == JudgePhase.CHECKER ? null : stderr;
    }
}
