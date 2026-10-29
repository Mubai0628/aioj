package com.aioj.next.ai.model.api;

import java.util.Locale;

/**
 * Providers the model call package can talk to. The provider comes from the
 * configuration, never from guessing at model names or URLs; an unknown name is
 * a configuration error (plan A1.3).
 */
public enum ProviderId {
    DEEPSEEK("deepseek"),
    /**
     * Kimi (Moonshot), retired on 2026-10-29: its adapter and settings are commented out and nothing
     * calls it. The name stays so that stored configurations, call records and usage that name it
     * still parse.
     */
    KIMI("kimi"),
    /** Alibaba Cloud Model Studio, used for OpenAI-compatible embeddings. */
    DASHSCOPE("dashscope"),
    /** Any other OpenAI-compatible endpoint; the admin settings offer it for embeddings only. */
    OPENAI_COMPATIBLE("openai-compatible");

    private final String configName;

    ProviderId(String configName) {
        this.configName = configName;
    }

    /** The canonical name stored in ai_model_configs and usage records. */
    public String configName() {
        return configName;
    }

    /** Accepts the canonical names and the aliases the admin settings have always accepted. */
    public static ProviderId fromConfig(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "deepseek" -> DEEPSEEK;
            case "kimi", "moonshot" -> KIMI;
            case "dashscope", "qwen", "alibaba" -> DASHSCOPE;
            case "openai-compatible", "openai", "custom" -> OPENAI_COMPATIBLE;
            default -> throw new ModelException(ModelErrorKind.CONFIGURATION_INVALID, null, null,
                    "Unknown AI provider: " + (normalized.isEmpty() ? "(blank)" : normalized));
        };
    }

    /** A retired provider is kept for historical data only; a call that names it is a configuration error. */
    public boolean retired() {
        return this == KIMI;
    }
}
