package com.aioj.next.agent.harness.context.api;

import java.util.List;

/** What went into one model call, without any message text (plan §3.3, manifest per call). */
public record ModelCallManifest(String runId, int callSeq, String profile, int messageCount,
                                List<String> toolNames, List<String> warnings) {
    public ModelCallManifest {
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
