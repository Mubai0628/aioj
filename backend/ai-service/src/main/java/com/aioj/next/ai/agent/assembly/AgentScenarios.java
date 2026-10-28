package com.aioj.next.ai.agent.assembly;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ai-service's Agent scenarios and the tools each one cannot do its job without (A2.4). A
 * scenario that misses one still runs; the health endpoint reports it as degraded.
 */
public final class AgentScenarios {
    public static final String TUTOR = "tutor";
    public static final String PROBLEM_DRAFT = "problem_draft";

    /** Scenario name to required tool names, in a stable order for the health details. */
    public static final Map<String, List<String>> REQUIRED_TOOLS;

    static {
        Map<String, List<String>> required = new LinkedHashMap<>();
        // The bootstrap tells the model to fetch problems and submissions; recall needs the three context tools.
        required.put(TUTOR, List.of("context.search_exact", "context.search_digests", "context.fetch_sources",
                "problem.fetch_allowed_view", "submission.fetch_allowed_view"));
        // The draft loop needs every workspace step; its completion gate is draft.finalize.
        required.put(PROBLEM_DRAFT, List.of("draft.state.get", "draft.state.patch", "draft.static.validate",
                "draft.code.execute", "draft.samples.run", "draft.testcase.generate", "draft.testcase.run_package",
                "draft.execution.get", "draft.finalize"));
        REQUIRED_TOOLS = java.util.Collections.unmodifiableMap(required);
    }

    private AgentScenarios() {
    }
}
