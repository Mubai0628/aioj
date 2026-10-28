package com.aioj.next.executor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * agent-executor's settings (plan §3.6 rule 3): its own inbound token, the
 * agent sandbox's endpoint and token, and limits. Nothing else: no database,
 * queue, JWT or model settings, and not the shared internal token. Getter
 * names match judge-worker's properties so the endpoints copied from there
 * stay as they were until T1.10 removes judge-worker's copy.
 */
@ConfigurationProperties(prefix = "aioj.executor")
public class AgentExecutorProperties {
    private String internalApiToken = "";
    private String sandboxEndpoint = "http://localhost:8090/execute";
    private String sandboxToken = "";
    private Duration sandboxTimeout = Duration.ofSeconds(10);
    private Duration sandboxReadTimeout = Duration.ofSeconds(370);
    private List<String> languageWhitelist = List.of("java", "cpp", "python");
    private String cacheRoot = System.getProperty("user.home") + "/.ai-oj-next/agent-executor";
    private int maxConcurrentExecutions = 1;
    private int stdoutBaseCollectLimitBytes = 64 * 1024;
    private int stdoutExtraCollectBytes = 64 * 1024;
    private int stdoutMaxCollectLimitBytes = 4 * 1024 * 1024;
    private int stderrCollectLimitBytes = 64 * 1024;
    private int maxSourceBytes = 256 * 1024;
    private int maxInputBytes = 8 * 1024 * 1024;
    private int maxBatchSize = 200;
    private long maxBatchInputBytes = 32L * 1024L * 1024L;
    private int maxRunTimeLimitMillis = 30_000;
    private int maxScriptTimeLimitMillis = 180_000;
    /** Memory one program run may ask for; a request above it is rejected. */
    private int maxMemoryLimitKb = 262_144;
    /** Memory for compiling and for generator scripts, which compile the standard solution in the same run. */
    private int maxCompileMemoryLimitKb = 393_216;
    private int maxProcessLimit = 80;
    /** A5.3: how long a scoped compile is reused, and how many are kept at most (0 turns the cache off). */
    private int compileCacheTtlSeconds = 600;
    private int compileCacheMaxEntries = 256;
    private long maxDraftPackageBytes = 50L * 1024L * 1024L;
    private long maxDraftPackageUncompressedBytes = 100L * 1024L * 1024L;
    private long maxDraftPackageEntryBytes = 8L * 1024L * 1024L;
    private int maxDraftPackageEntries = 500;
    private int maxDraftPackageCases = 200;
    private long maxDraftPackageRunMillis = 540_000L;

    public String getInternalApiToken() {
        return internalApiToken;
    }

    public void setInternalApiToken(String internalApiToken) {
        this.internalApiToken = internalApiToken;
    }

    public String getSandboxEndpoint() {
        return sandboxEndpoint;
    }

    public void setSandboxEndpoint(String sandboxEndpoint) {
        this.sandboxEndpoint = sandboxEndpoint;
    }

    public String getSandboxToken() {
        return sandboxToken;
    }

    public void setSandboxToken(String sandboxToken) {
        this.sandboxToken = sandboxToken;
    }

    public Duration getSandboxTimeout() {
        return sandboxTimeout;
    }

    public void setSandboxTimeout(Duration sandboxTimeout) {
        this.sandboxTimeout = sandboxTimeout;
    }

    public Duration getSandboxReadTimeout() {
        return sandboxReadTimeout;
    }

    public void setSandboxReadTimeout(Duration sandboxReadTimeout) {
        this.sandboxReadTimeout = sandboxReadTimeout;
    }

    public List<String> getLanguageWhitelist() {
        return languageWhitelist;
    }

    public void setLanguageWhitelist(List<String> languageWhitelist) {
        this.languageWhitelist = languageWhitelist;
    }

    public String getCacheRoot() {
        return cacheRoot;
    }

