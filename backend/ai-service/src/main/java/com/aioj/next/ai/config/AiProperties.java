package com.aioj.next.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "aioj.ai")
public class AiProperties {
    private String provider = "deepseek";
    private String baseUrl = "https://api.deepseek.com/chat/completions";
    private String apiKey = "";
    private String model = "deepseek-v4-pro";
    private String problemServiceUri = "http://localhost:8202";
    /** Plan T1.10: Agent tool execution goes to agent-executor, never to judge-worker or a sandbox. */
    private String agentExecutorUri = "http://localhost:8205";
    /** agent-executor's own inbound token; only ai-service holds it. */
    private String agentExecutorToken = "";
    /** agent-executor's cap on one program's memory; a draft above it is not verified there. */
    private int agentExecutorMaxMemoryLimitKb = 262_144;
    private int problemServiceConnectTimeoutMs = 2000;
    private int problemServiceReadTimeoutMs = 5000;
    private long problemServiceGuardCacheTtlMs = 30000;
    private long dailyLimit = 50;
    private long rollingLimit = 50;
    private int rollingWindowHours = 2;
    private long monthlyLimit = 1000;
    private DeepSeek deepseek = new DeepSeek();
    private Intent intent = new Intent();
    private Embedding embedding = new Embedding();
    private Capacity capacity = new Capacity();
    private Recall recall = new Recall();
    private MemoryJobs memoryJobs = new MemoryJobs();
    private AgentJobs agentJobs = new AgentJobs();
    private Channel channel = new Channel();
    private ProblemDraft problemDraft = new ProblemDraft();
    private AgentCore agentCore = new AgentCore();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProblemServiceUri() {
        return problemServiceUri;
    }

    public void setProblemServiceUri(String problemServiceUri) {
        this.problemServiceUri = problemServiceUri;
    }

    public String getAgentExecutorUri() {
        return agentExecutorUri;
    }

    public void setAgentExecutorUri(String agentExecutorUri) {
        this.agentExecutorUri = agentExecutorUri;
    }

    public String getAgentExecutorToken() {
        return agentExecutorToken;
    }

    public void setAgentExecutorToken(String agentExecutorToken) {
        this.agentExecutorToken = agentExecutorToken;
    }

    public int getAgentExecutorMaxMemoryLimitKb() {
        return agentExecutorMaxMemoryLimitKb;
    }

    public void setAgentExecutorMaxMemoryLimitKb(int agentExecutorMaxMemoryLimitKb) {
        this.agentExecutorMaxMemoryLimitKb = agentExecutorMaxMemoryLimitKb;
    }

    public int getProblemServiceConnectTimeoutMs() {
        return problemServiceConnectTimeoutMs;
    }

    public void setProblemServiceConnectTimeoutMs(int problemServiceConnectTimeoutMs) {
        this.problemServiceConnectTimeoutMs = problemServiceConnectTimeoutMs;
    }

    public int getProblemServiceReadTimeoutMs() {
        return problemServiceReadTimeoutMs;
    }

    public void setProblemServiceReadTimeoutMs(int problemServiceReadTimeoutMs) {
        this.problemServiceReadTimeoutMs = problemServiceReadTimeoutMs;
    }

    public long getProblemServiceGuardCacheTtlMs() {
        return problemServiceGuardCacheTtlMs;
    }

    public void setProblemServiceGuardCacheTtlMs(long problemServiceGuardCacheTtlMs) {
        this.problemServiceGuardCacheTtlMs = problemServiceGuardCacheTtlMs;
    }

