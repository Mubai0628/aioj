package com.aioj.next.agent.harness.context.api;

import java.util.List;

/**
 * What the contributors produced, in contributor order, and the warnings for the manifest:
 * each contribution's own warnings plus one {@code contributor_degraded:...} entry per
 * skipped optional contributor.
 */
public record AssembledContext(List<ContextContribution> contributions, List<String> warnings) {
    public AssembledContext {
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** All sections, contributor by contributor, in the order each contributor gave them. */
    public List<ContextSection> sections() {
        return contributions.stream().flatMap(contribution -> contribution.sections().stream()).toList();
    }
}
