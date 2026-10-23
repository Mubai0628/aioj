package com.aioj.next.agent.harness.runtime.api;

import java.util.List;

/**
 * The messages about to be sent, as {@link LoopInterceptor#beforeModelCall}
 * sees them: readable, and open to appending only. What an interceptor
 * appends stays in the conversation for later steps too.
 */
public interface OutgoingMessages {
    List<HarnessMessage> view();

    /** Index of the first message added by the loop; earlier ones are the run's starting messages. */
    int startingMessageCount();

    void append(HarnessMessage message);
}
