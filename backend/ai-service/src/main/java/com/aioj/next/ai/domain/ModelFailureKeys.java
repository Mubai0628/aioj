package com.aioj.next.ai.domain;

import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.common.error.SafeErrors;
import com.aioj.next.common.error.UserErrorFeedback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plan S7: what the model settings page hears when a provider call fails, the category as an error
 * key and never the provider's own words; the HTTP status and the failure go to the log.
 */
public final class ModelFailureKeys {
    private static final Logger log = LoggerFactory.getLogger(ModelFailureKeys.class);

    private ModelFailureKeys() {
    }

    public static String of(Throwable failure) {
        if (failure instanceof ModelException model) {
            log.warn("AI provider call failed: kind={} status={} {}", model.kind(), model.httpStatus(),
                    SafeErrors.describe(failure));
            return switch (model.kind()) {
                case AUTHENTICATION -> "ai.keyInvalid";
                case RATE_LIMITED, QUOTA_EXHAUSTED -> "ai.rateLimited";
                case TIMEOUT -> "request.timeout";
                case UNAVAILABLE -> "ai.providerUnavailable";
                case INVALID_RESPONSE -> "ai.responseParseFailed";
                case CONFIGURATION_INVALID, INVALID_REQUEST, CAPABILITY_UNSUPPORTED, THINKING_UNAVAILABLE -> "ai.providerRejected";
                case CANCELLED -> "ai.providerFailed";
            };
        }
        log.warn("AI provider call failed: {}", SafeErrors.describe(failure));
        String key = UserErrorFeedback.storedKey(failure);
        return "system.internal".equals(key) ? "ai.providerFailed" : key;
    }
}
