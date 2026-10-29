package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.model.api.EndpointTuning;
import com.aioj.next.ai.persistence.entity.AiModelConfigEntity;
import com.aioj.next.ai.persistence.mapper.AiModelConfigMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiModelCallSettings;
import com.aioj.next.contract.ai.AiModelConfigResponse;
import com.aioj.next.contract.ai.AiModelConfigTestRequest;
import com.aioj.next.contract.ai.AiModelConfigTestResponse;
import com.aioj.next.contract.ai.AiModelConfigUpdateRequest;
import com.aioj.next.contract.ai.AiModelListResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Service
public class AiModelConfigService implements AiModelConfigResolver {
    private static final Logger log = LoggerFactory.getLogger(AiModelConfigService.class);
    private static final long CACHE_TTL_MILLIS = 30_000;
    private static final ZoneId ZONE = ZoneId.systemDefault();
    /** Plan A6.1: the provider is chosen, never guessed; these are the names a scope may store. */
    // Kimi and the plain OpenAI-compatible text option were retired on 2026-10-29; before, text scopes took
    // Set.of("deepseek", "kimi", "openai-compatible"). Rows that still name them stay readable.
    private static final Set<String> TEXT_PROVIDERS = Set.of("deepseek");
    private static final Set<String> RETIRED_TEXT_PROVIDERS = Set.of("kimi", "openai-compatible");
    private static final Set<String> EMBEDDING_PROVIDERS = Set.of("dashscope", "openai-compatible");
    /** Plan Q13: tools only run on verified providers, and these scopes call tools. */
    // Retired with the plain OpenAI-compatible text option on 2026-10-29:
    // private static final Set<AiModelScope> TOOL_SCOPES = Set.of(AiModelScope.TEXT_GENERATION, AiModelScope.PROBLEM_DRAFT);

    private final AiModelConfigMapper mapper;
    private final ModelCompletions completionClient;
    private final ModelCatalogService modelCatalogClient;
    private final AiQuotaService aiQuotaService;
    private final EnvironmentAiModelConfigResolver environmentResolver;
    private final Map<AiModelScope, CacheEntry> cache = new ConcurrentHashMap<>();
    /** Agent Core V3 rule: env takeover of the chat model is WARN-logged once per JVM, never silent. */
    private final AtomicBoolean envFallbackWarned = new AtomicBoolean(false);

    @Autowired
    public AiModelConfigService(
            AiProperties properties,
            AiModelConfigMapper mapper,
            ModelCompletions completionClient,
            ModelCatalogService modelCatalogClient,
            AiQuotaService aiQuotaService
    ) {
        this(properties, mapper, completionClient, modelCatalogClient, aiQuotaService, System::getenv);
    }

    AiModelConfigService(
            AiProperties properties,
            AiModelConfigMapper mapper,
            ModelCompletions completionClient,
            ModelCatalogService modelCatalogClient,
            AiQuotaService aiQuotaService,
            Function<String, String> envLookup
    ) {
        this.mapper = mapper;
        this.completionClient = completionClient;
        this.modelCatalogClient = modelCatalogClient;
        this.aiQuotaService = aiQuotaService;
        this.environmentResolver = new EnvironmentAiModelConfigResolver(properties, envLookup);
    }

