package com.aioj.next.problem.domain.recommendation;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Maps problem tags and learning-profile keys onto shared knowledge topics, so
 * "二分查找", "binary_search" and the profile key "wrong_answer_lower_bound" all
 * land on binary_search. Keys are normalized the way ai-service normalizes
 * knowledge nodes; synonyms live in {@value #ALIASES}.
 */
@Component
public class KnowledgeTopics {
    static final String ALIASES = "recommendation/knowledge-aliases.properties";
    private static final int MAX_KEY_LENGTH = 100;
    /** Profile keys that start with a judge status describe a symptom first, e.g. wrong_answer_debugging. */
    private static final List<String> STATUS_PREFIXES = List.of("wrong_answer", "runtime_error", "time_limit_exceeded",
            "memory_limit_exceeded", "output_limit_exceeded", "compile_error");

    private final Map<String, String> aliases;
    /** Longest first, so a key extending two aliases always takes the more specific one. */
    private final List<String> aliasesByLength;

    public KnowledgeTopics() {
        this(loadAliases());
    }

    KnowledgeTopics(Map<String, String> aliases) {
        this.aliases = Map.copyOf(aliases);
        this.aliasesByLength = aliases.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /** Same rule as ai-service's KnowledgeNodeNormalizer: lowercase snake_case, CJK kept, at most 100 chars. */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.length() <= MAX_KEY_LENGTH ? normalized : normalized.substring(0, MAX_KEY_LENGTH);
    }

    /** A known synonym, a known topic the tag extends ("binary_search_boundary"), or the tag itself. */
    public String tagTopic(String tag) {
        String normalized = normalize(tag);
        String known = knownTopic(normalized);
        return known.isEmpty() ? normalized : known;
    }

    /**
     * The topic of a learning-profile key. A key that starts with a judge status
     * counts only when the rest is a known topic, so generic keys such as
     * wrong_answer_debugging never match a tag by accident.
     */
    public String profileTopic(String profileKey) {
        String normalized = normalize(profileKey);
        if (normalized.startsWith("status_")) {
            normalized = normalized.substring("status_".length());
        }
        for (String status : STATUS_PREFIXES) {
            if (normalized.equals(status)) {
                return "";
            }
            if (normalized.startsWith(status + "_")) {
                return knownTopic(normalized.substring(status.length() + 1));
            }
        }
        return tagTopic(normalized);
    }

    /** The topics of a problem's tags, each with the first tag that named it. */
    public Map<String, String> topicsOf(List<String> tags) {
        Map<String, String> topics = new LinkedHashMap<>();
        for (String tag : tags) {
            String topic = tagTopic(tag);
            if (!topic.isEmpty()) {
                topics.putIfAbsent(topic, tag.trim());
            }
        }
        return topics;
    }

    private String knownTopic(String normalized) {
        if (normalized.isEmpty()) {
            return "";
        }
        String exact = aliases.get(normalized);
        if (exact != null) {
            return exact;
        }
        for (String alias : aliasesByLength) {
            if (normalized.startsWith(alias + "_")) {
                return aliases.get(alias);
            }
        }
        return "";
    }

    private static Map<String, String> loadAliases() {
        Properties properties = new Properties();
        try (InputStream in = KnowledgeTopics.class.getClassLoader().getResourceAsStream(ALIASES)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + ALIASES);
            }
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        Map<String, String> aliases = new HashMap<>();
        properties.forEach((alias, topic) -> {
            String key = normalize(String.valueOf(alias));
            String value = normalize(String.valueOf(topic));
            if (!key.isEmpty() && !value.isEmpty()) {
                aliases.put(key, value);
            }
        });
        return aliases;
    }
}