    public void setCacheRoot(String cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public int getMaxConcurrentExecutions() {
        return maxConcurrentExecutions;
    }

    public void setMaxConcurrentExecutions(int maxConcurrentExecutions) {
        this.maxConcurrentExecutions = maxConcurrentExecutions;
    }

    public int getStdoutBaseCollectLimitBytes() {
        return stdoutBaseCollectLimitBytes;
    }

    public void setStdoutBaseCollectLimitBytes(int stdoutBaseCollectLimitBytes) {
        this.stdoutBaseCollectLimitBytes = stdoutBaseCollectLimitBytes;
    }

    public int getStdoutExtraCollectBytes() {
        return stdoutExtraCollectBytes;
    }

    public void setStdoutExtraCollectBytes(int stdoutExtraCollectBytes) {
        this.stdoutExtraCollectBytes = stdoutExtraCollectBytes;
    }

    public int getStdoutMaxCollectLimitBytes() {
        return stdoutMaxCollectLimitBytes;
    }

    public void setStdoutMaxCollectLimitBytes(int stdoutMaxCollectLimitBytes) {
        this.stdoutMaxCollectLimitBytes = stdoutMaxCollectLimitBytes;
    }

    public int getStderrCollectLimitBytes() {
        return stderrCollectLimitBytes;
    }

    public void setStderrCollectLimitBytes(int stderrCollectLimitBytes) {
        this.stderrCollectLimitBytes = stderrCollectLimitBytes;
    }

    public int getMaxSourceBytes() {
        return maxSourceBytes;
    }

    public void setMaxSourceBytes(int maxSourceBytes) {
        this.maxSourceBytes = maxSourceBytes;
    }

    public int getMaxInputBytes() {
        return maxInputBytes;
    }

    public void setMaxInputBytes(int maxInputBytes) {
        this.maxInputBytes = maxInputBytes;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public long getMaxBatchInputBytes() {
        return maxBatchInputBytes;
    }

    public void setMaxBatchInputBytes(long maxBatchInputBytes) {
        this.maxBatchInputBytes = maxBatchInputBytes;
    }

    public int getMaxRunTimeLimitMillis() {
        return maxRunTimeLimitMillis;
    }

    public void setMaxRunTimeLimitMillis(int maxRunTimeLimitMillis) {
        this.maxRunTimeLimitMillis = maxRunTimeLimitMillis;
    }

    public int getMaxScriptTimeLimitMillis() {
        return maxScriptTimeLimitMillis;
    }

    public void setMaxScriptTimeLimitMillis(int maxScriptTimeLimitMillis) {
        this.maxScriptTimeLimitMillis = maxScriptTimeLimitMillis;
    }

    public int getMaxMemoryLimitKb() {
        return maxMemoryLimitKb;
    }

    public void setMaxMemoryLimitKb(int maxMemoryLimitKb) {
        this.maxMemoryLimitKb = maxMemoryLimitKb;
    }

    public int getMaxCompileMemoryLimitKb() {
        return maxCompileMemoryLimitKb;
    }

    public void setMaxCompileMemoryLimitKb(int maxCompileMemoryLimitKb) {
        this.maxCompileMemoryLimitKb = maxCompileMemoryLimitKb;
    }

    public int getCompileCacheTtlSeconds() {
        return compileCacheTtlSeconds;
    }

    public void setCompileCacheTtlSeconds(int compileCacheTtlSeconds) {
        this.compileCacheTtlSeconds = compileCacheTtlSeconds;
    }

    public int getCompileCacheMaxEntries() {
        return compileCacheMaxEntries;
    }

    public void setCompileCacheMaxEntries(int compileCacheMaxEntries) {
        this.compileCacheMaxEntries = compileCacheMaxEntries;
    }

    public int getMaxProcessLimit() {
        return maxProcessLimit;
    }

    public void setMaxProcessLimit(int maxProcessLimit) {
        this.maxProcessLimit = maxProcessLimit;
    }

    public long getMaxDraftPackageBytes() {
        return maxDraftPackageBytes;
    }

    public void setMaxDraftPackageBytes(long maxDraftPackageBytes) {
        this.maxDraftPackageBytes = maxDraftPackageBytes;
    }

    public long getMaxDraftPackageUncompressedBytes() {
        return maxDraftPackageUncompressedBytes;
    }

    public void setMaxDraftPackageUncompressedBytes(long maxDraftPackageUncompressedBytes) {
        this.maxDraftPackageUncompressedBytes = maxDraftPackageUncompressedBytes;
    }

    public long getMaxDraftPackageEntryBytes() {
        return maxDraftPackageEntryBytes;
    }

    public void setMaxDraftPackageEntryBytes(long maxDraftPackageEntryBytes) {
        this.maxDraftPackageEntryBytes = maxDraftPackageEntryBytes;
    }

    public int getMaxDraftPackageEntries() {
        return maxDraftPackageEntries;
    }

    public void setMaxDraftPackageEntries(int maxDraftPackageEntries) {
        this.maxDraftPackageEntries = maxDraftPackageEntries;
    }

    public int getMaxDraftPackageCases() {
        return maxDraftPackageCases;
    }

    public void setMaxDraftPackageCases(int maxDraftPackageCases) {
        this.maxDraftPackageCases = maxDraftPackageCases;
    }

    public long getMaxDraftPackageRunMillis() {
        return maxDraftPackageRunMillis;
    }

    public void setMaxDraftPackageRunMillis(long maxDraftPackageRunMillis) {
        this.maxDraftPackageRunMillis = maxDraftPackageRunMillis;
    }
}
