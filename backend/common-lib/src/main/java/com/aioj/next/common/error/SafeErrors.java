package com.aioj.next.common.error;

import org.springframework.web.client.RestClientResponseException;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * What a log line may say about an exception (T2.5): the exception types down the cause chain with
 * their structured details (HTTP status, error code, SQL state), never a message. Messages carry
 * response bodies, parser snippets and SQL values, and any of those can be a prompt, code, test data
 * or a model's reasoning.
 */
public final class SafeErrors {
    private static final int MAX_CAUSES = 5;
    private static final int MAX_FRAMES = 12;

    private SafeErrors() {
    }

    /** One line, for {@code error={}}: {@code ResourceAccessException <- HttpTimeoutException}. */
    public static String describe(Throwable error) {
        if (error == null) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSES && seen.add(current); depth++) {
            if (depth > 0) {
                out.append(" <- ");
            }
            out.append(typeName(current)).append(details(current));
            current = current.getCause();
        }
        return out.toString();
    }

    /**
     * Where it was thrown, for errors that need finding: each cause's type and its top frames,
     * the way a stack trace reads, without the messages.
     */
    public static String trace(Throwable error) {
        if (error == null) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSES && seen.add(current); depth++) {
            out.append(depth == 0 ? "" : "\nCaused by: ").append(typeName(current)).append(details(current));
            StackTraceElement[] frames = current.getStackTrace();
            for (int i = 0; i < Math.min(MAX_FRAMES, frames.length); i++) {
                out.append("\n\tat ").append(frames[i]);
            }
            if (frames.length > MAX_FRAMES) {
                out.append("\n\t... ").append(frames.length - MAX_FRAMES).append(" more");
            }
            current = current.getCause();
        }
        return out.toString();
    }

    private static String typeName(Throwable error) {
        String name = error.getClass().getName();
        return name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
    }

    private static String details(Throwable error) {
        if (error instanceof RestClientResponseException response) {
            return "[status=" + response.getStatusCode().value() + "]";
        }
        if (error instanceof DomainException domain && domain.errorCode() != null) {
            return "[code=" + domain.errorCode().code() + "]";
        }
        if (error instanceof SQLException sql) {
            return "[sqlState=" + sql.getSQLState() + " vendorCode=" + sql.getErrorCode() + "]";
        }
        return "";
    }
}
