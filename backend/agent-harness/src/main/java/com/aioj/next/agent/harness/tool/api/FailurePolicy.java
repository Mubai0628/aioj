package com.aioj.next.agent.harness.tool.api;

/**
 * What an infrastructure failure of a tool (timeout, crash, no capacity) means
 * for the run. Model mistakes such as bad arguments or a denied call are
 * always returned to the model as data, whatever the policy.
 */
public enum FailurePolicy {
    /** The failure goes back to the model as data and the run continues. */
    FAIL_OPEN,
    /**
     * The run ends with a typed failure: the tool guards data the answer must
     * not be produced without.
     */
    FAIL_CLOSED
}
