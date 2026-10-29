package com.aioj.next.ai.providercontract;

import java.util.List;

/**
 * Provider responses in the shapes the official docs give (checked 2026-10-23):
 * DeepSeek api-docs.deepseek.com (create-chat-completion, thinking_mode,
 * error_codes) and Kimi platform.kimi.com (api/chat, api/errors,
 * guide/use-thinking-models, guide/kimi-k3-quickstart). Kimi was retired on
 * 2026-10-29; its samples are kept commented out below.
 *
 * <p>The two differ where it matters for parsing: DeepSeek reports cache hits
 * as {@code prompt_cache_hit_tokens} (and in {@code prompt_tokens_details}) and
 * puts the stream's usage on the chunk that carries {@code finish_reason};
 * Kimi reports {@code cached_tokens} (top level and in
 * {@code prompt_tokens_details}) and sends usage in an extra chunk whose
 * {@code choices} is empty.
 */
final class ProviderPayloads {
    /** Planted in error bodies; must never reach an exception message or a log line. */
    static final String BODY_SENTINEL = "provider-body-7f3a";

    static final String READ_ARGS = "{\"id\":7}";
    static final String SEARCH_ARGS = "{\"query\":\"dp\",\"limit\":3}";

    private ProviderPayloads() {
    }

    static String deepSeekText(String content, String reasoning) {
        return """
                {"id":"ds-1","object":"chat.completion","created":1761200000,"model":"deepseek-v4-pro",
                 "system_fingerprint":"fp_test",
                 "choices":[{"index":0,"message":{"role":"assistant","content":%s,"reasoning_content":%s},
                   "logprobs":null,"finish_reason":"stop"}],
                 "usage":%s}
                """.formatted(quote(content), quote(reasoning), deepSeekUsage());
    }

    /** Two parallel tool calls, {@code content: null}, as DeepSeek returns them in thinking mode. */
    static String deepSeekToolCalls(String reasoning) {
        return """
                {"id":"ds-2","object":"chat.completion","created":1761200001,"model":"deepseek-v4-pro",
                 "system_fingerprint":"fp_test",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,"reasoning_content":%s,
                   "tool_calls":%s},"logprobs":null,"finish_reason":"tool_calls"}],
                 "usage":%s}
                """.formatted(quote(reasoning), toolCalls(), deepSeekUsage());
    }

    static String deepSeekFinishReason(String finishReason) {
        return """
                {"id":"ds-3","object":"chat.completion","created":1761200002,"model":"deepseek-v4-pro",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"partial"},"finish_reason":"%s"}],
                 "usage":%s}
                """.formatted(finishReason, deepSeekUsage());
    }

    static String deepSeekUsage() {
        return """
                {"prompt_tokens":1200,"completion_tokens":80,"total_tokens":1280,
                 "prompt_tokens_details":{"cached_tokens":1024},
                 "prompt_cache_hit_tokens":1024,"prompt_cache_miss_tokens":176,
                 "completion_tokens_details":{"reasoning_tokens":50}}""";
    }

    // Kimi, retired on 2026-10-29:
    // static String kimiText(String content, String reasoning) {
    //     return """
    //             {"id":"kimi-1","object":"chat.completion","created":1761200000,"model":"kimi-k3",
    //              "choices":[{"index":0,"message":{"role":"assistant","content":%s,"reasoning_content":%s},
    //                "finish_reason":"stop"}],
    //              "usage":%s}
    //             """.formatted(quote(content), quote(reasoning), kimiUsage());
    // }

    // Kimi, retired on 2026-10-29:
    // static String kimiToolCalls(String reasoning) {
    //     return """
    //             {"id":"kimi-2","object":"chat.completion","created":1761200001,"model":"kimi-k3",
    //              "choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":%s,
    //                "tool_calls":%s},"finish_reason":"tool_calls"}],
    //              "usage":%s}
    //             """.formatted(quote(reasoning), toolCalls(), kimiUsage());
    // }

    // Kimi, retired on 2026-10-29:
    // static String kimiUsage() {
    //     return """
    //             {"prompt_tokens":1200,"completion_tokens":80,"total_tokens":1280,"cached_tokens":1024,
    //              "prompt_tokens_details":{"cached_tokens":1024,"cache_write_tokens":0}}""";
    // }

    /**
     * DeepSeek stream: reasoning deltas, then two tool calls whose arguments
     * arrive in fragments (only the first fragment of a call carries id, type
     * and name), then the finish chunk carrying the usage.
     */
    static List<String> deepSeekToolStream() {
        return List.of(
                chunk("ds-s1", "deepseek-v4-pro", "{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"Read the \"}", null, null),
                chunk("ds-s1", "deepseek-v4-pro", "{\"reasoning_content\":\"problem first.\"}", null, null),
                chunk("ds-s1", "deepseek-v4-pro", toolDelta(0, "call_1", "problem__read", ""), null, null),
                chunk("ds-s1", "deepseek-v4-pro", toolDelta(0, null, null, "{\\\"id\\\":"), null, null),
                chunk("ds-s1", "deepseek-v4-pro", toolDelta(0, null, null, "7}"), null, null),
                chunk("ds-s1", "deepseek-v4-pro", toolDelta(1, "call_2", "context__search_exact", "{\\\"query\\\":"), null, null),
                chunk("ds-s1", "deepseek-v4-pro", toolDelta(1, null, null, "\\\"dp\\\",\\\"limit\\\":3}"), null, null),
                chunk("ds-s1", "deepseek-v4-pro", "{\"content\":\"\"}", "tool_calls", deepSeekUsage()));
    }

