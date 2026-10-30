package com.aioj.next.ai.domain.problem;

import com.aioj.next.common.error.SafeErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan S7: what an agent-executor failure says to the model and in a draft's verification report.
 * The executor's response body and the HTTP client's exception text stay out: the status and the
 * executor's error key are what there is to act on, and the failure itself goes to the log.
 */
public final class ExecutorFailures {
    public static final String UNAVAILABLE = "The code executor is unavailable; please try again shortly";

    private static final Logger log = LoggerFactory.getLogger(ExecutorFailures.class);
    private static final Pattern ERROR_KEY = Pattern.compile("\"errorKey\"\\s*:\\s*\"([a-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)+)\"");

    private ExecutorFailures() {
    }

    /** The executor answered but refused; its message is made of the code and the error key only. */
    public static final class Refusal extends IllegalStateException {
        public Refusal(int code, String errorKey) {
            super(refusalText(String.valueOf(code), errorKey));
        }
    }

    /** A 4xx answer is a refusal with its status and key; a 5xx, no answer or anything else is unavailability. */
    public static String describe(Exception failure) {
        if (failure instanceof HttpClientErrorException refused) {
            Matcher key = ERROR_KEY.matcher(refused.getResponseBodyAsString());
            return refusalText("HTTP " + refused.getStatusCode().value(), key.find() ? key.group(1) : null);
        }
        if (failure instanceof Refusal refusal) {
            return refusal.getMessage();
        }
        log.warn("agent-executor unavailable: {}", SafeErrors.describe(failure));
        return UNAVAILABLE;
    }

    private static String refusalText(String status, String errorKey) {
        return "The code executor refused this run (" + status + (errorKey == null ? "" : ", " + errorKey) + ")";
    }
}
