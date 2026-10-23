package com.aioj.next.ai.model.api;

/**
 * One resolved model endpoint: a plain value, filled by the {@link ModelConfigSource}
 * from ai_model_configs and the API key in the server environment (plan §3.2.3).
 *
 * @param baseUrl for chat, the full chat-completions URL as ai_model_configs stores it;
 *                for embeddings, the endpoint or its compatible-mode root
 * @param temperature {@code null} means the call profile's default
 * @param maxTokens {@code null} or non-positive means the call profile's default
 * @param tuning the scope's own timeouts, retries, concurrency and fallback switch
 */
public record ModelEndpointConfig(
        ProviderId provider,
        String baseUrl,
        String apiKey,
        String model,
        boolean enabled,
        boolean jsonOutputEnabled,
        boolean thinkingEnabled,
        String reasoningEffort,
        Double temperature,
        Integer maxTokens,
        Integer embeddingDimension,
        EndpointTuning tuning
) {
    public ModelEndpointConfig {
        tuning = tuning == null ? EndpointTuning.DEFAULTS : tuning;
    }

    public ModelEndpointConfig(ProviderId provider, String baseUrl, String apiKey, String model, boolean enabled,
                               boolean jsonOutputEnabled, boolean thinkingEnabled, String reasoningEffort,
                               Double temperature, Integer maxTokens, Integer embeddingDimension) {
        this(provider, baseUrl, apiKey, model, enabled, jsonOutputEnabled, thinkingEnabled, reasoningEffort,
                temperature, maxTokens, embeddingDimension, EndpointTuning.DEFAULTS);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public ModelEndpointConfig withThinking(boolean thinking) {
        return new ModelEndpointConfig(provider, baseUrl, apiKey, model, enabled, jsonOutputEnabled, thinking,
                reasoningEffort, temperature, maxTokens, embeddingDimension, tuning);
    }

    /** Never prints the API key. */
    @Override
    public String toString() {
        return "ModelEndpointConfig[provider=" + provider + ", baseUrl=" + baseUrl + ", model=" + model
                + ", enabled=" + enabled + ", apiKey=" + (hasApiKey() ? "***" : "(none)")
                + ", thinking=" + thinkingEnabled + "]";
    }

    public static Builder builder(ProviderId provider) {
        return new Builder(provider);
    }

    public static final class Builder {
        private final ProviderId provider;
        private String baseUrl;
        private String apiKey;
        private String model;
        private boolean enabled = true;
        private boolean jsonOutputEnabled = true;
        private boolean thinkingEnabled;
        private String reasoningEffort;
        private Double temperature;
        private Integer maxTokens;
        private Integer embeddingDimension;
        private EndpointTuning tuning = EndpointTuning.DEFAULTS;

        private Builder(ProviderId provider) {
            this.provider = provider;
        }

        public Builder baseUrl(String value) {
            this.baseUrl = value;
            return this;
        }

        public Builder apiKey(String value) {
            this.apiKey = value;
            return this;
        }

        public Builder model(String value) {
            this.model = value;
            return this;
        }

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder jsonOutputEnabled(boolean value) {
            this.jsonOutputEnabled = value;
            return this;
        }

        public Builder thinking(boolean enabled, String effort) {
            this.thinkingEnabled = enabled;
            this.reasoningEffort = effort;
            return this;
        }

        public Builder temperature(Double value) {
            this.temperature = value;
            return this;
        }

        public Builder maxTokens(Integer value) {
            this.maxTokens = value;
            return this;
        }

        public Builder embeddingDimension(Integer value) {
            this.embeddingDimension = value;
            return this;
        }

        public Builder tuning(EndpointTuning value) {
            this.tuning = value;
            return this;
        }

        public ModelEndpointConfig build() {
            return new ModelEndpointConfig(provider, baseUrl, apiKey, model, enabled, jsonOutputEnabled,
                    thinkingEnabled, reasoningEffort, temperature, maxTokens, embeddingDimension, tuning);
        }
    }
}