    public long getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(long dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public long getRollingLimit() {
        return rollingLimit;
    }

    public void setRollingLimit(long rollingLimit) {
        this.rollingLimit = rollingLimit;
    }

    public int getRollingWindowHours() {
        return rollingWindowHours;
    }

    public void setRollingWindowHours(int rollingWindowHours) {
        this.rollingWindowHours = rollingWindowHours;
    }

    public long getMonthlyLimit() {
        return monthlyLimit;
    }

    public void setMonthlyLimit(long monthlyLimit) {
        this.monthlyLimit = monthlyLimit;
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(DeepSeek deepseek) {
        this.deepseek = deepseek == null ? new DeepSeek() : deepseek;
    }

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent intent) {
        this.intent = intent == null ? new Intent() : intent;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding == null ? new Embedding() : embedding;
    }

    public Capacity getCapacity() {
        return capacity;
    }

    public void setCapacity(Capacity capacity) {
        this.capacity = capacity == null ? new Capacity() : capacity;
    }

    public MemoryJobs getMemoryJobs() {
        return memoryJobs;
    }

    public void setMemoryJobs(MemoryJobs memoryJobs) {
        this.memoryJobs = memoryJobs == null ? new MemoryJobs() : memoryJobs;
    }

    public AgentJobs getAgentJobs() {
        return agentJobs;
    }

    public void setAgentJobs(AgentJobs agentJobs) {
        this.agentJobs = agentJobs == null ? new AgentJobs() : agentJobs;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel == null ? new Channel() : channel;
    }

    /** Unified data-channel operational settings, prefix aioj.ai.channel.*. */
    public static class Channel {
        /** How often the backlog gauges are refreshed. */
        private long metricsRefreshMs = 30_000L;

        public long getMetricsRefreshMs() {
            return metricsRefreshMs;
        }

        public void setMetricsRefreshMs(long metricsRefreshMs) {
            this.metricsRefreshMs = Math.max(1_000L, metricsRefreshMs);
        }
    }

    public Recall getRecall() {
        return recall;
    }

    public void setRecall(Recall recall) {
        this.recall = recall == null ? new Recall() : recall;
    }

    public ProblemDraft getProblemDraft() {
        return problemDraft;
    }

    public void setProblemDraft(ProblemDraft problemDraft) {
        this.problemDraft = problemDraft == null ? new ProblemDraft() : problemDraft;
    }

    public AgentCore getAgentCore() {
        return agentCore;
    }

    public void setAgentCore(AgentCore agentCore) {
        this.agentCore = agentCore == null ? new AgentCore() : agentCore;
    }

    /** Agent Core V3 (new pipeline) budgets and limits; prefix aioj.ai.agent-core.*. */
    public static class AgentCore {
        private int maxAgentSteps = 8;
        private int maxToolCalls = 6;
        private int maxSearchCalls = 3;
        private int maxFetchCalls = 3;
        private int toolResultMaxTokens = 4000;
        private int bootstrapBudgetTokens = 6000;
        private int fetchBudgetTokens = 8000;
        private int recentTurnsLimit = 6;
        private long turnTimeoutMs = 210_000L;
        private String curatorScope = "AGENT_CURATOR";
        private double fingerprintContainmentThreshold = 0.45;
        private ContestSearch contestSearch = new ContestSearch();
        private OutputGuard outputGuard = new OutputGuard();
        private PseudoStream pseudoStream = new PseudoStream();
        private Context context = new Context();

        public int getMaxAgentSteps() {
            return maxAgentSteps;
        }

        public void setMaxAgentSteps(int maxAgentSteps) {
            this.maxAgentSteps = Math.max(1, maxAgentSteps);
        }

        public int getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(int maxToolCalls) {
            this.maxToolCalls = Math.max(0, maxToolCalls);
        }

        public int getMaxSearchCalls() {
            return maxSearchCalls;
        }

        public void setMaxSearchCalls(int maxSearchCalls) {
            this.maxSearchCalls = Math.max(0, maxSearchCalls);
        }

        public int getMaxFetchCalls() {
            return maxFetchCalls;
        }

        public void setMaxFetchCalls(int maxFetchCalls) {
            this.maxFetchCalls = Math.max(0, maxFetchCalls);
        }

        public int getToolResultMaxTokens() {
            return toolResultMaxTokens;
        }

        public void setToolResultMaxTokens(int toolResultMaxTokens) {
            this.toolResultMaxTokens = Math.max(256, toolResultMaxTokens);
        }

        public int getBootstrapBudgetTokens() {
            return bootstrapBudgetTokens;
        }

        public void setBootstrapBudgetTokens(int bootstrapBudgetTokens) {
            this.bootstrapBudgetTokens = Math.max(500, bootstrapBudgetTokens);
        }

        public int getFetchBudgetTokens() {
            return fetchBudgetTokens;
        }

        public void setFetchBudgetTokens(int fetchBudgetTokens) {
            this.fetchBudgetTokens = Math.max(500, fetchBudgetTokens);
        }

        public String getCuratorScope() {
            return curatorScope;
        }

        public void setCuratorScope(String curatorScope) {
            this.curatorScope = curatorScope == null || curatorScope.isBlank() ? "AGENT_CURATOR" : curatorScope.trim();
        }

        public int getRecentTurnsLimit() {
            return recentTurnsLimit;
        }

        public void setRecentTurnsLimit(int recentTurnsLimit) {
            this.recentTurnsLimit = Math.max(0, recentTurnsLimit);
        }

        public long getTurnTimeoutMs() {
            return turnTimeoutMs;
        }

        public void setTurnTimeoutMs(long turnTimeoutMs) {
            this.turnTimeoutMs = Math.max(1L, turnTimeoutMs);
        }

        public double getFingerprintContainmentThreshold() {
            return fingerprintContainmentThreshold;
        }

        public void setFingerprintContainmentThreshold(double fingerprintContainmentThreshold) {
            if (Double.isNaN(fingerprintContainmentThreshold) || fingerprintContainmentThreshold <= 0) {
                return;
            }
            this.fingerprintContainmentThreshold = Math.min(1.0, fingerprintContainmentThreshold);
        }

        public ContestSearch getContestSearch() {
            return contestSearch;
        }

        public void setContestSearch(ContestSearch contestSearch) {
            this.contestSearch = contestSearch == null ? new ContestSearch() : contestSearch;
        }

        public OutputGuard getOutputGuard() {
            return outputGuard;
        }

        public void setOutputGuard(OutputGuard outputGuard) {
            this.outputGuard = outputGuard == null ? new OutputGuard() : outputGuard;
        }

        public PseudoStream getPseudoStream() {
            return pseudoStream;
        }

        public void setPseudoStream(PseudoStream pseudoStream) {
            this.pseudoStream = pseudoStream == null ? new PseudoStream() : pseudoStream;
        }

        public Context getContext() {
            return context;
        }

        public void setContext(Context context) {
            this.context = context == null ? new Context() : context;
        }

        /**
         * Bootstrap context assembly (plan T1.6, A3.2): the contributor pool, each kind's time
         * budget, and the memory and profile sections, which are off until they have been
         * evaluated; prefix aioj.ai.agent-core.context.*.
         */
        public static class Context {
            /** Contributor threads; one turn's assembly uses one at a time. */
            private int threads = 8;
            private int queueCapacity = 64;
            /** Budget of a policy contributor; one that overruns fails the turn (fail-closed). */
            private long policyTimeoutMs = 2_000L;
            /** Budget of an optional contributor; one that overruns is skipped and recorded. */
            private long optionalTimeoutMs = 3_000L;
            /** Budget of one memory or profile port call (context sections and tools). */
            private long portTimeoutMs = 2_000L;
            private boolean memoryEnabled = false;
            private boolean profileEnabled = false;

            public int getThreads() {
                return threads;
            }

            public void setThreads(int threads) {
                this.threads = Math.max(1, threads);
            }

            public int getQueueCapacity() {
                return queueCapacity;
            }

            public void setQueueCapacity(int queueCapacity) {
                this.queueCapacity = Math.max(0, queueCapacity);
            }

            public long getPolicyTimeoutMs() {
                return policyTimeoutMs;
            }

            public void setPolicyTimeoutMs(long policyTimeoutMs) {
                this.policyTimeoutMs = Math.max(1L, policyTimeoutMs);
            }

            public long getOptionalTimeoutMs() {
                return optionalTimeoutMs;
            }

            public void setOptionalTimeoutMs(long optionalTimeoutMs) {
                this.optionalTimeoutMs = Math.max(1L, optionalTimeoutMs);
            }

            public long getPortTimeoutMs() {
                return portTimeoutMs;
            }

            public void setPortTimeoutMs(long portTimeoutMs) {
                this.portTimeoutMs = Math.max(1L, portTimeoutMs);
            }

            public boolean isMemoryEnabled() {
                return memoryEnabled;
            }

            public void setMemoryEnabled(boolean memoryEnabled) {
                this.memoryEnabled = memoryEnabled;
            }

            public boolean isProfileEnabled() {
                return profileEnabled;
            }

            public void setProfileEnabled(boolean profileEnabled) {
                this.profileEnabled = profileEnabled;
            }
        }

        /**
         * L4 output-guard settings (P3-5, design doc §5.4);
         * prefix aioj.ai.agent-core.output-guard.*.
         */
        public static class OutputGuard {
            private FullCode fullCode = new FullCode();

            public FullCode getFullCode() {
                return fullCode;
            }

            public void setFullCode(FullCode fullCode) {
                this.fullCode = fullCode == null ? new FullCode() : fullCode;
            }

            /** FullCodeHeuristicDetector threshold (aioj.ai.agent-core.output-guard.full-code.*). */
            public static class FullCode {
                /** Feature families hit to judge a draft as complete submittable code; clamped to 1..5. */
                private int minFeatures = 4;

                public int getMinFeatures() {
                    return minFeatures;
                }

                public void setMinFeatures(int minFeatures) {
                    this.minFeatures = Math.min(5, Math.max(1, minFeatures));
                }
            }
        }

        /**
         * PseudoStreamReplayer slicing (P3-5, Q2);
         * prefix aioj.ai.agent-core.pseudo-stream.*.
         */
        public static class PseudoStream {
            /** Characters per SSE delta chunk; clamped to at least 50. */
            private int chunkSize = 300;

            public int getChunkSize() {
                return chunkSize;
            }

            public void setChunkSize(int chunkSize) {
                this.chunkSize = Math.max(50, chunkSize);
            }
        }

        /**
         * problem.search anti-enumeration controls (P3-3, design doc §5.5);
         * prefix aioj.ai.agent-core.contest-search.*.
         */
        public static class ContestSearch {
            private RateLimit rateLimit = new RateLimit();

            public RateLimit getRateLimit() {
                return rateLimit;
            }

            public void setRateLimit(RateLimit rateLimit) {
                this.rateLimit = rateLimit == null ? new RateLimit() : rateLimit;
            }

            /** In-memory sliding-window limiter settings (aioj.ai.agent-core.contest-search.rate-limit.*). */
            public static class RateLimit {
                private long windowSeconds = 60;
                private int maxCallsPerWindow = 5;

                public long getWindowSeconds() {
                    return windowSeconds;
                }

                public void setWindowSeconds(long windowSeconds) {
                    this.windowSeconds = Math.max(1L, windowSeconds);
                }

                public int getMaxCallsPerWindow() {
                    return maxCallsPerWindow;
                }

                public void setMaxCallsPerWindow(int maxCallsPerWindow) {
                    this.maxCallsPerWindow = Math.max(1, maxCallsPerWindow);
                }
            }
        }
    }

    /** Agent Core V3 async job worker (ai_async_jobs); prefix aioj.ai.agent-jobs.*. */
    public static class AgentJobs {
        private boolean enabled = true;
        private long pollIntervalMs = 5000;
        private int batchSize = 4;
        private long leaseSeconds = 120;
        private int maxAttempts = 5;
        private long backoffBaseSeconds = 60;
        /**
         * Plan 15.4: how long one job may run before the worker gives up on it and moves on. Longer than one
         * model call attempt may take (300 s by default), so a slow but healthy call is not cut off and paid again.
         */
        private long jobTimeoutSeconds = 330;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = Math.max(1, batchSize);
        }

        public long getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(long leaseSeconds) {
            this.leaseSeconds = Math.max(1, leaseSeconds);
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = Math.max(1, maxAttempts);
        }

        public long getBackoffBaseSeconds() {
            return backoffBaseSeconds;
        }

        public void setBackoffBaseSeconds(long backoffBaseSeconds) {
            this.backoffBaseSeconds = Math.max(1, backoffBaseSeconds);
        }

        public long getJobTimeoutSeconds() {
            return jobTimeoutSeconds;
        }

        public void setJobTimeoutSeconds(long jobTimeoutSeconds) {
            this.jobTimeoutSeconds = Math.max(1, jobTimeoutSeconds);
        }
    }

