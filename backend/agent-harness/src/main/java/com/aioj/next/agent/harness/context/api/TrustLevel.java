package com.aioj.next.agent.harness.context.api;

/**
 * How far a piece of context or a tool result may be trusted. Only
 * {@link #SYSTEM_POLICY} can carry instructions; everything else is data.
 */
public enum TrustLevel {
    SYSTEM_POLICY,
    SERVER_AUTHORITATIVE,
    USER_PROVIDED,
    DERIVED_SUMMARY,
    MODEL_INFERRED,
    EXTERNAL_UNTRUSTED
}
