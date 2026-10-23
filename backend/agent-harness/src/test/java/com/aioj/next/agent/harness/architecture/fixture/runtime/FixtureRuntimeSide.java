package com.aioj.next.agent.harness.architecture.fixture.runtime;

import com.aioj.next.agent.harness.architecture.fixture.tool.internal.FixtureInternalHelper;
import org.assertj.core.api.Assertions;

/** Fixture: uses another subpackage's internal class and a library outside the JDK and Jackson. */
public final class FixtureRuntimeSide {
    public String crossesIntoInternal() {
        return FixtureInternalHelper.help();
    }

    public Object usesAForbiddenLibrary() {
        return Assertions.assertThat(1);
    }
}
