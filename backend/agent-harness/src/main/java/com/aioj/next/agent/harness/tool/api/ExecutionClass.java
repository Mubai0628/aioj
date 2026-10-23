package com.aioj.next.agent.harness.tool.api;

/**
 * Which thread bulkhead runs a tool (plan §3.3): a slow or saturated class
 * never starves the others.
 */
public enum ExecutionClass {
    /** Reads server data; no side effects. */
    READ_ONLY,
    /** Runs code in a remote sandbox. */
    SANDBOX,
    /** Writes a candidate that a person or a later step still has to accept. */
    WRITE_CANDIDATE
}
