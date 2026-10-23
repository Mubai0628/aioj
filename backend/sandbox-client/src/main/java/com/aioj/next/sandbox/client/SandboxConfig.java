package com.aioj.next.sandbox.client;

import java.time.Duration;

/**
 * Where go-judge is and how to talk to it. {@code endpoint} may be the base
 * URL or the run URL as deployments configure it (".../run" or
 * ".../execute"); non-positive timeouts fall back to 10 seconds.
 */
public record SandboxConfig(String endpoint, String token, Duration connectTimeout, Duration readTimeout) {
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public SandboxConfig {
        connectTimeout = resolve(connectTimeout);
        readTimeout = resolve(readTimeout);
    }

    /** The base URL requests are resolved against, without a trailing slash. */
    public String baseUrl() {
        if (endpoint == null || endpoint.isBlank()) {
            return "http://localhost:8090";
        }
        String trimmed = endpoint.trim();
        if (trimmed.endsWith("/run") || trimmed.endsWith("/execute")) {
            return trimmed.substring(0, trimmed.lastIndexOf('/'));
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    private static Duration resolve(Duration timeout) {
        return timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
    }
}
