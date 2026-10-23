package com.aioj.next.agent.harness.architecture.fixture.tool;

import com.aioj.next.agent.harness.architecture.fixture.runtime.FixtureRuntimeSide;
import com.aioj.next.agent.harness.architecture.fixture.tool.internal.FixtureInternalHelper;

/** Fixture: may use its own subpackage's internal class, must not reach up into runtime. */
public final class FixtureToolSide {
    public String allowed() {
        return FixtureInternalHelper.help();
    }

    public String reachesUp() {
        return new FixtureRuntimeSide().toString();
    }
}