    public static class DeepSeek {
        private boolean thinkingEnabled = false;
        private String reasoningEffort = "high";

        public boolean isThinkingEnabled() {
            return thinkingEnabled;
        }

        public void setThinkingEnabled(boolean thinkingEnabled) {
            this.thinkingEnabled = thinkingEnabled;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
        }
    }

    public static class ProblemDraft {
        private int maxRepairAttempts = 5;
        private int maxAgentSteps = 20;
        private int maxToolCalls = 16;
        private int maxSandboxCalls = 8;
        private int maxTestcaseGenerations = 2;
        private int maxTestcasePackageRuns = 2;
        private int maxRunMinutes = 15;
        private int jobLeaseMinutes = 0;
        private String testcaseArtifactStorageRoot = System.getProperty("user.home") + "/.ai-oj-next/ai-draft-testcase-artifacts";
        private long maxOfficialCaseBytes = 8L * 1024L * 1024L;
        private long maxOfficialPackageBytes = 50L * 1024L * 1024L;
        private List<String> allowedTags = new ArrayList<>(List.of(
                "implementation",
                "math",
                "greedy",
                "dp",
                "binary_search",
                "graphs",
                "shortest_paths",
                "trees",
                "strings",
                "data_structures",
                "sorting",
                "sortings",
                "数组",
                "哈希",
                "排序",
                "实现",
                "数学",
                "贪心",
                "动态规划",
                "二分",
                "图论",
                "最短路",
                "树",
                "字符串",
                "数据结构"
        ));

