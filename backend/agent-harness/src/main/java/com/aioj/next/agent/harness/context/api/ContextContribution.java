package com.aioj.next.agent.harness.context.api;

import java.util.List;

/** The sections one contributor adds, plus the warnings it wants recorded in the manifest. */
public record ContextContribution(String contributor, List<ContextSection> sections, List<String> warnings) {
    public ContextContribution {
        sections = sections == null ? List.of() : List.copyOf(sections);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ContextContribution empty(String contributor) {
        return new ContextContribution(contributor, List.of(), List.of());
    }
}
