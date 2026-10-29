package com.aioj.next.common.http;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bound below aioj.internal.http; contains no credential, URL, or payload. */
public class InternalHttpProperties {
    public enum TransactionGuard { WARN, ERROR }
    private TransactionGuard transactionGuard = TransactionGuard.WARN;
    private Duration retryGrace = Duration.ofMillis(500);
    public Duration getRetryGrace() { return retryGrace; }
    public void setRetryGrace(Duration value) { retryGrace = value; }
    private Map<String, Client> clients = new LinkedHashMap<>();
    private Map<String, Target> targets = new LinkedHashMap<>();

    public TransactionGuard getTransactionGuard() { return transactionGuard; }
    public void setTransactionGuard(TransactionGuard value) { transactionGuard = value; }
    public Map<String, Client> getClients() { return clients; }
    public void setClients(Map<String, Client> value) { clients = value; }
    public Map<String, Target> getTargets() { return targets; }
    public void setTargets(Map<String, Target> value) { targets = value; }

    public static class Client {
        private Duration connectTimeout;
        private Duration readTimeout;
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration value) { connectTimeout = value; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration value) { readTimeout = value; }
    }

    public static class Target {
        private int bulkheadLimit = 8;
        private int windowSize = 20;
        private double failureRateThreshold = 0.5;
        private int consecutiveFailures = 3;
        private Duration openDuration = Duration.ofSeconds(30);
        public int getBulkheadLimit() { return bulkheadLimit; }
        public void setBulkheadLimit(int value) { bulkheadLimit = value; }
        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int value) { windowSize = value; }
        public double getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(double value) { failureRateThreshold = value; }
        public int getConsecutiveFailures() { return consecutiveFailures; }
        public void setConsecutiveFailures(int value) { consecutiveFailures = value; }
        public Duration getOpenDuration() { return openDuration; }
        public void setOpenDuration(Duration value) { openDuration = value; }
    }
}
