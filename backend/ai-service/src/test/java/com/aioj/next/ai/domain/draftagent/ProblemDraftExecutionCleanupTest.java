package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.aioj.next.ai.persistence.mapper.ProblemDraftExecutionMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Plan S6: draft executions keep their output 24 hours; the hourly cleanup counts what it removes. */
class ProblemDraftExecutionCleanupTest {
    @Test
    void expiredExecutionsAreDeletedAndCounted() {
        ProblemDraftExecutionMapper mapper = mock(ProblemDraftExecutionMapper.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProblemDraftExecutionService service = new ProblemDraftExecutionService(mapper, mock(DraftSandboxClient.class), meters);
        when(mapper.delete(any())).thenReturn(3, 0);

        assertThat(service.cleanupExpired()).isEqualTo(3);
        assertThat(service.cleanupExpired()).isZero();

        assertThat(meters.counter("aioj.retention.deleted", "category", "draft_executions").count()).isEqualTo(3);
    }
}
