package com.aioj.next.problem.domain.search;

import com.aioj.next.contract.problem.Difficulty;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Helpers shared by indexing and querying: search text, its hash, and the stored vector format. */
final class SearchVectors {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private SearchVectors() {
    }

    /** A problem's tags column (a JSON array); unreadable values count as no tags. */
    static List<String> tags(ObjectMapper objectMapper, String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> tags = objectMapper.readValue(json, STRING_LIST);
            return tags == null ? List.of() : tags.stream().filter(StringUtils::hasText).map(String::strip).toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /**
     * What gets embedded for a problem: title, difficulty and tags first, then the
     * statement and notes, cut to the input budget so the model limit is never hit.
     */
    static String searchText(String title, Difficulty difficulty, List<String> tags, String statement, String notes,
                             int maxChars) {
        StringBuilder text = new StringBuilder();
        append(text, "标题", title);
        append(text, "难度", difficulty == null ? null : difficulty.name());
        append(text, "标签", String.join("、", tags));
        append(text, "题面", statement);
        append(text, "补充说明", notes);
        return cut(text.toString(), maxChars);
    }

    static String compactWhitespace(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    /** Cuts at a code point boundary, so a surrogate pair is never split. */
    static String cut(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        return value.substring(0, end);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length % Float.BYTES != 0) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }

    /** Cosine similarity with precomputed norms; 0 when the dimensions differ or a norm is 0. */
    static double cosine(float[] left, double leftNorm, float[] right, double rightNorm) {
        if (left.length != right.length || leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
        }
        return dot / (leftNorm * rightNorm);
    }

    private static void append(StringBuilder text, String label, String value) {
        String compact = compactWhitespace(value);
        if (!compact.isEmpty()) {
            text.append(label).append("：").append(compact).append('\n');
        }
    }
}
