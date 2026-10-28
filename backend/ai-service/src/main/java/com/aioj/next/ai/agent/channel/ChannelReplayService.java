package com.aioj.next.ai.agent.channel;

import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService;
import com.aioj.next.ai.domain.OperationAuditWriter;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiChannelReplayRequest;
import com.aioj.next.contract.ai.AiChannelReplayResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The operator's replay (plan T1.7, item 8, minimal version): a subscriber's dead letters, or one
 * event's, go back to PENDING with a fresh attempt budget, and each replay is one operation
 * audit row. Only consumers that subscribe to an event type can be replayed.
 */
@Service
public class ChannelReplayService {
    static final String AUDIT_ACTION = "AI_CHANNEL_REPLAY";
    static final String AUDIT_RESOURCE = "AI_CHANNEL_EVENT";
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private final AgentAsyncJobService jobService;
    private final HarnessEventChannel channel;
    private final OperationAuditWriter auditWriter;

    public ChannelReplayService(AgentAsyncJobService jobService, HarnessEventChannel channel,
                                OperationAuditWriter auditWriter) {
        this.jobService = jobService;
        this.channel = channel;
        this.auditWriter = auditWriter;
    }

    @Transactional
    public AiChannelReplayResponse replay(Long operatorId, AiChannelReplayRequest request) {
        String consumer = request == null || request.consumer() == null ? "" : request.consumer().strip();
        if (!channel.consumers().contains(consumer)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Unknown channel consumer");
        }
        Long eventId = request.eventId();
        int limit = request.limit() == null ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, request.limit()));
        int requeued = jobService.requeueFailed(consumer, eventId == null ? null : String.valueOf(eventId), limit);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("consumer", consumer);
        summary.put("limit", limit);
        summary.put("requeued", requeued);
        auditWriter.record(AUDIT_ACTION, AUDIT_RESOURCE, eventId, "SUCCEEDED", summary,
                operatorId, null, null, null);
        return new AiChannelReplayResponse(consumer, eventId, requeued);
    }
}
