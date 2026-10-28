package com.aioj.next.agent.harness.context.api;

/** Points back to the authoritative record a section was derived from. */
public record ContextSource(String type, String id) {
}
