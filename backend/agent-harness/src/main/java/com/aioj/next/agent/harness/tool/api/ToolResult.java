package com.aioj.next.agent.harness.tool.api;

import com.aioj.next.agent.harness.context.api.TrustLevel;

import java.util.List;

/**
 * What a tool returns. The model receives it as a JSON envelope marked
 * {@code instructionAllowed=false}: tool output is data, never instructions.
 * {@code dataClass} is the scenario's own label (for example a visibility
 * class); the Harness passes it through without interpreting it.
 */
public record ToolResult(
        ToolStatus status,
        Object data,
        List<SourceRef> sources,
        String dataClass,
        TrustLevel trust,
        boolean truncated,
        String nextCursor,
        List<String> warnings,
        String errorCode,
        String errorMessage
) {
    public ToolResult {
        status = status == null ? ToolStatus.EXECUTION_ERROR : status;
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ToolResult success(Object data, List<SourceRef> sources, String dataClass, TrustLevel trust) {
        return new ToolResult(ToolStatus.SUCCESS, data, sources, dataClass, trust, false, null, List.of(), null, null);
    }

    public static ToolResult failure(ToolStatus status, String errorCode, String errorMessage) {
        return new ToolResult(status, null, List.of(), null, null, false, null, List.of(), errorCode, errorMessage);
    }

    public boolean ok() {
        return status == ToolStatus.SUCCESS;
    }
}
