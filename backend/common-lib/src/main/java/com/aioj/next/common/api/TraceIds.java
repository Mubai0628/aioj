package com.aioj.next.common.api;

import org.slf4j.MDC;

import java.util.UUID;

public final class TraceIds {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final int MAX_LENGTH = 64;

    private TraceIds() {
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? "n/a" : value;
    }

    /** The incoming id when it is safe to log and echo back; otherwise a fresh one. */
    public static String ensure(String incoming) {
        return isValid(incoming) ? incoming : UUID.randomUUID().toString();
    }

    /**
     * Letters, digits, '.', '_' and '-', at most 64 of them: a caller-supplied id can never
     * split a log line or a response header.
     */
    public static boolean isValid(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
