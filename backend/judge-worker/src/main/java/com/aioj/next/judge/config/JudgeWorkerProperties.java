package com.aioj.next.judge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "aioj.judge")
public class JudgeWorkerProperties {
    private String sandboxEndpoint = "http://localhost:8090/execute";
    private String sandboxToken = "";
    private Duration sandboxTimeout = Duration.ofSeconds(10);
    private List<String> languageWhitelist = List.of("java", "cpp", "python");
    private String cacheRoot = System.getProperty("user.home") + "/.ai-oj-next/judge-cache";
    private String problemServiceBaseUrl = "http://problem-service:8202";
    private String internalApiToken = "";
    private int stdoutBaseCollectLimitBytes = 64 * 1024;
    private int stdoutExtraCollectBytes = 64 * 1024;
    private int stdoutMaxCollectLimitBytes = 4 * 1024 * 1024;
    private int stderrCollectLimitBytes = 64 * 1024;
    private Duration sandboxReadTimeout = Duration.ofSeconds(370);
    private int maxSourceBytes = 256 * 1024;
    private int maxRunTimeLimitMillis = 30_000;
    private int maxScriptTimeLimitMillis = 180_000;
    private int maxMemoryLimitKb = 1_048_576;
    private int maxProcessLimit = 80;
    private long compileTimeMultiplier = 10L;
    /** Upper bound for compiler CPU time; 0 keeps the max script time limit as the bound. */
    private long compileMaxTimeMillis = 0L;
    private long compileMemoryMultiplier = 2L;
    private long cppCompileMinMemoryKb = 262_144L;
    private long javaCompileMinMemoryKb = 524_288L;
    private long checkerCompileMinMemoryKb = 262_144L;

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

    public String getProblemServiceBaseUrl() {
        return problemServiceBaseUrl;
    }

    public void setProblemServiceBaseUrl(String problemServiceBaseUrl) {
        this.problemServiceBaseUrl = problemServiceBaseUrl;
    }

    public String getInternalApiToken() {
        return internalApiToken;
    }

    public void setInternalApiToken(String internalApiToken) {
        this.internalApiToken = internalApiToken;
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

    public Duration getSandboxReadTimeout() {
        return sandboxReadTimeout;
    }

    public void setSandboxReadTimeout(Duration sandboxReadTimeout) {
        this.sandboxReadTimeout = sandboxReadTimeout;
    }

    public int getMaxSourceBytes() {
        return maxSourceBytes;
    }

    public void setMaxSourceBytes(int maxSourceBytes) {
        this.maxSourceBytes = maxSourceBytes;
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

    public int getMaxProcessLimit() {
        return maxProcessLimit;
    }

    public void setMaxProcessLimit(int maxProcessLimit) {
        this.maxProcessLimit = maxProcessLimit;
    }

    public long getCompileTimeMultiplier() {
        return compileTimeMultiplier;
    }

    public void setCompileTimeMultiplier(long compileTimeMultiplier) {
        this.compileTimeMultiplier = compileTimeMultiplier;
    }

    public long getCompileMaxTimeMillis() {
        return compileMaxTimeMillis;
    }

    public void setCompileMaxTimeMillis(long compileMaxTimeMillis) {
        this.compileMaxTimeMillis = compileMaxTimeMillis;
    }

    public long getCompileMemoryMultiplier() {
        return compileMemoryMultiplier;
    }

    public void setCompileMemoryMultiplier(long compileMemoryMultiplier) {
        this.compileMemoryMultiplier = compileMemoryMultiplier;
    }

    public long getCppCompileMinMemoryKb() {
        return cppCompileMinMemoryKb;
    }

    public void setCppCompileMinMemoryKb(long cppCompileMinMemoryKb) {
        this.cppCompileMinMemoryKb = cppCompileMinMemoryKb;
    }

    public long getJavaCompileMinMemoryKb() {
        return javaCompileMinMemoryKb;
    }

    public void setJavaCompileMinMemoryKb(long javaCompileMinMemoryKb) {
        this.javaCompileMinMemoryKb = javaCompileMinMemoryKb;
    }

    public long getCheckerCompileMinMemoryKb() {
        return checkerCompileMinMemoryKb;
    }

    public void setCheckerCompileMinMemoryKb(long checkerCompileMinMemoryKb) {
        this.checkerCompileMinMemoryKb = checkerCompileMinMemoryKb;
    }
}
