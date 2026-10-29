package com.aioj.next.common.security;

import java.util.Map;
import java.util.Set;

/** Explicit deny-by-default inbound route ownership. Prefixes match path segments, not strings. */
public final class InternalApiAccess {
    private InternalApiAccess() { }

    public static Map<String, Set<String>> problemService() {
        return Map.of(
                "/api/v1/internal/ai", Set.of("ai-service"),
                "/api/v1/internal/ai-problem-drafts", Set.of("ai-service"),
                "/api/v1/internal/testcase-packages", Set.of("judge-worker"),
                "/api/v1/internal/submissions/judged-events", Set.of("judge-worker"));
    }

    public static Map<String, Set<String>> aiService() {
        return Map.of("/api/v1/internal/ai", Set.of("problem-service"));
    }

    public static Map<String, Set<String>> judgeWorker() {
        return Map.of("/api/v1/internal/judge", Set.of("problem-service"));
    }

    public static Map<String, Set<String>> agentExecutor() {
        return Map.of("/api/v1/internal/sandbox", Set.of("ai-service"));
    }
}
