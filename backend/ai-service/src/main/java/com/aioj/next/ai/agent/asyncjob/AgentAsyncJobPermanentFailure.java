package com.aioj.next.ai.agent.asyncjob;

/**
 * A handler's failure that no retry can fix, such as a payload or an owner that will
 * never match: the worker parks the job as FAILED at once instead of backing off.
 */
public class AgentAsyncJobPermanentFailure extends RuntimeException {
    public AgentAsyncJobPermanentFailure(String message) {
        super(message);
    }
}
