package com.aioj.next.ai.agent.digest;

import com.aioj.next.agent.harness.channel.api.EventPublisher;
import com.aioj.next.agent.harness.channel.api.EventSubject;
import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.ai.agent.channel.ChannelEventTypes;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import com.aioj.next.common.error.SafeErrors;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TurnDigest write path (design doc §6.3): on turn completion the rule-based Stub Digest
 * is persisted immediately (status=STUB, digest_version=1) and a TURN_CURATE job is
 * enqueued for the async Curator to upgrade it (digest_version=2, status=READY).
 * Every failure here is logged and swallowed — digestion must never break chat.
 */
@Service
public class TurnDigestService {

    public static final String JOB_TYPE_TURN_CURATE = "TURN_CURATE";
    public static final String JOB_TYPE_EMBED_DIGEST = "EMBED_DIGEST";
    public static final String JOB_TYPE_BACKFILL = "BACKFILL";

    private static final Logger log = LoggerFactory.getLogger(TurnDigestService.class);

    private final StubDigestFactory stubDigestFactory;
    private final AiTurnDigestMapper digestMapper;
    private final ObjectMapper objectMapper;
    private final EventPublisher channel;

    public TurnDigestService(
            StubDigestFactory stubDigestFactory,
            AiTurnDigestMapper digestMapper,
            ObjectMapper objectMapper,
            EventPublisher channel
    ) {
        this.channel = channel;
        this.stubDigestFactory = stubDigestFactory;
        this.digestMapper = digestMapper;
        this.objectMapper = objectMapper;
    }

    /** Synchronous stub write + async curate enqueue. Never throws. */
    public void recordCompletedTurn(TurnDigestInput input) {
        try {
            StubDigestFactory.BuiltStubDigest stub = stubDigestFactory.build(input);
            AiTurnDigestEntity entity = new AiTurnDigestEntity();
            entity.setTurnId(input.turnId());
            entity.setConversationId(input.conversationId());
            entity.setUserId(input.userId());
            entity.setSummary(stub.summary());
            entity.setStructuredDigest(stub.structuredDigestJson());
            entity.setSearchText(stub.searchText());
            entity.setSourceHash(stub.sourceHash());
            entity.setDigestVersion(stub.digestVersion());
            entity.setStatus(StubDigestFactory.STATUS_STUB);
            entity.setTokenEstimate(stub.tokenEstimate());
            LocalDateTime now = LocalDateTime.now();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            digestMapper.insert(entity);
            channel.publish(HarnessEvent.builder(ChannelEventTypes.TURN_COMPLETED,
                            "turn-completed:" + input.turnId(), "ai-service:tutor")
                    .subject(new EventSubject(String.valueOf(input.userId()), input.conversationId(),
                            input.turnId(), null))
                    .payload(objectMapper.readTree(curatePayload(input)))
                    .build());
        } catch (Exception ex) {
            log.warn("turn digest recording failed turn={} error={}", input.turnId(), SafeErrors.describe(ex));
        }
    }

    private String curatePayload(TurnDigestInput input) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("turnId", input.turnId());
            payload.put("conversationId", input.conversationId());
            payload.put("userId", input.userId());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"turnId\":\"" + input.turnId() + "\"}";
        }
    }
}