    static List<String> deepSeekTextStream() {
        return List.of(
                chunk("ds-s2", "deepseek-v4-pro", "{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"Think.\"}", null, null),
                chunk("ds-s2", "deepseek-v4-pro", "{\"content\":\"Hel\"}", null, null),
                chunk("ds-s2", "deepseek-v4-pro", "{\"content\":\"lo\"}", null, null),
                chunk("ds-s2", "deepseek-v4-pro", "{\"content\":\"\"}", "stop", deepSeekUsage()));
    }

    // Kimi, retired on 2026-10-29:
    // /** Kimi stream: same deltas, but the usage comes in an extra chunk with {@code choices: []}. */
    // static List<String> kimiToolStream() {
    //     return List.of(
    //             chunk("kimi-s1", "kimi-k3", "{\"role\":\"assistant\",\"reasoning_content\":\"Read the \"}", null, null),
    //             chunk("kimi-s1", "kimi-k3", "{\"reasoning_content\":\"problem first.\"}", null, null),
    //             chunk("kimi-s1", "kimi-k3", toolDelta(0, "call_1", "problem__read", ""), null, null),
    //             chunk("kimi-s1", "kimi-k3", toolDelta(0, null, null, "{\\\"id\\\":"), null, null),
    //             chunk("kimi-s1", "kimi-k3", toolDelta(0, null, null, "7}"), null, null),
    //             chunk("kimi-s1", "kimi-k3", toolDelta(1, "call_2", "context__search_exact", "{\\\"query\\\":"), null, null),
    //             chunk("kimi-s1", "kimi-k3", toolDelta(1, null, null, "\\\"dp\\\",\\\"limit\\\":3}"), null, null),
    //             chunk("kimi-s1", "kimi-k3", "{}", "tool_calls", null),
    //             oneLine("{\"id\":\"kimi-s1\",\"object\":\"chat.completion.chunk\",\"created\":1761200003,\"model\":\"kimi-k3\","
    //                     + "\"choices\":[],\"usage\":" + kimiUsage() + "}"));
    // }

    static String error(String type, String message) {
        return "{\"error\":{\"message\":\"" + message + " " + BODY_SENTINEL + "\",\"type\":\"" + type + "\",\"code\":null}}";
    }

    static String embedding(int dimensions) {
        StringBuilder vector = new StringBuilder();
        for (int i = 0; i < dimensions; i++) {
            vector.append(i == 0 ? "" : ",").append(i % 2 == 0 ? "0.5" : "-0.25");
        }
        return """
                {"object":"list","model":"text-embedding-v4",
                 "data":[{"object":"embedding","index":0,"embedding":[%s]}],
                 "usage":{"prompt_tokens":5,"total_tokens":5}}
                """.formatted(vector);
    }

    private static String toolCalls() {
        return """
                [{"index":0,"id":"call_1","type":"function","function":{"name":"problem__read","arguments":%s}},
                 {"index":1,"id":"call_2","type":"function","function":{"name":"context__search_exact","arguments":%s}}]"""
                .formatted(quote(READ_ARGS), quote(SEARCH_ARGS));
    }

    private static String toolDelta(int index, String id, String name, String argumentsFragment) {
        StringBuilder call = new StringBuilder("{\"index\":").append(index);
        if (id != null) {
            call.append(",\"id\":\"").append(id).append("\",\"type\":\"function\"");
        }
        call.append(",\"function\":{");
        if (name != null) {
            call.append("\"name\":\"").append(name).append("\",");
        }
        call.append("\"arguments\":\"").append(argumentsFragment).append("\"}}");
        return "{\"tool_calls\":[" + call + "]}";
    }

    /** A long text stream, for cancelling mid-way; each delta is about 1 KB. */
    static List<String> deepSeekLongTextStream(int chunks) {
        String text = "x".repeat(1_000);
        List<String> events = new java.util.ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            events.add(chunk("ds-s3", "deepseek-v4-pro", "{\"content\":\"" + text + "\"}", null, null));
        }
        events.add(chunk("ds-s3", "deepseek-v4-pro", "{\"content\":\"\"}", "stop", deepSeekUsage()));
        return events;
    }

    /** Stream chunks are single-line JSON, as every Provider sends them; SSE ends an event at a newline. */
    private static String chunk(String id, String model, String delta, String finishReason, String usage) {
        return oneLine("{\"id\":\"" + id + "\",\"object\":\"chat.completion.chunk\",\"created\":1761200003,\"model\":\"" + model + "\","
                + "\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"logprobs\":null,\"finish_reason\":"
                + (finishReason == null ? "null" : "\"" + finishReason + "\"") + "}],"
                + "\"usage\":" + (usage == null ? "null" : usage) + "}");
    }

    private static String oneLine(String json) {
        return json.replaceAll("\\s*\\n\\s*", "");
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
