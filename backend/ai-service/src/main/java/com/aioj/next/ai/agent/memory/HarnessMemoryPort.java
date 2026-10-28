package com.aioj.next.ai.agent.memory;

import com.aioj.next.agent.harness.memory.api.MemoryPort;
import com.aioj.next.ai.agent.port.PortCalls;
import com.aioj.next.ai.agent.port.PortToolResults;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The Harness memory port over ai_memory_claims and ai_memory_evidence (plan T1.6, A3.1):
 * read-only, through {@link PortCalls} so a slow store times out instead of holding the turn.
 * Only ACTIVE claims of the subject are visible (frozen decision Q4). Proposals stay with the
 * {@code memory.propose_candidate} tool until T1.7 puts memory decisions on the event channel.
 */
@Component
public class HarnessMemoryPort implements MemoryPort {
    private static final int MAX_HITS = 10;
    private static final int MAX_EVIDENCE = 3;
    private static final int MAX_EXCERPT_CHARS = 500;

    private final AiMemoryClaimMapper claimMapper;
    private final AiMemoryEvidenceMapper evidenceMapper;
    private final PortCalls portCalls;

    public HarnessMemoryPort(AiMemoryClaimMapper claimMapper, AiMemoryEvidenceMapper evidenceMapper, PortCalls portCalls) {
        this.claimMapper = claimMapper;
        this.evidenceMapper = evidenceMapper;
        this.portCalls = portCalls;
    }

    @Override
    public List<MemoryHit> search(MemoryQuery query) {
        long userId = Long.parseLong(query.subjectId());
        QueryWrapper<AiMemoryClaimEntity> wrapper = new QueryWrapper<AiMemoryClaimEntity>()
                .eq("user_id", userId)
                .eq("status", "ACTIVE");
        if (query.text() != null && !query.text().isBlank()) {
            wrapper.like("canonical_text", query.text().trim()
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"));
        }
        wrapper.orderByDesc("pinned", "updated_at").last("LIMIT " + Math.min(MAX_HITS, Math.max(1, query.limit())));
        List<AiMemoryClaimEntity> claims = portCalls.read(PortToolResults.MEMORY, () -> claimMapper.selectList(wrapper));
        return claims.stream()
                .map(claim -> new MemoryHit(String.valueOf(claim.id), claim.canonicalText,
                        claim.confidence == null ? 0.0 : claim.confidence.doubleValue()))
                .toList();
    }

    @Override
    public List<MemoryEvidence> evidence(String subjectId, String memoryId) {
        long userId = Long.parseLong(subjectId);
        long claimId = Long.parseLong(memoryId);
        List<AiMemoryEvidenceEntity> rows = portCalls.read(PortToolResults.MEMORY, () -> evidenceMapper.selectList(
                new QueryWrapper<AiMemoryEvidenceEntity>()
                        .eq("claim_id", claimId)
                        .eq("user_id", userId)
                        .orderByDesc("created_at")
                        .last("LIMIT " + MAX_EVIDENCE)));
        return rows.stream()
                .map(row -> new MemoryEvidence(row.evidenceType, String.valueOf(row.id), excerpt(row.evidenceText)))
                .toList();
    }

    @Override
    public void propose(MemoryCandidate candidate) {
        throw new UnsupportedOperationException(
                "memory candidates are proposed through memory.propose_candidate; the port is read-only (T1.6)");
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_EXCERPT_CHARS ? text.substring(0, MAX_EXCERPT_CHARS) : text;
    }
}
