package com.aioj.next.ai.architecture.fixture.caller;

import com.aioj.next.ai.architecture.fixture.model.api.FixtureModelClient;
import com.aioj.next.ai.architecture.fixture.model.internal.FixtureModelInternals;
import com.aioj.next.ai.architecture.fixture.model.provider.FixtureLeakyAdapter;

/** Rule fixture: a caller outside the fake model package; only its first field is allowed. */
public class FixtureCaller {
    FixtureModelClient client;
    FixtureLeakyAdapter adapter;
    int retries = FixtureModelInternals.retries();
}
