package com.aioj.next.ai.providercontract;

import com.aioj.next.ai.providercontract.FakeProvider.Reply;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aioj.next.ai.providercontract.ContractFixtures.API_KEY;
import static com.aioj.next.ai.providercontract.ContractFixtures.PRIOR_REASONING;
import static com.aioj.next.ai.providercontract.ContractFixtures.READ_SCHEMA;
import static com.aioj.next.ai.providercontract.ContractFixtures.SYSTEM;
import static com.aioj.next.ai.providercontract.ContractFixtures.TOOL_RESULT;
import static com.aioj.next.ai.providercontract.ContractFixtures.USER;
import static com.aioj.next.ai.providercontract.ContractFixtures.schema;
import static com.aioj.next.ai.providercontract.ProviderPayloads.BODY_SENTINEL;
import static com.aioj.next.ai.providercontract.ProviderPayloads.READ_ARGS;
import static com.aioj.next.ai.providercontract.ProviderPayloads.SEARCH_ARGS;
import static com.aioj.next.ai.providercontract.WireAssertions.assertLoopMessages;
import static com.aioj.next.ai.providercontract.WireAssertions.assertToolsPassThrough;
import static com.aioj.next.ai.providercontract.WireAssertions.strictFlag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Plan T1.2a step 2: gate item 9 (Kimi through the OpenAI-compatible path,
 * re-running items 3 and 4) and item 8 (OpenAI-compatible embeddings with
 * {@code dimensions}) on Spring AI 2.0.2, plus the items 5–6 facts of this
 * module. Since 2.0 {@code spring-ai-openai} wraps the official OpenAI Java SDK
 * (OkHttp transport, its own retry), so these facts differ from the DeepSeek
 * module's. "gap" marks behavior an adapter would have to work around.
 */
class SpringAiOpenAiKimiGateTest {
    private final FakeProvider provider = new FakeProvider();

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    // ---- items 9 + 3: request shape, tools, strict, reasoning replay ---------------------------

