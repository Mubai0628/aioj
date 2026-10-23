package com.aioj.next.ai.providercontract;

import io.micrometer.observation.ObservationRegistry;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Plan T1.2a gate items 1 and 9 against the real APIs (§3.2.2). Billed calls:
 * runs only with AIOJ_LIVE_PROVIDER_TESTS=true and the provider key, about 5
 * DeepSeek and 4 Kimi calls per run, all Spring AI and SDK retries off and a
 * hard stop at {@value #CALL_BUDGET} calls. The facts each call shows go to
 * stdout as "[gate-live]" lines; soft assertions let one run collect them all.
 */
class SpringAiLiveGateTest {
    /** Same opt-in as AgentProviderLiveTest. */
    static final String OPT_IN = "AIOJ_LIVE_PROVIDER_TESTS";
    static final int CALL_BUDGET = 15;
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final String SYSTEM = """
            Answer only from tool results; never guess. First call release__codename. Then call
            release__date with that codename. Finally answer in one sentence that names both.""";
    private static final String USER = "下一个 AI-OJ 版本的代号是什么？什么时候发布？";

    @Test
    @EnabledIfEnvironmentVariable(named = OPT_IN, matches = "true")
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void item1_deepSeekThinkingToolLoopSendsReasoningBackOnEveryStep() {
        String model = env("AIOJ_GATE_DEEPSEEK_MODEL", "deepseek-v4-flash");
        DeepSeekChatModel chat = deepSeek();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder().model(model).enableThinking()
                .reasoningEffortHigh().maxTokens(4096).toolChoice("auto").toolCallbacks(tools()).build();
        SoftAssertions soft = new SoftAssertions();

        List<Message> history = new ArrayList<>(List.of(new SystemMessage(SYSTEM), new UserMessage(USER)));
        AtomicInteger stepsWithReasoning = new AtomicInteger();
        String answer = runLoop("deepseek", chat, () -> options, history, soft, step -> {
            DeepSeekAssistantMessage message = (DeepSeekAssistantMessage) step;
            if (message.getReasoningContent() != null && !message.getReasoningContent().isBlank()) {
                stepsWithReasoning.incrementAndGet();
            }
            // The request builder rejects null text (SpringAiDeepSeekGateTest); keep reasoning and calls.
            return ((DeepSeekAssistantMessage.Builder) message.mutate())
                    .content(message.getText() == null ? "" : message.getText()).build();
        });
        soft.assertThat(answer).contains("agent-core-v3").contains("11");
        // The flash model thinks only when it needs to; some tool-call step must carry reasoning.
        soft.assertThat(stepsWithReasoning).as("tool-call steps with reasoning").hasPositiveValue();

        // Streaming: does the last chunk carry usage although Spring AI sends no stream_options?
        List<ChatResponse> chunks = stream(chat, new Prompt(List.of(new UserMessage("只回复一个词：好")),
                DeepSeekChatOptions.builder().model(model).disableThinking().maxTokens(16).build()));
        Usage usage = lastUsage(chunks);
        report("deepseek stream: chunks=" + chunks.size() + " text=" + text(chunks).length() + " chars usage="
                + describe(usage));
        soft.assertThat(text(chunks)).isNotBlank();
        soft.assertThat(usage == null ? 0 : usage.getPromptTokens()).as("usage in the stream").isPositive();
        soft.assertAll();
    }

    /**
     * Control for item 1, one call: the docs say a thinking-mode request with
     * tools gets HTTP 400 when an earlier assistant tool call lacks its
     * reasoning_content. The history is built by hand, so the model's choice
     * to think or not cannot hide the rule.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = OPT_IN, matches = "true")
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void item1_control_aToolCallWithoutItsReasoningIsRejected() {
        String model = env("AIOJ_GATE_DEEPSEEK_MODEL", "deepseek-v4-flash");
        List<Message> history = List.of(new SystemMessage(SYSTEM), new UserMessage(USER),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new ToolCall("call_x1", "function", "release__codename", "{}"))).build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponse("call_x1", "release__codename", "{\"codename\":\"agent-core-v3\"}"))).build());

        Throwable rejected = catchThrowable(() -> call(deepSeek(), new Prompt(history,
                DeepSeekChatOptions.builder().model(model).enableThinking().reasoningEffortHigh().maxTokens(4096)
                        .toolChoice("auto").toolCallbacks(tools()).build())));

        report("deepseek control, tool call without reasoning_content: " + describe(rejected));
        assertThat(rejected).as("API enforces reasoning_content").isNotNull();
        assertThat(rejected.getMessage()).startsWith("400");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = OPT_IN, matches = "true")
    @EnabledIfEnvironmentVariable(named = "KIMI_API_KEY", matches = ".+")
    void item9_kimiK3ThroughTheOpenAiPathKeepsToolsReasoningAndStreamingIntact() {
        String model = env("AIOJ_GATE_KIMI_MODEL", "kimi-k3");
        OpenAiChatModel chat = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(env("AIOJ_GATE_KIMI_BASE_URL", "https://api.moonshot.cn/v1"))
                        .apiKey(System.getenv("KIMI_API_KEY")).model(model).maxRetries(0)
                        .timeout(Duration.ofSeconds(120)).build())
                .observationRegistry(ObservationRegistry.NOOP).build();
        Function<Boolean, OpenAiChatOptions> options = required -> OpenAiChatOptions.builder().model(model)
                .reasoningEffort("low").maxCompletionTokens(16_000).toolCallbacks(tools())
                .toolChoice(required ? "required" : "auto").build();
        SoftAssertions soft = new SoftAssertions();

        List<Message> history = new ArrayList<>(List.of(new SystemMessage(SYSTEM), new UserMessage(USER)));
        AtomicInteger step = new AtomicInteger();
        String answer = runLoop("kimi", chat, () -> options.apply(step.getAndIncrement() == 0), history, soft,
                message -> {
                    soft.assertThat(message.getMetadata().get("reasoningContent")).as("reasoning on a tool-call step")
                            .asString().isNotBlank();
                    // Spring AI replays reasoning from this metadata; keep the message as returned.
                    return message;
                });
        soft.assertThat(answer).contains("agent-core-v3").contains("11");

        List<ChatResponse> chunks = stream(chat, new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(USER)),
                options.apply(true)));
        List<ToolCall> streamed = new ArrayList<>();
        chunks.forEach(chunk -> chunk.getResults().forEach(g -> streamed.addAll(g.getOutput().getToolCalls())));
        Usage usage = lastUsage(chunks);
        report("kimi stream: chunks=" + chunks.size() + " toolCalls=" + names(streamed) + " usage=" + describe(usage));
        soft.assertThat(streamed).as("streamed tool call merged").isNotEmpty();
        soft.assertThat(streamed).allSatisfy(call -> assertThat(call.arguments()).startsWith("{").endsWith("}"));
        soft.assertThat(usage == null ? 0 : usage.getPromptTokens()).as("usage chunk").isPositive();
        soft.assertAll();
    }

    /** Runs the tool loop; {@code options} gives each step's options, {@code keep} what goes back into history. */
    private String runLoop(String provider, ChatModel chat, Supplier<ChatOptions> options, List<Message> history,
                           SoftAssertions soft, Function<AssistantMessage, AssistantMessage> keep) {
        int toolCalls = 0;
        for (int step = 1; step <= 4; step++) {
            ChatResponse response = call(chat, new Prompt(history, options.get()));
            Generation generation = response.getResult();
            AssistantMessage message = generation.getOutput();
            report(provider + " step " + step + ": text=" + (message.getText() == null ? "null" : message.getText().length() + " chars")
                    + " reasoning=" + reasoningLength(message) + " chars toolCalls=" + names(message.getToolCalls())
                    + " finish=" + generation.getMetadata().getFinishReason() + " usage=" + describe(response.getMetadata().getUsage()));
            if (message.getToolCalls().isEmpty()) {
                soft.assertThat(toolCalls).as(provider + " tool calls before the answer").isGreaterThanOrEqualTo(2);
                return message.getText() == null ? "" : message.getText();
            }
            toolCalls += message.getToolCalls().size();
            history.add(keep.apply(message));
            List<ToolResponse> results = new ArrayList<>();
            for (ToolCall call : message.getToolCalls()) {
                results.add(new ToolResponse(call.id(), call.name(), runTool(call)));
            }
            history.add(ToolResponseMessage.builder().responses(results).build());
        }
        soft.fail(provider + " loop did not finish in 4 steps");
        return "";
    }

    private static String runTool(ToolCall call) {
        return switch (call.name()) {
            case "release__codename" -> "{\"codename\":\"agent-core-v3\"}";
            case "release__date" -> call.arguments().contains("agent-core-v3")
                    ? "{\"codename\":\"agent-core-v3\",\"date\":\"2026-11-30\"}"
                    : "{\"error\":\"unknown codename; call release__codename first\"}";
            default -> "{\"error\":\"unknown tool\"}";
        };
    }

    private static List<ToolCallback> tools() {
        return List.of(
                definition("release__codename", "Returns the codename of the next AI-OJ release.",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
                definition("release__date", "Returns the release date for a codename.",
                        "{\"type\":\"object\",\"additionalProperties\":false,"
                                + "\"properties\":{\"codename\":{\"type\":\"string\",\"minLength\":1}},"
                                + "\"required\":[\"codename\"]}"));
    }

    private static ToolCallback definition(String name, String description, String schema) {
        ToolDefinition definition = ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("tools run in the test loop, never inside Spring AI");
            }
        };
    }

    private static DeepSeekChatModel deepSeek() {
        String url = env("DEEPSEEK_BASE_URL", "https://api.deepseek.com/chat/completions");
        String base = url.endsWith("/chat/completions") ? url.substring(0, url.length() - "/chat/completions".length()) : url;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(180));
        DeepSeekApi api = DeepSeekApi.builder().baseUrl(base).completionsPath("/chat/completions")
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .webClientBuilder(WebClient.builder())
                .responseErrorHandler(RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER).build();
        return DeepSeekChatModel.builder().deepSeekApi(api)
                .options(DeepSeekChatOptions.builder().model("deepseek-v4-flash").build())
                .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
                .observationRegistry(ObservationRegistry.NOOP).build();
    }

    private static ChatResponse call(ChatModel chat, Prompt prompt) {
        spend();
        return chat.call(prompt);
    }

    private static List<ChatResponse> stream(ChatModel chat, Prompt prompt) {
        spend();
        return chat.stream(prompt).collectList().block(Duration.ofSeconds(180));
    }

    private static void spend() {
        int used = CALLS.incrementAndGet();
        assertThat(used).as("live call budget").isLessThanOrEqualTo(CALL_BUDGET);
        report("call #" + used);
    }

    private static Usage lastUsage(List<ChatResponse> chunks) {
        Usage usage = null;
        for (ChatResponse chunk : chunks) {
            Usage current = chunk.getMetadata().getUsage();
            if (current != null && current.getPromptTokens() != null && current.getPromptTokens() > 0) {
                usage = current;
            }
        }
        return usage;
    }

    private static String text(List<ChatResponse> chunks) {
        StringBuilder text = new StringBuilder();
        chunks.forEach(chunk -> chunk.getResults().forEach(g -> {
            if (g.getOutput().getText() != null) {
                text.append(g.getOutput().getText());
            }
        }));
        return text.toString();
    }

    private static int reasoningLength(AssistantMessage message) {
        if (message instanceof DeepSeekAssistantMessage deepSeek) {
            return deepSeek.getReasoningContent() == null ? 0 : deepSeek.getReasoningContent().length();
        }
        Object value = message.getMetadata().get("reasoningContent");
        return value instanceof String text ? text.length() : 0;
    }

    private static List<String> names(List<ToolCall> calls) {
        return calls.stream().map(ToolCall::name).toList();
    }

    private static String describe(Usage usage) {
        if (usage == null) {
            return "none";
        }
        return "prompt=" + usage.getPromptTokens() + " completion=" + usage.getCompletionTokens()
                + " cacheRead=" + usage.getCacheReadInputTokens() + " native=" + (usage.getNativeUsage() == null
                ? "-" : usage.getNativeUsage().getClass().getSimpleName());
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "accepted";
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        return error.getClass().getSimpleName() + " status=" + (message.length() >= 3 ? message.substring(0, 3) : message);
    }

    private static void report(String line) {
        System.out.println("[gate-live] " + line);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
