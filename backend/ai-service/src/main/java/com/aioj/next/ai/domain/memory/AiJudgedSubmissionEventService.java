package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.agent.channel.ChannelEventTypes;
import com.aioj.next.ai.persistence.entity.AiDomainEventEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.submission.SubmissionStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AiJudgedSubmissionEventService {
    private static final String SOURCE_TYPE_SUBMISSION = "submission";

    private final AiMemoryEventService eventService;

    public AiJudgedSubmissionEventService(AiMemoryEventService eventService) {
        this.eventService = eventService;
    }

    public AiDomainEventEntity recordJudgedSubmission(AiJudgedSubmissionEventRequest request) {
        validate(request);
        String sourceId = String.valueOf(request.submissionId());
        String eventKey = "submission-judged-safe:" + request.submissionId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", request.userId());
        payload.put("submissionId", request.submissionId());
        payload.put("problemId", request.problemId());
        payload.put("status", request.status().name());
        payload.put("language", request.language() == null ? "" : request.language());
        payload.put("contestId", request.contestId());
        payload.put("contestRunId", request.contestRunId());
        payload.put("contestProblemId", request.contestProblemId());
        payload.put("judgedAt", request.judgedAt() == null ? "" : request.judgedAt().toString());
        return eventService.recordEvent(
                ChannelEventTypes.SUBMISSION_JUDGED,
                request.userId(),
                SOURCE_TYPE_SUBMISSION,
                sourceId,
                eventKey,
                payload,
                AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE
        );
    }

    private void validate(AiJudgedSubmissionEventRequest request) {
        if (request == null || request.userId() == null || request.submissionId() == null
                || request.problemId() == null || request.status() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Judged submission event is missing required identifiers");
        }
        if (request.status() == SubmissionStatus.QUEUED || request.status() == SubmissionStatus.RUNNING) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Judged submission event must be terminal");
        }
    }
}