        public int getMaxRepairAttempts() {
            return maxRepairAttempts;
        }

        public void setMaxRepairAttempts(int maxRepairAttempts) {
            this.maxRepairAttempts = Math.max(0, maxRepairAttempts);
        }

        public int getMaxAgentSteps() {
            return maxAgentSteps;
        }

        public void setMaxAgentSteps(int maxAgentSteps) {
            this.maxAgentSteps = Math.max(1, maxAgentSteps);
        }

        public int getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(int maxToolCalls) {
            this.maxToolCalls = Math.max(0, maxToolCalls);
        }

        public int getMaxSandboxCalls() {
            return maxSandboxCalls;
        }

        public void setMaxSandboxCalls(int maxSandboxCalls) {
            this.maxSandboxCalls = Math.max(0, maxSandboxCalls);
        }

        public int getMaxTestcaseGenerations() {
            return maxTestcaseGenerations;
        }

        public void setMaxTestcaseGenerations(int maxTestcaseGenerations) {
            this.maxTestcaseGenerations = Math.max(0, maxTestcaseGenerations);
        }

        public int getMaxTestcasePackageRuns() {
            return maxTestcasePackageRuns;
        }

        public void setMaxTestcasePackageRuns(int maxTestcasePackageRuns) {
            this.maxTestcasePackageRuns = Math.max(0, maxTestcasePackageRuns);
        }

