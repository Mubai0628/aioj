package com.aioj.next.ai.model.transport;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Status, headers and body of one HTTP answer. For a successful stream the body is
 * empty: the events went to the consumer, and {@code streamCompleted} says whether
 * the provider sent its {@code [DONE]}.
 */
public record TransportResponse(int status, Map<String, List<String>> headers, String body, boolean streamCompleted) {

    public TransportResponse {
        headers = headers == null ? Map.of() : headers;
        body = body == null ? "" : body;
    }

    public boolean successful() {
        return status >= 200 && status < 300;
    }

    public String header(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    /** Retry-After as delta-seconds or an HTTP date; {@code null} when absent or unreadable. */
    public Duration retryAfter() {
        String value = header("Retry-After");
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notSeconds) {
            try {
                Duration until = Duration.between(ZonedDateTime.now(),
                        ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME));
                return until.isNegative() ? Duration.ZERO : until;
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }
    }
}
