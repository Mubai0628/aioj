package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelUsage;
import com.aioj.next.ai.model.api.ProviderId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared parsing and error mapping of the OpenAI-compatible dialects. A provider
 * body is only ever read here, to classify; it never reaches an exception message
 * (appendix G.4 item 3).
 */
public final class ProviderErrors {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Provider error codes are short identifiers; anything else is not kept. */
    private static final Pattern ERROR_TYPE = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    private ProviderErrors() {
    }

    /** The provider's {@code error.type}, or {@code error.code}, when it is a short identifier. */
    static String errorType(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode error = JSON.readTree(body).path("error");
            for (String field : new String[]{"type", "code"}) {
                JsonNode value = error.path(field);
                String text = value.isString() ? value.asString("") : value.isNumber() ? value.asString("") : "";
                if (ERROR_TYPE.matcher(text).matches()) {
                    return text;
                }
            }
        } catch (RuntimeException notJson) {
            // An HTML error page (Kimi's 504) has no error type.
        }
        return null;
    }

    /** Case-insensitive search in a provider body, for classification only. */
    static boolean mentions(String body, String... needles) {
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (normalized.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** The default mapping of a non-2xx answer to a typed failure. */
    public static ModelException httpFailure(ProviderId provider, String model, int status, String errorType,
                                             Duration retryAfter) {
        String name = provider.configName();
        ModelErrorKind kind;
        String message;
        if (status == 400 || status == 422) {
            kind = ModelErrorKind.INVALID_REQUEST;
            message = "AI provider " + name + " rejected the request";
        } else if (status == 401 || status == 403) {
            kind = ModelErrorKind.AUTHENTICATION;
            message = "AI provider " + name + " rejected the API key";
        } else if (status == 402) {
            kind = ModelErrorKind.QUOTA_EXHAUSTED;
            message = "AI provider " + name + " account balance or quota is exhausted";
        } else if (status == 404) {
            kind = ModelErrorKind.CONFIGURATION_INVALID;
            message = "AI provider " + name + " endpoint or model was not found";
        } else if (status == 429) {
            kind = ModelErrorKind.RATE_LIMITED;
            message = "AI provider " + name + " is rate limiting requests";
        } else if (status == 408 || status >= 500) {
            kind = ModelErrorKind.UNAVAILABLE;
            message = "AI provider " + name + " is unavailable";
        } else {
            kind = ModelErrorKind.INVALID_RESPONSE;
            message = "AI provider " + name + " answered with an unexpected status";
        }
        return new ModelException(kind, provider, model, message + " (HTTP " + status + ")", status, errorType,
                retryAfter, false);
    }

    static ModelException invalidResponse(ProviderId provider, String model) {
        return new ModelException(ModelErrorKind.INVALID_RESPONSE, provider, model,
                "AI provider " + provider.configName() + " response could not be parsed");
    }

    static JsonNode readJson(String body, ProviderId provider, String model) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) {
                throw invalidResponse(provider, model);
            }
            return root;
        } catch (ModelException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw invalidResponse(provider, model);
        }
    }

    /**
     * Token usage. Cache hits are {@code prompt_cache_hit_tokens} on DeepSeek and
     * {@code cached_tokens} on Kimi, at the top level or in {@code prompt_tokens_details}.
     */
    static ModelUsage usage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return ModelUsage.NONE;
        }
        boolean reported = usage.has("prompt_tokens") || usage.has("completion_tokens") || usage.has("total_tokens");
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        long cacheHits = usage.path("prompt_cache_hit_tokens").asLong(0);
        if (cacheHits <= 0) {
            cacheHits = usage.path("cached_tokens").asLong(0);
        }
        if (cacheHits <= 0) {
            cacheHits = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        }
        return new ModelUsage(prompt, completion, cacheHits, reported);
    }

    /** A string, or the text parts of an array, as some providers send content. */
    static String text(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isString()) {
            return content.asString("");
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode value = part.path("text");
                if (value.isString()) {
                    text.append(value.asString(""));
                }
            }
            return text.toString();
        }
        return "";
    }

    static String stringOrNull(JsonNode node) {
        return node != null && node.isString() ? node.asString("") : null;
    }

    /** An http or https URL from the configuration; anything else is a configuration error. */
    static URI endpointUri(String url, ProviderId provider, String model) {
        String trimmed = url == null ? "" : url.trim();
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
                throw new IllegalArgumentException("not an http URL");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new ModelException(ModelErrorKind.CONFIGURATION_INVALID, provider, model,
                    "AI provider endpoint is not a valid http(s) URL");
        }
    }

    /** {@code scheme://host[:port]} of a configured URL. */
    static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }
}