        public int getMaxRunMinutes() {
            return maxRunMinutes;
        }

        public void setMaxRunMinutes(int maxRunMinutes) {
            this.maxRunMinutes = Math.max(1, maxRunMinutes);
        }

        /** 0 derives the generation-job lease from {@code maxRunMinutes} plus a grace margin. */
        public int getJobLeaseMinutes() {
            return jobLeaseMinutes;
        }

        public void setJobLeaseMinutes(int jobLeaseMinutes) {
            this.jobLeaseMinutes = Math.max(0, jobLeaseMinutes);
        }

        public String getTestcaseArtifactStorageRoot() {
            return testcaseArtifactStorageRoot;
        }

        public void setTestcaseArtifactStorageRoot(String testcaseArtifactStorageRoot) {
            this.testcaseArtifactStorageRoot = testcaseArtifactStorageRoot;
        }

        public long getMaxOfficialCaseBytes() {
            return maxOfficialCaseBytes;
        }

        public void setMaxOfficialCaseBytes(long maxOfficialCaseBytes) {
            this.maxOfficialCaseBytes = Math.max(1L, maxOfficialCaseBytes);
        }

        public long getMaxOfficialPackageBytes() {
            return maxOfficialPackageBytes;
        }

        public void setMaxOfficialPackageBytes(long maxOfficialPackageBytes) {
            this.maxOfficialPackageBytes = Math.max(1L, maxOfficialPackageBytes);
        }

