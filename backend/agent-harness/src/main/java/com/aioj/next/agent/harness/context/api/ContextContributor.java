package com.aioj.next.agent.harness.context.api;

import java.time.Duration;

/**
 * Adds sections to the model context (plan §3.3). Each contributor runs within
 * its own time budget and its failures are isolated: a {@link Kind#POLICY}
 * contributor that fails stops the run (fail-closed); any other is skipped and
 * the skip is recorded.
 */
public interface ContextContributor {
    enum Kind {
        /** Carries rules the answer must follow; without it the run must not proceed. */
        POLICY,
        /** Useful but optional context. */
        OPTIONAL
    }

    String name();

    Kind kind();

    Duration timeBudget();

    ContextContribution contribute(ContextRequest request);
}
