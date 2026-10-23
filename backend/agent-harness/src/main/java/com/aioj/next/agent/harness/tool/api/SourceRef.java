package com.aioj.next.agent.harness.tool.api;

/** Points back to the authoritative record a result was derived from. */
public record SourceRef(String type, String id) {
}
