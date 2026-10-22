package com.aioj.next.ai.agent.model;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;

/**
 * Explicit provider capability failure for a requested thinking/reasoning mode.
 *
 * <p>Callers may opt into a controlled retry with standard tool calling. The
 * exception intentionally carries no provider response body because those
 * bodies can contain request fragments or other sensitive diagnostics.</p>
 */
public final class ThinkingModeUnavailableException extends DomainException {

    private final String provider;
    private final String model;
    private final int httpStatus;

    public ThinkingModeUnavailableException(String provider, String model, int httpStatus) {
        super(ErrorCode.BAD_REQUEST, "Configured AI model does not support the requested thinking mode");
        this.provider = provider;
        this.model = model;
        this.httpStatus = httpStatus;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
