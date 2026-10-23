package com.aioj.next.ai.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Transport and resilience settings of the model call package (plan §3.2.3).
 * The per-scope concurrency defaults follow ai-service's current capacity
 * settings (aioj.ai.capacity), so moving callers over changes no limit.
 */
@ConfigurationProperties(prefix = "aioj.ai.model-client")
public class ModelClientProperties {

    private static final Map<String, Integer> DEFAULT_SCOPE_CONCURRENCY = Map.of(
            "TEXT_GENERATION", 8,
            "PROBLEM_DRAFT", 2,
            "INTENT", 4,
            "MEMORY_EXTRACTION", 4,
            "AGENT_CURATOR", 4,
            "REPORT_ANALYSIS", 1,
            "ACCOUNT_IMPORT_PARSE", 2,
            "EMBEDDING", 4);

    private Duration connectTimeout = Duration.ofSeconds(10);
    /** Streaming: until the response headers arrive. */
    private Duration firstByteTimeout = Duration.ofSeconds(60);
    /** One attempt, from sending to the last byte; the call's own deadline can only shorten it. */
    private Duration totalTimeout = Duration.ofSeconds(300);
    private final Retry retry = new Retry();
    private final Breaker breaker = new Breaker();
    private final Bulkhead bulkhead = new Bulkhead();

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getFirstByteTimeout() {
        return firstByteTimeout;
    }

    public void setFirstByteTimeout(Duration firstByteTimeout) {
        this.firstByteTimeout = firstByteTimeout;
    }

    public Duration getTotalTimeout() {
        return totalTimeout;
    }

    public void setTotalTimeout(Duration totalTimeout) {
        this.totalTimeout = totalTimeout;
    }

    public Retry getRetry() {
        return retry;
    }

    public Breaker getBreaker() {
        return breaker;
    }

    public Bulkhead getBulkhead() {
        return bulkhead;
    }

    /** Retries happen only while nothing was produced yet (plan A1.5). */
    public static class Retry {
        private int maxRetries = 2;
        private Duration initialBackoff = Duration.ofMillis(500);
        private Duration maxBackoff = Duration.ofSeconds(2);
        /** A longer Retry-After is not waited for: the call fails instead. */
        private Duration maxRetryAfter = Duration.ofSeconds(10);

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public Duration getMaxRetryAfter() {
            return maxRetryAfter;
        }

        public void setMaxRetryAfter(Duration maxRetryAfter) {
            this.maxRetryAfter = maxRetryAfter;
        }
    }

    /** Per-endpoint circuit breaker. */
    public static class Breaker {
        private int windowSize = 20;
        private double failureRateThreshold = 0.5;
        private int consecutiveFailures = 5;
        private Duration openDuration = Duration.ofSeconds(30);

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }

        public double getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(double failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public int getConsecutiveFailures() {
            return consecutiveFailures;
        }

        public void setConsecutiveFailures(int consecutiveFailures) {
            this.consecutiveFailures = consecutiveFailures;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }
    }

    /** Per-scope concurrency limit with a bounded wait. */
    public static class Bulkhead {
        private int defaultConcurrency = 2;
        private int maxWaiting = 32;
        private Duration maxWait = Duration.ofSeconds(60);
        /** Overrides by scope name, such as {@code TEXT_GENERATION: 6}. */
        private Map<String, Integer> scopes = new LinkedHashMap<>();

        public int getDefaultConcurrency() {
            return defaultConcurrency;
        }

        public void setDefaultConcurrency(int defaultConcurrency) {
            this.defaultConcurrency = defaultConcurrency;
        }

        public int getMaxWaiting() {
            return maxWaiting;
        }

        public void setMaxWaiting(int maxWaiting) {
            this.maxWaiting = maxWaiting;
        }

        public Duration getMaxWait() {
            return maxWait;
        }

        public void setMaxWait(Duration maxWait) {
            this.maxWait = maxWait;
        }

        public Map<String, Integer> getScopes() {
            return scopes;
        }

        public void setScopes(Map<String, Integer> scopes) {
            this.scopes = scopes == null ? new LinkedHashMap<>() : scopes;
        }

        /**
         * Keys are compared without case or separators: relaxed binding turns
         * {@code scopes.TEXT_GENERATION} into other spellings depending on the source.
         */
        public int concurrencyFor(String scope) {
            String key = normalized(scope);
            Integer configured = null;
            for (Map.Entry<String, Integer> entry : scopes.entrySet()) {
                if (normalized(entry.getKey()).equals(key)) {
                    configured = entry.getValue();
                }
            }
            if (configured == null) {
                for (Map.Entry<String, Integer> entry : DEFAULT_SCOPE_CONCURRENCY.entrySet()) {
                    if (normalized(entry.getKey()).equals(key)) {
                        configured = entry.getValue();
                    }
                }
            }
            return Math.max(1, configured == null || configured <= 0 ? defaultConcurrency : configured);
        }

        private static String normalized(String scope) {
            return scope == null ? "" : scope.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        }
    }
}
