package com.aioj.next.ai.agent.problem.tool;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class TutorCodeTimeoutTest {
    @Test
    void ordinaryRunsKeepHeadroomAndLargeBatchesRetainTheirExistingRuntimeBudget() {
        assertThat(TutorCodeExecutor.batchReadTimeout(3, 1000, true)).isEqualTo(Duration.ofMinutes(10));
        Duration cached = TutorCodeExecutor.batchReadTimeout(200, 30_000, true);
        Duration uncached = TutorCodeExecutor.batchReadTimeout(200, 30_000, false);
        assertThat(cached).isGreaterThan(Duration.ofMillis(200L * 30_000));
        assertThat(uncached).isGreaterThan(cached);
        assertThat(TutorCodeExecutor.batchReadTimeout(0, 0, false)).isEqualTo(Duration.ofMinutes(10));
    }
}