        public List<String> getAllowedTags() {
            return allowedTags;
        }

        public void setAllowedTags(List<String> allowedTags) {
            this.allowedTags = allowedTags == null ? new ArrayList<>() : new ArrayList<>(allowedTags);
        }
    }

    public static class Intent {
        private boolean enabled = true;
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private int maxContextChars = 6000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxContextChars() {
            return maxContextChars;
        }

        public void setMaxContextChars(int maxContextChars) {
            this.maxContextChars = maxContextChars;
        }
    }

    public static class Embedding {
        private boolean enabled = true;
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
        private String apiKey = "";
        private String model = "text-embedding-v3";
        private int dimension = 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }
    }

    public static class Capacity {
        private int chatCorePoolSize = 4;
        private int chatMaxPoolSize = 8;
        private int chatQueueCapacity = 64;
        private int studentAssistantConcurrency = 8;
        private int problemDraftConcurrency = 2;
        private int intentMemoryConcurrency = 4;
        private int batchReportConcurrency = 1;
        /** Answers one user may have running at once (T2.2); the chat pool is shared by everyone. */
        private int perUserTurnConcurrency = 2;
        /** Problem-draft jobs one admin may have queued or running (T2.2). */
        private int perUserDraftJobs = 3;
        private int modelCorePoolSize = 2;
        private int modelMaxPoolSize = 8;
        private int modelQueueCapacity = 32;
        private int toolCorePoolSize = 4;
        private int toolMaxPoolSize = 16;
        private int toolQueueCapacity = 64;

        public int getPerUserTurnConcurrency() {
            return perUserTurnConcurrency;
        }

        public void setPerUserTurnConcurrency(int perUserTurnConcurrency) {
            this.perUserTurnConcurrency = perUserTurnConcurrency;
        }

        public int getPerUserDraftJobs() {
            return perUserDraftJobs;
        }

        public void setPerUserDraftJobs(int perUserDraftJobs) {
            this.perUserDraftJobs = perUserDraftJobs;
        }

        public int getChatCorePoolSize() {
            return chatCorePoolSize;
        }

        public void setChatCorePoolSize(int chatCorePoolSize) {
            this.chatCorePoolSize = chatCorePoolSize;
        }

        public int getChatMaxPoolSize() {
            return chatMaxPoolSize;
        }

        public void setChatMaxPoolSize(int chatMaxPoolSize) {
            this.chatMaxPoolSize = chatMaxPoolSize;
        }

        public int getChatQueueCapacity() {
            return chatQueueCapacity;
        }

        public void setChatQueueCapacity(int chatQueueCapacity) {
            this.chatQueueCapacity = chatQueueCapacity;
        }

        public int getStudentAssistantConcurrency() {
            return studentAssistantConcurrency;
        }

        public void setStudentAssistantConcurrency(int studentAssistantConcurrency) {
            this.studentAssistantConcurrency = studentAssistantConcurrency;
        }

        public int getProblemDraftConcurrency() {
            return problemDraftConcurrency;
        }

        public void setProblemDraftConcurrency(int problemDraftConcurrency) {
            this.problemDraftConcurrency = problemDraftConcurrency;
        }

        public int getIntentMemoryConcurrency() {
            return intentMemoryConcurrency;
        }

        public void setIntentMemoryConcurrency(int intentMemoryConcurrency) {
            this.intentMemoryConcurrency = intentMemoryConcurrency;
        }

        public int getBatchReportConcurrency() {
            return batchReportConcurrency;
        }

        public void setBatchReportConcurrency(int batchReportConcurrency) {
            this.batchReportConcurrency = batchReportConcurrency;
        }

        public int getModelCorePoolSize() {
            return modelCorePoolSize;
        }

        public void setModelCorePoolSize(int modelCorePoolSize) {
            this.modelCorePoolSize = modelCorePoolSize;
        }

        public int getModelMaxPoolSize() {
            return modelMaxPoolSize;
        }

        public void setModelMaxPoolSize(int modelMaxPoolSize) {
            this.modelMaxPoolSize = modelMaxPoolSize;
        }

        public int getModelQueueCapacity() {
            return modelQueueCapacity;
        }

        public void setModelQueueCapacity(int modelQueueCapacity) {
            this.modelQueueCapacity = modelQueueCapacity;
        }

        public int getToolCorePoolSize() {
            return toolCorePoolSize;
        }

        public void setToolCorePoolSize(int toolCorePoolSize) {
            this.toolCorePoolSize = toolCorePoolSize;
        }

        public int getToolMaxPoolSize() {
            return toolMaxPoolSize;
        }

        public void setToolMaxPoolSize(int toolMaxPoolSize) {
            this.toolMaxPoolSize = toolMaxPoolSize;
        }

        public int getToolQueueCapacity() {
            return toolQueueCapacity;
        }

        public void setToolQueueCapacity(int toolQueueCapacity) {
            this.toolQueueCapacity = toolQueueCapacity;
        }
    }

    public static class MemoryJobs {
        private boolean enabled = true;
        private long pollIntervalMs = 5000;
        private int batchSize = 8;
        private long leaseSeconds = 60;
        private int maxAttempts = 3;
        private long backoffBaseSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(long leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffBaseSeconds() {
            return backoffBaseSeconds;
        }

        public void setBackoffBaseSeconds(long backoffBaseSeconds) {
            this.backoffBaseSeconds = backoffBaseSeconds;
        }
    }

    public static class Recall {
        private long cooldownWindowMinutes = 30;
        private double cooldownPenalty = 0.35;
        private double confirmedBoost = 0.10;

        public long getCooldownWindowMinutes() {
            return cooldownWindowMinutes;
        }

        public void setCooldownWindowMinutes(long cooldownWindowMinutes) {
            this.cooldownWindowMinutes = Math.max(0, cooldownWindowMinutes);
        }

        public double getCooldownPenalty() {
            return cooldownPenalty;
        }

        public void setCooldownPenalty(double cooldownPenalty) {
            this.cooldownPenalty = Math.max(0, cooldownPenalty);
        }

        public double getConfirmedBoost() {
            return confirmedBoost;
        }

        public void setConfirmedBoost(double confirmedBoost) {
            this.confirmedBoost = Math.max(0, confirmedBoost);
        }
    }
}