    @Test
    void item9_k3RequestCarriesTheLoopToolsAndReasoning() {
        provider.reply(Reply.json(200, ProviderPayloads.kimiText("ok", null)));

        model(0).call(new Prompt(loopMessages(), k3Options(SpringAiDeepSeekGateTest.definitions(new AtomicInteger()))
                .toolChoice("required").build()));

        FakeProvider.RecordedRequest request = provider.onlyRequest();
        assertThat(request.path()).isEqualTo("/chat/completions");
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + API_KEY);
        JsonNode body = request.json();
        assertThat(body.path("model").asString()).isEqualTo("kimi-k3");
        assertThat(body.path("reasoning_effort").asString()).isEqualTo("high");
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("temperature")).as("K3 fixes temperature; nothing is sent").isFalse();
        assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(4096);
        assertThat(body.path("tool_choice").asString()).isEqualTo("required");
        assertLoopMessages(body);
        assertToolsPassThrough(body);
        // gap: strict is one flag for the whole request, sent as false by default,
        // which overrides Kimi's documented per-function default of true.
        assertThat(strictFlag(body, 0)).isFalse();
        assertThat(strictFlag(body, 1)).isFalse();
    }

    /** gap: strict=true is all-or-nothing and rewrites every schema to OpenAI's strict rules. */
    @Test
    void item9_strictTrueRewritesTheToolSchemas() {
        provider.reply(Reply.json(200, ProviderPayloads.kimiText("ok", null)));

        model(0).call(new Prompt(loopMessages(), k3Options(SpringAiDeepSeekGateTest.definitions(new AtomicInteger()))
                .strict(true).build()));

        JsonNode sent = provider.onlyRequest().json().path("tools").path(0).path("function").path("parameters");
        assertThat(sent).isNotEqualTo(schema(READ_SCHEMA));
        assertThat(sent.path("required").toString()).contains("sections");
        assertThat(sent.path("properties").path("sections").path("type").toString()).contains("null");
    }

    @Test
    void item9_toolCallsComeBackUnexecutedWithReasoningAndCacheHits() {
        provider.reply(Reply.json(200, ProviderPayloads.kimiToolCalls("need the statement")));
        AtomicInteger executed = new AtomicInteger();

        ChatResponse response = model(0).call(new Prompt(loopMessages(),
                k3Options(SpringAiDeepSeekGateTest.definitions(executed)).build()));

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getToolCalls()).containsExactly(
                new ToolCall("call_1", "function", "problem__read", READ_ARGS),
                new ToolCall("call_2", "function", "context__search_exact", SEARCH_ARGS));
        assertThat(output.getMetadata()).containsEntry("reasoningContent", "need the statement");
        assertThat(executed).hasValue(0);
        Usage usage = response.getMetadata().getUsage();
        assertThat(usage.getPromptTokens()).isEqualTo(1200);
        assertThat(usage.getCacheReadInputTokens()).isEqualTo(1024);
        assertThat(provider.requests()).hasSize(1);
    }

    @Test
    void item9_theReturnedAssistantMessageGoesBackWithItsReasoning() {
        provider.reply(Reply.json(200, ProviderPayloads.kimiToolCalls("need the statement")));
        provider.reply(Reply.json(200, ProviderPayloads.kimiText("answer", "done")));
        OpenAiChatModel model = model(0);
        OpenAiChatOptions options = k3Options(SpringAiDeepSeekGateTest.definitions(new AtomicInteger())).build();
        List<Message> history = new ArrayList<>(List.of(new SystemMessage(SYSTEM), new UserMessage(USER)));

        history.add(model.call(new Prompt(history, options)).getResult().getOutput());
        history.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponse("call_1", "problem__read", TOOL_RESULT),
                new ToolResponse("call_2", "context__search_exact", "[]"))).build());
        ChatResponse answer = model.call(new Prompt(history, options));

        assertThat(answer.getResult().getOutput().getText()).isEqualTo("answer");
        JsonNode assistant = provider.requests().get(1).json().path("messages").path(2);
        assertThat(assistant.path("reasoning_content").asString()).isEqualTo("need the statement");
        assertThat(assistant.path("tool_calls")).hasSize(2);
    }

    // ---- items 9 + 4: streaming ----------------------------------------------------------------

    @Test
    void item9_streamMergesToolCallsAndReadsTheSeparateUsageChunk() {
        provider.reply(Reply.sse(ProviderPayloads.kimiToolStream()));

        List<ChatResponse> responses = model(0).stream(new Prompt(loopMessages(),
                        k3Options(SpringAiDeepSeekGateTest.definitions(new AtomicInteger())).build()))
                .collectList().block(Duration.ofSeconds(10));

        List<ToolCall> toolCalls = new ArrayList<>();
        String reasoning = null;
        Usage usage = null;
        for (ChatResponse response : responses) {
            for (Generation generation : response.getResults()) {
                toolCalls.addAll(generation.getOutput().getToolCalls());
                Object value = generation.getOutput().getMetadata().get("reasoningContent");
                if (value instanceof String text && !text.isEmpty()) {
                    reasoning = text;
                }
            }
            if (response.getMetadata().getUsage() != null && response.getMetadata().getUsage().getPromptTokens() > 0) {
                usage = response.getMetadata().getUsage();
            }
        }
        assertThat(toolCalls).containsExactly(
                new ToolCall("call_1", "function", "problem__read", READ_ARGS),
                new ToolCall("call_2", "function", "context__search_exact", SEARCH_ARGS));
        assertThat(reasoning).as("accumulated across chunks").isEqualTo("Read the problem first.");
        assertThat(usage).isNotNull();
        assertThat(usage.getPromptTokens()).isEqualTo(1200);
        assertThat(usage.getCacheReadInputTokens()).isEqualTo(1024);
        JsonNode body = provider.onlyRequest().json();
        assertThat(body.path("stream").asBoolean(false)).isTrue();
        assertThat(body.path("stream_options").path("include_usage").asBoolean(false)).isTrue();
    }

    // ---- items 5 + 6 for this module -----------------------------------------------------------

    /** gap: the SDK's error messages carry the Provider body. */
    @Test
    void item5_errorsFailOnceWhenSdkRetriesAreOff() {
        provider.reply(Reply.json(400, ProviderPayloads.error("invalid_request_error", "rejected")));

        Throwable error = catchThrowable(() -> model(0).call(simplePrompt()));

        assertThat(error).isNotNull();
        assertThat(error.getClass().getName()).startsWith("com.openai.errors.");
        assertThat(error.getMessage()).contains(BODY_SENTINEL);
        assertThat(provider.requests()).hasSize(1);
    }

    @Test
    void item5_theSdkRetriesByDefaultAndHonorsRetryAfter() {
        for (int i = 0; i < 4; i++) {
            provider.reply(Reply.json(429, ProviderPayloads.error("engine_overloaded_error", "busy"),
                    "retry-after-ms", "10"));
        }

        Throwable error = catchThrowable(() -> model(null).call(simplePrompt()));

        assertThat(error.getClass().getSimpleName()).isEqualTo("RateLimitException");
        assertThat(provider.requests()).as("1 call + DEFAULT_MAX_RETRIES (3)").hasSize(4);
    }

    @Test
    void item6_aPerRequestTimeoutOverridesTheClientTimeout() {
        provider.reply(Reply.delayed(1_500, Reply.json(200, ProviderPayloads.kimiText("late", null))));

        long started = System.nanoTime();
        Throwable error = catchThrowable(() -> model(0).call(new Prompt(List.of(new UserMessage(USER)),
                OpenAiChatOptions.builder().model("kimi-k3").timeout(Duration.ofMillis(300)).build())));

        assertThat(error).isNotNull();
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_400);
        assertThat(provider.requests()).hasSize(1);
    }

    /**
     * gap: cancelling the Flux closes the SDK's AsyncStreamResponse, yet the
     * SDK keeps reading the HTTP stream to its end, so the Provider generates
     * (and bills) the whole answer. 4 MB of deltas would make the server's
     * writes fail if the connection had been closed.
     */
    @Test
    void item6_cancellingAStreamStillReadsTheWholeResponse() throws InterruptedException {
        provider.reply(Reply.sse(ProviderPayloads.deepSeekLongTextStream(4_000), 0));

        ChatResponse first = model(0).stream(simplePrompt()).blockFirst(Duration.ofSeconds(5));

        assertThat(first).isNotNull();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (provider.abandonedReplies() + provider.completedReplies() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(provider.completedReplies()).as("all 4 MB were read after the cancel").isEqualTo(1);
        assertThat(provider.abandonedReplies()).isZero();
    }

    // ---- item 8: OpenAI-compatible embeddings with dimensions ----------------------------------

    @Test
    void item8_embeddingRequestsCarryDimensions() {
        provider.reply(Reply.json(200, ProviderPayloads.embedding(1024)));
        OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                .options(OpenAiEmbeddingOptions.builder().baseUrl(provider.baseUrl()).apiKey(API_KEY)
                        .model("text-embedding-v4").dimensions(1024).maxRetries(0).build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        EmbeddingResponse response = model.embedForResponse(List.of("monotonic queue"));

        FakeProvider.RecordedRequest request = provider.onlyRequest();
        assertThat(request.path()).isEqualTo("/embeddings");
        assertThat(request.json().path("model").asString()).isEqualTo("text-embedding-v4");
        assertThat(request.json().path("dimensions").asInt()).isEqualTo(1024);
        assertThat(request.json().path("input").toString()).contains("monotonic queue");
        assertThat(response.getResult().getOutput()).hasSize(1024);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** {@code maxRetries == null} keeps the SDK default. */
    private OpenAiChatModel model(Integer maxRetries) {
        OpenAiChatOptions.Builder defaults = OpenAiChatOptions.builder()
                .baseUrl(provider.baseUrl())
                .apiKey(API_KEY)
                .model("kimi-k3")
                .timeout(Duration.ofSeconds(30));
        if (maxRetries != null) {
            defaults.maxRetries(maxRetries);
        }
        return OpenAiChatModel.builder().options(defaults.build()).observationRegistry(ObservationRegistry.NOOP).build();
    }

    /** What a T1.2b Kimi adapter would send for K3 with thinking on. */
    private static OpenAiChatOptions.Builder k3Options(List<ToolCallback> tools) {
        return OpenAiChatOptions.builder()
                .model("kimi-k3")
                .reasoningEffort("high")
                .maxCompletionTokens(4096)
                .toolCallbacks(tools);
    }

    private static List<Message> loopMessages() {
        return List.of(new SystemMessage(SYSTEM), new UserMessage(USER),
                AssistantMessage.builder().content("").properties(Map.of("reasoningContent", PRIOR_REASONING))
                        .toolCalls(List.of(new ToolCall("call_0", "function", "problem__read", "{\"id\":7}"))).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponse("call_0", "problem__read", TOOL_RESULT))).build());
    }

    private static Prompt simplePrompt() {
        return new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(USER)),
                OpenAiChatOptions.builder().model("kimi-k3").maxCompletionTokens(256).build());
    }
}
