package com.aioj.next.agent.harness.tool.api;

import com.aioj.next.agent.harness.tool.internal.DescriptorValidator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The registered tools. A tool whose descriptor breaks the rules is left out
 * and reported in {@link #rejections()} instead of stopping startup (A2.4):
 * one broken tool should cost that tool, not the whole service.
 */
public final class ToolCatalog {
    private final Map<String, AgentTool> toolsByName;
    private final List<ToolRejection> rejections;

    private ToolCatalog(Map<String, AgentTool> toolsByName, List<ToolRejection> rejections) {
        this.toolsByName = toolsByName;
        this.rejections = rejections;
    }

    public static ToolCatalog of(List<? extends AgentTool> tools) {
        return of(tools, Set.of());
    }

    /**
     * @param extraForbiddenInputFields input field names (case-insensitive) the
     *        assembling service also treats as server-owned, on top of the
     *        Harness's identity and permission fields
     */
    public static ToolCatalog of(List<? extends AgentTool> tools, Set<String> extraForbiddenInputFields) {
        Map<String, AgentTool> accepted = new LinkedHashMap<>();
        List<ToolRejection> rejected = new ArrayList<>();
        for (AgentTool tool : tools == null ? List.<AgentTool>of() : tools) {
            ToolDescriptor descriptor = tool.descriptor();
            List<String> reasons = DescriptorValidator.violations(descriptor, extraForbiddenInputFields);
            String name = descriptor == null ? null : descriptor.name();
            if (reasons.isEmpty() && accepted.containsKey(name)) {
                reasons = List.of("duplicate tool name");
            }
            if (reasons.isEmpty()) {
                accepted.put(name, tool);
            } else {
                rejected.add(new ToolRejection(name, tool.getClass().getName(), reasons));
            }
        }
        return new ToolCatalog(Map.copyOf(accepted), List.copyOf(rejected));
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(name == null ? null : toolsByName.get(name));
    }

    /** Tools a caller with these scopes may see, sorted by name so the prompt prefix stays cacheable. */
    public List<ToolDescriptor> visible(Set<String> grantedScopes) {
        Set<String> granted = grantedScopes == null ? Set.of() : grantedScopes;
        return toolsByName.values().stream()
                .map(AgentTool::descriptor)
                .filter(descriptor -> granted.containsAll(descriptor.requiredScopes()))
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    public List<ToolRejection> rejections() {
        return rejections;
    }
}
