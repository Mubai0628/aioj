package com.aioj.next.ai.model.transport;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * Minimal server-sent events reader: returns the {@code data} of one event at a
 * time. Multi-line data is joined with newlines; comments (keep-alives) and the
 * {@code event}, {@code id} and {@code retry} fields are skipped.
 */
final class SseEvents {
    private final BufferedReader reader;

    SseEvents(BufferedReader reader) {
        this.reader = reader;
    }

    /** The next event's data, or {@code null} at the end of the stream. */
    String next() throws IOException {
        StringBuilder data = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data != null) {
                    return data.toString();
                }
                continue;
            }
            if (line.charAt(0) == ':') {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            if (!"data".equals(field)) {
                continue;
            }
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (data == null) {
                data = new StringBuilder(value);
            } else {
                data.append('\n').append(value);
            }
        }
        return data == null ? null : data.toString();
    }
}
