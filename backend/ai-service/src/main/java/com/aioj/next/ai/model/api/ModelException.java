package com.aioj.next.ai.model.api;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;

import java.time.Duration;

/**
 * Failure of a model call (plan A1.7). The message is always safe to show: it never
 * contains the provider's response body, which can echo request fragments. The
 * provider's {@code error.type} is kept separately for logs and classification.
 *
 * <p>It is a {@link DomainException} with the error codes the old clients used
 * (configuration and capability problems are bad requests, provider failures are
 * internal errors), so the HTTP contract does not change when a caller moves over.</p>
 */
public class ModelException extends DomainException {
    private final ModelErrorKind kind;
    private final ProviderId provider;
    private final String model;
    private final Integer httpStatus;
    private final String providerErrorType;
    private final Duration retryAfter;
    private final boolean outputStarted;

    public ModelException(ModelErrorKind kind, ProviderId provider, String model, String message) {
        this(kind, provider, model, message, null, null, null, false);
    }

    public ModelException(ModelErrorKind kind, ProviderId provider, String model, String message,
                          Integer httpStatus, String providerErrorType, Duration retryAfter, boolean outputStarted) {
        super(errorCodeFor(kind), message);
        this.kind = kind;
        this.provider = provider;
        this.model = model;
        this.httpStatus = httpStatus;
        this.providerErrorType = providerErrorType;
        this.retryAfter = retryAfter;
        this.outputStarted = outputStarted;
    }

    public ModelErrorKind kind() {
        return kind;
    }

    public ProviderId provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    /** The provider's HTTP status, when the failure came from an HTTP answer. */
    public Integer httpStatus() {
        return httpStatus;
    }

    /** The provider's {@code error.type} or {@code error.code}, when it sent one. */
    public String providerErrorType() {
        return providerErrorType;
    }

    /** The provider's Retry-After, when it sent one. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** Whether the stream had already delivered output; such a call is never retried. */
    public boolean outputStarted() {
        return outputStarted;
    }

    /** The same failure, marked as having happened after output reached the caller. */
    public ModelException afterOutput() {
        if (outputStarted) {
            return this;
        }
        ModelException marked = new ModelException(kind, provider, model, getMessage(), httpStatus,
                providerErrorType, retryAfter, true);
        marked.initCause(this);
        return marked;
    }

    private static ErrorCode errorCodeFor(ModelErrorKind kind) {
        return switch (kind) {
            case CONFIGURATION_INVALID, CAPABILITY_UNSUPPORTED, THINKING_UNAVAILABLE -> ErrorCode.BAD_REQUEST;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
