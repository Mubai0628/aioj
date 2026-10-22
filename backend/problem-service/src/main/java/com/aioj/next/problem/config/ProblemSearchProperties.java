package com.aioj.next.problem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "aioj.problem-search")
public class ProblemSearchProperties {
    /** Semantic search and indexing; when off, search is keyword only. */
    private boolean semanticEnabled = true;
    /** Characters of a problem embedded: title, tags and difficulty first, then the statement. */
    private int maxInputChars = 2000;
    private int maxQueryChars = 200;
    /** Semantic queries a user may run per minute; beyond it search answers by keyword. 0 disables the limit. */
    private int queryRateLimitPerMinute = 20;
    private int embedBatchSize = 10;
    /** Embedding attempts per content version before a row stays failed. */
    private int maxAttempts = 8;
    private Duration queryVectorTtl = Duration.ofHours(1);
    /** How stale the in-memory vectors may get before a query refreshes them. */
    private Duration vectorRefreshInterval = Duration.ofSeconds(30);

    public boolean isSemanticEnabled() { return semanticEnabled; }
    public void setSemanticEnabled(boolean semanticEnabled) { this.semanticEnabled = semanticEnabled; }
    public int getMaxInputChars() { return maxInputChars; }
    public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
    public int getMaxQueryChars() { return maxQueryChars; }
    public void setMaxQueryChars(int maxQueryChars) { this.maxQueryChars = maxQueryChars; }
    public int getQueryRateLimitPerMinute() { return queryRateLimitPerMinute; }
    public void setQueryRateLimitPerMinute(int queryRateLimitPerMinute) { this.queryRateLimitPerMinute = queryRateLimitPerMinute; }
    public int getEmbedBatchSize() { return embedBatchSize; }
    public void setEmbedBatchSize(int embedBatchSize) { this.embedBatchSize = embedBatchSize; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getQueryVectorTtl() { return queryVectorTtl; }
    public void setQueryVectorTtl(Duration queryVectorTtl) { this.queryVectorTtl = queryVectorTtl; }
    public Duration getVectorRefreshInterval() { return vectorRefreshInterval; }
    public void setVectorRefreshInterval(Duration vectorRefreshInterval) { this.vectorRefreshInterval = vectorRefreshInterval; }
}
