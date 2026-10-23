package com.aioj.next.ai.model.api;

/** Typed failure categories of a model call (plan A1.7). */
public enum ModelErrorKind {
    /** Disabled configuration, missing API key, unknown provider, wrong endpoint. */
    CONFIGURATION_INVALID,
    /** The provider rejected the API key or the account has no permission. */
    AUTHENTICATION,
    /** The provider asked us to slow down; retried with backoff while nothing was produced yet. */
    RATE_LIMITED,
    /** Balance or quota used up; retrying cannot help. */
    QUOTA_EXHAUSTED,
    TIMEOUT,
    /** Connection failures, 5xx answers, an open circuit or a full local bulkhead. */
    UNAVAILABLE,
    /** The provider or model cannot do what the request asks, for example native tool_choice=required. */
    CAPABILITY_UNSUPPORTED,
    /** The model rejected the requested thinking mode; callers may retry without thinking. */
    THINKING_UNAVAILABLE,
    /** The provider rejected the request itself (HTTP 400 or 422). */
    INVALID_REQUEST,
    /** The provider answered with something that could not be parsed. */
    INVALID_RESPONSE,
    CANCELLED
}
