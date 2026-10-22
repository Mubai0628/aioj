package com.aioj.next.contract.problem;

/** Why a semantic search was answered by keyword instead. */
public enum ProblemSearchFallbackReason {
    SEMANTIC_DISABLED,
    /** The user ran too many semantic searches in the last minute. */
    RATE_LIMITED,
    /** No ready vectors for the current embedding model yet. */
    INDEX_NOT_READY,
    EMBEDDING_UNAVAILABLE
}
