package com.aioj.next.ai.domain.memory;
import com.aioj.next.ai.agent.channel.ChannelEventTypes;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.submission.SubmissionStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiJudgedSubmissionEventServiceTest {
    private final AiMemoryEventService eventService = mock(AiMemoryEventService.class);
    private final AiJudgedSubmissionEventService service = new AiJudgedSubmissionEventService(eventService);

    @Test
    void recordJudgedSubmissionEnqueuesAnalysisJobIdempotently() {
        AiJudgedSubmissionEventRequest request = new AiJudgedSubmissionEventRequest(
                123L,
                99L,
                7L,
                SubmissionStatus.TIME_LIMIT_EXCEEDED,
                "python",
                1L,
                2L,
                3L,
                Instant.parse("2026-06-25T00:00:00Z")
        );

        service.recordJudgedSubmission(request);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventService).recordEvent(
                eq(ChannelEventTypes.SUBMISSION_JUDGED),
                eq(7L),
                eq("submission"),
                eq("123"),
                eq("submission-judged-safe:123"),
                payload.capture(),
                eq(AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE)
        );
        assertThat(payload.getValue()).containsEntry("userId", 7L).containsEntry("submissionId", 123L)
                .containsEntry("problemId", 99L).containsEntry("status", "TIME_LIMIT_EXCEEDED")
                .containsEntry("contestId", 1L).containsEntry("contestRunId", 2L)
                .containsEntry("contestProblemId", 3L).doesNotContainKey("sourceCode");
    }

    @Test
    void queuedSubmissionIsRejected() {
        AiJudgedSubmissionEventRequest request = new AiJudgedSubmissionEventRequest(
                123L,
                99L,
                7L,
                SubmissionStatus.QUEUED,
                "cpp",
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> service.recordJudgedSubmission(request))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("terminal");
    }
}
