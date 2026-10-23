package com.aioj.next.agent.harness.memory.api;

import java.util.List;

/**
 * Long-term memory as the Harness sees it (plan §3.3): search, fetch the
 * evidence behind a memory, and propose a candidate. Proposals only ever
 * become CANDIDATE records; accepting them is the owner's decision. Ports
 * have their own timeouts and fail open: a failed lookup means no memory.
 */
public interface MemoryPort {
    List<MemoryHit> search(MemoryQuery query);

    List<MemoryEvidence> evidence(String subjectId, String memoryId);

    void propose(MemoryCandidate candidate);

    record MemoryQuery(String subjectId, String text, int limit) {
    }

    record MemoryHit(String memoryId, String summary, double score) {
    }

    record MemoryEvidence(String sourceType, String sourceId, String excerpt) {
    }

    record MemoryCandidate(String subjectId, String kind, String content, List<String> evidenceRefs) {
        public MemoryCandidate {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }
}