    @Override
    public AiModelEffectiveConfig effectiveConfig(AiModelScope scope) {
        AiModelScope normalized = scope == null ? AiModelScope.TEXT_GENERATION : scope;
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(normalized);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.config();
        }
        AiModelEffectiveConfig config = loadEffectiveConfig(normalized);
        if (normalized == AiModelScope.TEXT_GENERATION && "ENVIRONMENT".equals(config.source())
                && envFallbackWarned.compareAndSet(false, true)) {
            log.warn("AI chat model config is resolved from environment (no enabled DATABASE row): provider={} model={} baseUrl={}. "
                    + "Agent Core V3 expects the DB-backed config service as the source of truth; configure it via admin model config.",
                    config.provider(), config.model(), config.baseUrl());
        }
        cache.put(normalized, new CacheEntry(config, now + CACHE_TTL_MILLIS));
        return config;
    }

    public List<AiModelConfigResponse> listConfigs() {
        return Arrays.stream(AiModelScope.values())
                .map(scope -> toResponse(loadDisplayConfig(scope)))
                .toList();
    }

    public AiModelListResponse listModels(String scopeName, String provider, String baseUrl) {
        AiModelScope scope = parseScope(scopeName);
        AiModelEffectiveConfig current = loadDisplayConfig(scope);
        String requested = textOr(provider, current.provider());
        String retired = environmentResolver.canonicalTextProvider(requested);
        if (isTextScope(scope) && RETIRED_TEXT_PROVIDERS.contains(retired)) {
            // A stored row on a retired provider still opens in the settings page; its list says why it is empty.
            return modelCatalogClient.listModels(scope, retired, textOr(baseUrl, current.baseUrl()), null);
        }
        String effectiveProvider = providerForScope(scope, requested);
        String effectiveBaseUrl = normalizeTextBaseUrl(scope, effectiveProvider, textOr(baseUrl, current.baseUrl()));
        EnvironmentAiModelConfigResolver.ApiKeyResolution key = environmentResolver.apiKeyFor(scope, effectiveProvider);
        return modelCatalogClient.listModels(scope, effectiveProvider, effectiveBaseUrl, key);
    }

    @Transactional
    public AiModelConfigResponse updateConfig(String scopeName, AiModelConfigUpdateRequest request, Long updatedBy) {
        AiModelScope scope = parseScope(scopeName);
        AiModelConfigEntity existing = select(scope);
        AiModelEffectiveConfig base = existing != null && Boolean.TRUE.equals(existing.getEnabled())
                ? fromDatabase(scope, existing, false)
                : fallbackFor(scope, existing != null);
        AiModelConfigEntity entity = existing == null ? new AiModelConfigEntity() : existing;
        LocalDateTime now = LocalDateTime.now();
        if (entity.getId() == null) {
            entity.setId(IdWorker.getId());
            entity.setScope(scope.name());
            entity.setCreatedAt(now);
        }
        boolean enabled = scope == AiModelScope.TEXT_GENERATION || request == null || request.enabled() == null
                ? true
                : request.enabled();
        entity.setEnabled(enabled);
        String provider = providerForScope(scope, textOr(request == null ? null : request.provider(), base.provider()));
        String baseUrl = required("baseUrl", normalizeTextBaseUrl(scope, provider, textOr(request == null ? null : request.baseUrl(), base.baseUrl())));
        String model = required("model", textOr(request == null ? null : request.model(), base.model()));
        validateTextModel(scope, provider, model, baseUrl);
        entity.setProvider(provider);
        entity.setBaseUrl(baseUrl);
        entity.setModel(model);
        entity.setJsonOutputEnabled(request == null || request.jsonOutputEnabled() == null ? base.jsonOutputEnabled() : request.jsonOutputEnabled());
        entity.setThinkingEnabled(request == null || request.thinkingEnabled() == null ? base.thinkingEnabled() : request.thinkingEnabled());
        entity.setReasoningEffort(reasoningEffort(request == null ? null : request.reasoningEffort(), base.reasoningEffort()));
        entity.setTemperature(temperature(request == null ? null : request.temperature(), base.temperature()));
        entity.setMaxTokens(maxTokens(request == null ? null : request.maxTokens(), base.maxTokens()));
        entity.setEmbeddingDimension(embeddingDimension(request == null ? null : request.embeddingDimension(), base.embeddingDimension()));
        applyCallSettings(entity, validCallSettings(request == null ? null : request.callSettings()));
        applyKeyAction(entity, request);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(updatedBy);
        if (existing == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        cache.clear();
        return toResponse(loadDisplayConfig(scope));
    }

    public AiModelConfigTestResponse testConfig(String scopeName, AiModelConfigTestRequest request, Long userId) {
        AiModelScope scope = parseScope(scopeName);
        AiModelEffectiveConfig config = temporaryConfig(scope, request);
        long start = System.nanoTime();
        try {
            if (!config.enabled()) {
                aiQuotaService.record(userId, config.provider(), config.model(), 0, 0, false);
                return failure(config, elapsedMillis(start), 0, 0, "AI model configuration is disabled");
            }
            if (!config.hasApiKey()) {
                aiQuotaService.record(userId, config.provider(), config.model(), 0, 0, false);
                return failure(config, elapsedMillis(start), 0, 0, "AI API key is not configured");
            }
            boolean strictJsonTest = scope != AiModelScope.EMBEDDING && config.jsonOutputEnabled();
            String prompt = request == null || request.prompt() == null || request.prompt().isBlank()
                    ? defaultTestPrompt(strictJsonTest)
                    : request.prompt().trim();
            if (scope == AiModelScope.EMBEDDING) {
                // Embeddings endpoints reject chat completion payloads; probe with a real
                // embeddings request instead.
                ModelCompletions.EmbeddingProbe probe = completionClient.embedProbe(config, prompt);
                long elapsed = elapsedMillis(start);
                aiQuotaService.record(userId, config.provider(), config.model(), probe.promptTokens(), 0, true);
                return new AiModelConfigTestResponse(true, config.provider(), config.model(), elapsed,
                        probe.promptTokens(), 0, "Embedding OK, vector dimension " + probe.dimension(), null);
            }
            ModelCompletions.Result result = completionClient.complete(
                    config,
                    List.of(
                            message("system", strictJsonTest ? "Return a strict JSON object only." : "Reply briefly for an AI model connectivity test."),
                            message("user", prompt)
                    ),
                    config.temperatureOr(strictJsonTest ? 0.1 : 0.2),
                    config.maxTokensOr(512),
                    strictJsonTest
            );
            long elapsed = elapsedMillis(start);
            aiQuotaService.record(userId, result.provider(), result.model(), result.promptTokens(), result.completionTokens(), true);
            return new AiModelConfigTestResponse(true, result.provider(), result.model(), elapsed,
                    result.promptTokens(), result.completionTokens(), preview(result.content(), 500), null);
        } catch (RuntimeException ex) {
            long elapsed = elapsedMillis(start);
            aiQuotaService.record(userId, config.provider(), config.model(), 0, 0, false);
            return failure(config, elapsed, 0, 0, sanitizeError(ex.getMessage(), config.apiKey()));
        }
    }

    private AiModelEffectiveConfig temporaryConfig(AiModelScope scope, AiModelConfigTestRequest request) {
        AiModelEffectiveConfig current = loadDisplayConfig(scope);
        if (request == null) {
            return current;
        }
        if (scope != AiModelScope.TEXT_GENERATION && scope != AiModelScope.EMBEDDING && Boolean.FALSE.equals(request.enabled())) {
            return effectiveConfig(AiModelScope.TEXT_GENERATION).withScope(scope, true, "TEXT_GENERATION");
        }
        String action = keyAction(request.apiKeyAction());
        if ("REPLACE".equals(action)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "API Key must be configured through server environment variables");
        }
        String provider = providerForScope(scope, textOr(request.provider(), current.provider()));
        String baseUrl = normalizeTextBaseUrl(scope, provider, textOr(request.baseUrl(), current.baseUrl()));
        String model = textOr(request.model(), current.model());
        validateTextModel(scope, provider, model, baseUrl);
        EnvironmentAiModelConfigResolver.ApiKeyResolution key = environmentResolver.apiKeyFor(scope, provider);
        if ("CLEAR".equals(action)) {
            key = environmentResolver.apiKeyFor(scope, provider);
        }
        return current.withOverrides(
                request.enabled(),
                provider,
                baseUrl,
                key.apiKey(),
                key.apiKeyPreview(),
                key.apiKeySource(),
                key.apiKeyEnvName(),
                model,
                request.jsonOutputEnabled(),
                request.thinkingEnabled(),
                reasoningEffort(request.reasoningEffort(), current.reasoningEffort()),
                temperature(request.temperature(), current.temperature()),
                maxTokens(request.maxTokens(), current.maxTokens()),
                embeddingDimension(request.embeddingDimension(), current.embeddingDimension())
        ).withTuning(request.callSettings() == null ? current.tuning()
                : tuning(validCallSettings(request.callSettings())));
    }

    private AiModelEffectiveConfig loadEffectiveConfig(AiModelScope scope) {
        AiModelConfigEntity entity = select(scope);
        if (entity != null && Boolean.TRUE.equals(entity.getEnabled())) {
            return fromDatabase(scope, entity, true);
        }
        return fallbackFor(scope, entity != null);
    }

    private AiModelEffectiveConfig loadDisplayConfig(AiModelScope scope) {
        AiModelConfigEntity entity = select(scope);
        if (entity != null && Boolean.TRUE.equals(entity.getEnabled())) {
            return fromDatabase(scope, entity, false);
        }
        return fallbackFor(scope, entity != null);
    }

    private AiModelEffectiveConfig fallbackFor(AiModelScope scope, boolean adminConfigured) {
        return switch (scope) {
            case TEXT_GENERATION -> environmentResolver.effectiveConfig(AiModelScope.TEXT_GENERATION);
            case MEMORY_EXTRACTION, REPORT_ANALYSIS, PROBLEM_DRAFT, ACCOUNT_IMPORT_PARSE, AGENT_CURATOR ->
                    effectiveConfig(AiModelScope.TEXT_GENERATION).withScope(scope, true, "TEXT_GENERATION");
            case INTENT -> adminConfigured
                    ? effectiveConfig(AiModelScope.TEXT_GENERATION).withScope(scope, true, "TEXT_GENERATION")
                    : environmentResolver.effectiveConfig(AiModelScope.INTENT);
            case EMBEDDING -> environmentResolver.effectiveConfig(AiModelScope.EMBEDDING);
        };
    }

    private AiModelEffectiveConfig fromDatabase(AiModelScope scope, AiModelConfigEntity entity, boolean fallbackWhenMissingKey) {
        AiModelEffectiveConfig fallback = fallbackFor(scope, true);
        String provider = storedProviderForScope(scope, entity.getProvider());
        if (provider.isBlank()) {
            return fallbackFor(scope, true);
        }
        String baseUrl = normalizeTextBaseUrl(scope, provider, textOr(entity.getBaseUrl(), fallback.baseUrl()));
        String model = textOr(entity.getModel(), fallback.model());
        if (isTextScope(scope) && invalidTextModel(provider, model, baseUrl)) {
            return fallbackFor(scope, true);
        }
        EnvironmentAiModelConfigResolver.ApiKeyResolution key = environmentResolver.apiKeyFor(scope, provider);
        if (fallbackWhenMissingKey && !key.hasApiKey() && shouldFallbackToDefaultText(scope)) {
            return fallbackFor(scope, true);
        }
        return new AiModelEffectiveConfig(
                scope,
                true,
                false,
                "DATABASE",
                provider,
                baseUrl,
                key.apiKey(),
                key.apiKeyPreview(),
                key.apiKeySource(),
                key.apiKeyEnvName(),
                model,
                entity.getJsonOutputEnabled() == null ? fallback.jsonOutputEnabled() : entity.getJsonOutputEnabled(),
                entity.getThinkingEnabled() == null ? fallback.thinkingEnabled() : entity.getThinkingEnabled(),
                reasoningEffort(entity.getReasoningEffort(), fallback.reasoningEffort()),
                temperature(entity.getTemperature(), fallback.temperature()),
                maxTokens(entity.getMaxTokens(), fallback.maxTokens()),
                embeddingDimension(entity.getEmbeddingDimension(), fallback.embeddingDimension()),
                toInstant(entity.getUpdatedAt()),
                entity.getUpdatedBy(),
                tuning(storedCallSettings(entity))
        );
    }

    private boolean shouldFallbackToDefaultText(AiModelScope scope) {
        return scope == AiModelScope.MEMORY_EXTRACTION
                || scope == AiModelScope.REPORT_ANALYSIS
                || scope == AiModelScope.PROBLEM_DRAFT
                || scope == AiModelScope.ACCOUNT_IMPORT_PARSE
                || scope == AiModelScope.AGENT_CURATOR
                || scope == AiModelScope.INTENT;
    }

    /**
     * V73 turns off the stored text configurations that name a retired provider; one that is still on
     * (switched back by hand, or a database V73 has not reached) is reported, not refused. No key is read
     * for it, so a scene override falls back to the default text configuration, and a default text
     * configuration on it fails its calls with the model client's "retired" configuration error.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warnAboutRetiredProviders() {
        try {
            for (AiModelConfigEntity row : mapper.selectList(new QueryWrapper<AiModelConfigEntity>()
                    .eq("enabled", true)
                    .ne("scope", AiModelScope.EMBEDDING.name()))) {
                if (RETIRED_TEXT_PROVIDERS.contains(environmentResolver.canonicalTextProvider(row.getProvider()))) {
                    log.warn("AI model config {} is still enabled on provider {}, which was retired on 2026-10-29; {}",
                            row.getScope(), row.getProvider(), AiModelScope.TEXT_GENERATION.name().equals(row.getScope())
                                    ? "its calls fail until it is switched to deepseek"
                                    : "the scope falls back to the default text configuration");
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Could not check the AI model configs for retired providers: {}", ex.toString());
        }
    }

    /** Plan A6.1: an unknown provider is refused; the old aliases are stored by their real names. */
    private String providerForScope(AiModelScope scope, String provider) {
        String name = required("provider", provider).toLowerCase(Locale.ROOT);
        if (!isTextScope(scope)) {
            String canonical = switch (name) {
                case "qwen", "alibaba" -> "dashscope";
                case "openai", "custom" -> "openai-compatible";
                default -> name;
            };
            if (!EMBEDDING_PROVIDERS.contains(canonical)) {
                throw new DomainException(ErrorCode.BAD_REQUEST,
                        "Unknown embedding AI provider " + name + "; choose dashscope or openai-compatible");
            }
            return canonical;
        }
        String canonical = environmentResolver.canonicalTextProvider(name);
        if (RETIRED_TEXT_PROVIDERS.contains(canonical)) {
            throw new DomainException(ErrorCode.BAD_REQUEST,
                    "Text AI provider " + canonical + " was retired on 2026-10-29; choose deepseek");
        }
        if (!TEXT_PROVIDERS.contains(canonical)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unknown text AI provider " + name + "; choose deepseek");
        }
        // Retired with the plain OpenAI-compatible text option on 2026-10-29:
        // if ("openai-compatible".equals(canonical) && TOOL_SCOPES.contains(scope)) {
        //     throw new DomainException(ErrorCode.BAD_REQUEST, scope.name()
        //             + " calls tools, which run only on deepseek or kimi; openai-compatible suits the other text scopes");
        // }
        return canonical;
    }

    private String storedProviderForScope(AiModelScope scope, String provider) {
        if (!isTextScope(scope)) {
            return textOr(provider, "");
        }
        return environmentResolver.canonicalTextProvider(provider);
    }

    private boolean isTextScope(AiModelScope scope) {
        return scope != AiModelScope.EMBEDDING;
    }

    private void validateTextModel(AiModelScope scope, String provider, String model, String baseUrl) {
        if (!isTextScope(scope) || !invalidTextModel(provider, model, baseUrl)) {
            return;
        }
        throw new DomainException(ErrorCode.BAD_REQUEST, "Text AI API and model/base URL do not match");
    }

    private boolean invalidTextModel(String provider, String model, String baseUrl) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        String normalizedModel = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.trim().toLowerCase(Locale.ROOT);
        if ("deepseek".equals(normalizedProvider)) {
            return normalizedModel.startsWith("kimi-")
                    || normalizedModel.startsWith("moonshot-")
                    || normalizedBaseUrl.contains("moonshot")
                    || normalizedBaseUrl.contains("kimi");
        }
        // Kimi, retired on 2026-10-29:
        // if ("kimi".equals(normalizedProvider)) {
        //     return normalizedModel.startsWith("deepseek-")
        //             || normalizedBaseUrl.contains("deepseek");
        // }
        // A stored row on a retired provider is read as it is (saving one is refused); a call made with it fails
        // as retired in the model client.
        return !RETIRED_TEXT_PROVIDERS.contains(normalizedProvider);
    }

    private String normalizeTextBaseUrl(AiModelScope scope, String provider, String baseUrl) {
        String current = textOr(baseUrl, "");
        // Kimi, retired on 2026-10-29:
        // if (isTextScope(scope) && "kimi".equals(provider)) {
        //     String normalized = current.toLowerCase(Locale.ROOT);
        //     if (normalized.isBlank() || normalized.contains("api.moonshot.ai")) {
        //         return "https://api.moonshot.cn/v1/chat/completions";
        //     }
        // }
        return current;
    }

    private AiModelConfigEntity select(AiModelScope scope) {
        return mapper.selectOne(new QueryWrapper<AiModelConfigEntity>()
                .eq("scope", scope.name())
                .last("LIMIT 1"));
    }

    private void applyKeyAction(AiModelConfigEntity entity, AiModelConfigUpdateRequest request) {
        String action = keyAction(request == null ? null : request.apiKeyAction());
        if ("REPLACE".equals(action)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "API Key must be configured through server environment variables");
        }
        entity.setApiKeyEncrypted(null);
        entity.setApiKeyPreview(null);
    }

    private AiModelConfigResponse toResponse(AiModelEffectiveConfig config) {
        return new AiModelConfigResponse(
                config.scope().name(),
                config.enabled(),
                config.inherited(),
                config.source(),
                config.provider(),
                config.baseUrl(),
                config.model(),
                config.jsonOutputEnabled(),
                config.thinkingEnabled(),
                config.reasoningEffort(),
                config.temperature(),
                config.maxTokens(),
                config.embeddingDimension(),
                config.hasApiKey(),
                config.apiKeyPreview() == null ? "" : config.apiKeyPreview(),
                config.apiKeySource(),
                config.apiKeyEnvName(),
                config.updatedAt(),
                config.updatedBy(),
                callSettings(config.tuning())
        );
    }

    private AiModelScope parseScope(String scopeName) {
        try {
            return AiModelScope.from(scopeName);
        } catch (IllegalArgumentException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
    }

    private String keyAction(String value) {
        if (value == null || value.isBlank()) {
            return "KEEP";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("KEEP", "REPLACE", "CLEAR").contains(normalized)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported apiKeyAction");
        }
        return normalized;
    }

    private String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** DeepSeek takes low, high and max (2026-10-29); anything else is its default, high. */
    private String reasoningEffort(String value, String fallback) {
        String normalized = textOr(value, fallback == null ? "high" : fallback).toLowerCase(Locale.ROOT);
        return "low".equals(normalized) || "max".equals(normalized) ? normalized : "high";
    }

    private Double temperature(Double value, Double fallback) {
        Double next = value == null ? fallback : value;
        if (next != null && (next < 0 || next > 2)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "temperature must be between 0 and 2");
        }
        return next;
    }

    private Integer maxTokens(Integer value, Integer fallback) {
        Integer next = value == null ? fallback : value;
        if (next != null && next <= 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "maxTokens must be positive");
        }
        return next;
    }

    private Integer embeddingDimension(Integer value, Integer fallback) {
        Integer next = value == null ? fallback : value;
        if (next != null && next <= 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "embeddingDimension must be positive");
        }
        return next;
    }

    /** Plan A6.2: each value is either left to the default (null) or within a sane range. */
    private AiModelCallSettings validCallSettings(AiModelCallSettings settings) {
        if (settings == null) {
            return null;
        }
        within("connectTimeoutMs", settings.connectTimeoutMs(), 100, 120_000);
        within("firstByteTimeoutMs", settings.firstByteTimeoutMs(), 1_000, 600_000);
        within("totalTimeoutMs", settings.totalTimeoutMs(), 1_000, 1_800_000);
        within("maxConcurrency", settings.maxConcurrency(), 1, 64);
        within("maxRetries", settings.maxRetries(), 0, 5);
        return settings;
    }

    private void within(String field, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, field + " must be between " + min + " and " + max);
        }
    }

    /** Saving replaces every call setting, so clearing a field puts the default back. */
    private void applyCallSettings(AiModelConfigEntity entity, AiModelCallSettings settings) {
        AiModelCallSettings value = settings == null
                ? new AiModelCallSettings(null, null, null, null, null, null, null) : settings;
        entity.setConnectTimeoutMs(value.connectTimeoutMs());
        entity.setFirstByteTimeoutMs(value.firstByteTimeoutMs());
        entity.setTotalTimeoutMs(value.totalTimeoutMs());
        entity.setMaxConcurrency(value.maxConcurrency());
        entity.setMaxRetries(value.maxRetries());
        entity.setThinkingFallbackEnabled(value.thinkingFallbackEnabled());
        entity.setStreamingEnabled(value.streamingEnabled());
    }

    private AiModelCallSettings storedCallSettings(AiModelConfigEntity entity) {
        return new AiModelCallSettings(entity.getConnectTimeoutMs(), entity.getFirstByteTimeoutMs(),
                entity.getTotalTimeoutMs(), entity.getMaxConcurrency(), entity.getMaxRetries(),
                entity.getThinkingFallbackEnabled(), entity.getStreamingEnabled());
    }

    private static EndpointTuning tuning(AiModelCallSettings settings) {
        if (settings == null) {
            return EndpointTuning.DEFAULTS;
        }
        return new EndpointTuning(millis(settings.connectTimeoutMs()), millis(settings.firstByteTimeoutMs()),
                millis(settings.totalTimeoutMs()), settings.maxConcurrency(), settings.maxRetries(),
                settings.thinkingFallbackEnabled(), settings.streamingEnabled());
    }

    private static AiModelCallSettings callSettings(EndpointTuning tuning) {
        return new AiModelCallSettings(millis(tuning.connectTimeout()), millis(tuning.firstByteTimeout()),
                millis(tuning.totalTimeout()), tuning.maxConcurrency(), tuning.maxRetries(),
                tuning.thinkingFallback(), tuning.streaming());
    }

    private static Duration millis(Integer value) {
        return value == null ? null : Duration.ofMillis(value);
    }

    private static Integer millis(Duration value) {
        return value == null ? null : (int) value.toMillis();
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private String defaultTestPrompt(boolean strictJsonTest) {
        return strictJsonTest ? "Return {\"ok\":true,\"source\":\"ai-model-config-test\"}." : "Reply with a short connectivity confirmation.";
    }

    private AiModelConfigTestResponse failure(AiModelEffectiveConfig config, long latencyMillis,
                                              long promptTokens, long completionTokens, String errorMessage) {
        return new AiModelConfigTestResponse(false, config.provider(), config.model(), latencyMillis,
                promptTokens, completionTokens, null, preview(errorMessage, 300));
    }

    private long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private String preview(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String sanitizeError(String message, String apiKey) {
        String sanitized = message == null || message.isBlank() ? "AI provider test failed" : message;
        if (apiKey != null && !apiKey.isBlank()) {
            sanitized = sanitized.replace(apiKey, "***");
        }
        sanitized = sanitized.replaceAll("(?i)(api[_-]?key|token|secret|password)\\s*(?:=|:|是|为)\\s*[^\\s,;，。]+", "$1=***");
        sanitized = sanitized.replaceAll("(?i)(your\\s+api\\s+key\\s*:\\s*)\\*+[^\\s\\\"',;，。}]+", "$1***");
        sanitized = sanitized.replaceAll("(?i)(api\\s+key\\s*:\\s*)\\*+[^\\s\\\"',;，。}]+", "$1***");
        return preview(sanitized, 300);
    }

    private record CacheEntry(AiModelEffectiveConfig config, long expiresAtMillis) {
    }
}
