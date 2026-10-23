package com.aioj.next.ai.providercontract;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aioj.next.ai.agent.model.CallProfile;
import com.aioj.next.ai.agent.model.CallSettings;
import com.aioj.next.ai.agent.model.DeepSeekToolCallAdapter;
import com.aioj.next.ai.agent.model.GatewayRequest;
import com.aioj.next.ai.agent.model.ToolChoiceMode;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.providercontract.FakeProvider.Reply;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
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
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aioj.next.ai.providercontract.ContractFixtures.API_KEY;
import static com.aioj.next.ai.providercontract.ContractFixtures.PRIOR_REASONING;
import static com.aioj.next.ai.providercontract.ContractFixtures.READ_SCHEMA;
import static com.aioj.next.ai.providercontract.ContractFixtures.SEARCH_SCHEMA;
import static com.aioj.next.ai.providercontract.ContractFixtures.SYSTEM;
import static com.aioj.next.ai.providercontract.ContractFixtures.TOOL_RESULT;
import static com.aioj.next.ai.providercontract.ContractFixtures.USER;
import static com.aioj.next.ai.providercontract.ProviderPayloads.BODY_SENTINEL;
import static com.aioj.next.ai.providercontract.ProviderPayloads.READ_ARGS;
import static com.aioj.next.ai.providercontract.ProviderPayloads.SEARCH_ARGS;
import static com.aioj.next.ai.providercontract.WireAssertions.assertLoopMessages;
import static com.aioj.next.ai.providercontract.WireAssertions.assertToolsPassThrough;
import static com.aioj.next.ai.providercontract.WireAssertions.strictFlag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Plan T1.2a step 2: gate items 2–7 of §3.2.2 for DeepSeek on Spring AI
 * 2.0.2. {@link DeepSeekChatModel} talks to the same {@link FakeProvider} and
 * sends the same logical request as {@link LegacyAdapterContractTest}. Each
 * test names its gate item; "gap" marks behavior an adapter would have to
 * work around. Spring AI is a test-scope dependency only.
 */
class SpringAiDeepSeekGateTest {
    private static final String COMPLETION = "Use a monotonic queue. completion-text-5d8e";

    private final FakeProvider provider = new FakeProvider();
    /** One shared JDK HttpClient, so keep-alive and timeouts are ours to set. */
    private final JdkClientHttpRequestFactory requestFactory = requestFactory(Duration.ofSeconds(30));

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    // ---- Gate item 2: CallProfile semantics -------------------------------------------------

    @ParameterizedTest(name = "thinking={0} effort={1} json={2}")
    @CsvSource({"true, high, false", "true, max, false", "false, high, false", "false, high, true"})
    void item2_callSettingsProduceTheSameRequestAsTheCurrentAdapter(boolean thinking, String effort, boolean json) {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("ok", null)));
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("ok", null)));
        CallSettings settings = new CallSettings(thinking, effort, 0.3, 4096);
        CallProfile profile = json ? CallProfile.STRUCTURED_SMALL : CallProfile.CHAT_STREAM;

        AiProperties properties = new AiProperties();
        new DeepSeekToolCallAdapter(new ObjectMapper(), properties).execute(
                ContractFixtures.config("deepseek", provider.chatUrl(), "deepseek-v4-pro", thinking, effort), settings,
                new GatewayRequest(ContractFixtures.loopRequest(ToolChoiceMode.AUTO).messages(),
                        ContractFixtures.tools(), ToolChoiceMode.AUTO, profile));
        model(noRetry()).call(new Prompt(loopMessages(), options(settings, json, definitions(new AtomicInteger()))));

        JsonNode legacy = provider.requests().get(0).json();
        JsonNode springAi = provider.requests().get(1).json();
        assertThat(fieldNames(springAi)).as("top-level request fields").isEqualTo(fieldNames(legacy));
        for (String field : fieldNames(legacy)) {
            if (!field.equals("messages") && !field.equals("tools")) {
                assertThat(springAi.path(field)).as(field).isEqualTo(legacy.path(field));
            }
        }
        assertLoopMessages(springAi);
        assertToolsPassThrough(springAi);
        assertThat(strictFlag(springAi, 0)).isNull();
    }

    // ---- Gate item 3: tool calls handed back, names, schemas, tool_choice ----------------------

    @Test
    void item3_toolCallsComeBackUnexecutedWithTheirReasoning() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekToolCalls("need the statement")));
        AtomicInteger executed = new AtomicInteger();

        ChatResponse response = model(noRetry()).call(new Prompt(loopMessages(),
                options(new CallSettings(true, "high", 0.3, 4096), false, definitions(executed))));

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output).isInstanceOf(DeepSeekAssistantMessage.class);
        assertThat(((DeepSeekAssistantMessage) output).getReasoningContent()).isEqualTo("need the statement");
        assertThat(output.getToolCalls()).containsExactly(
                new ToolCall("call_1", "function", "problem__read", READ_ARGS),
                new ToolCall("call_2", "function", "context__search_exact", SEARCH_ARGS));
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
        assertThat(executed).as("Spring AI must not run tools").hasValue(0);
        assertThat(provider.requests()).as("one request, no internal tool loop").hasSize(1);
    }

    /**
     * gap: DeepSeek answers a tool call with {@code content: null}; the
     * returned message cannot be sent back as it is, because the request
     * builder asserts that assistant text is non-null.
     */
    @Test
    void item3_anAssistantToolCallWithNullContentCannotBeSentBackUnchanged() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekToolCalls("need the statement")));
        DeepSeekChatModel model = model(noRetry());
        DeepSeekChatOptions options = options(new CallSettings(true, "high", 0.3, 4096), false,
                definitions(new AtomicInteger()));
        List<Message> history = new ArrayList<>(List.of(new SystemMessage(SYSTEM), new UserMessage(USER)));

        AssistantMessage step = model.call(new Prompt(history, options)).getResult().getOutput();
        history.add(step);
        history.add(toolResults());
        Throwable error = catchThrowable(() -> model.call(new Prompt(history, options)));

        assertThat(step.getText()).isNull();
        assertThat(error).isInstanceOf(IllegalStateException.class).hasMessageContaining("text must not be null");
        assertThat(provider.requests()).hasSize(1);
    }

    @Test
    void item3_withEmptyTextTheLoopStepGoesBackWithItsFullReasoning() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekToolCalls("need the statement")));
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText(COMPLETION, "done")));
        DeepSeekChatModel model = model(noRetry());
        DeepSeekChatOptions options = options(new CallSettings(true, "high", 0.3, 4096), false,
                definitions(new AtomicInteger()));
        List<Message> history = new ArrayList<>(List.of(new SystemMessage(SYSTEM), new UserMessage(USER)));

        DeepSeekAssistantMessage step = (DeepSeekAssistantMessage) model.call(new Prompt(history, options))
                .getResult().getOutput();
        history.add(((DeepSeekAssistantMessage.Builder) step.mutate()).content("").build());
        history.add(toolResults());
        ChatResponse answer = model.call(new Prompt(history, options));

        assertThat(answer.getResult().getOutput().getText()).isEqualTo(COMPLETION);
        JsonNode assistant = provider.requests().get(1).json().path("messages").path(2);
        assertThat(assistant.path("reasoning_content").asString()).isEqualTo("need the statement");
        assertThat(assistant.path("tool_calls")).hasSize(2);
        JsonNode toolMessage = provider.requests().get(1).json().path("messages").path(3);
        assertThat(toolMessage.path("tool_call_id").asString()).isEqualTo("call_1");
    }

    @Test
    void item3_toolChoiceIsSentAsGiven() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("ok", null)));

        model(noRetry()).call(new Prompt(loopMessages(), DeepSeekChatOptions.builder().model("deepseek-v4-pro")
                .disableThinking().toolCallbacks(definitions(new AtomicInteger())).toolChoice("required").build()));

        assertThat(provider.onlyRequest().json().path("tool_choice").asString()).isEqualTo("required");
    }

    // ---- Gate item 4: streaming ----------------------------------------------------------------

    @Test
    void item4_streamCarriesReasoningMergedToolCallsAndUsage() {
        provider.reply(Reply.sse(ProviderPayloads.deepSeekToolStream()));

        List<ChatResponse> responses = model(noRetry()).stream(new Prompt(loopMessages(),
                        options(new CallSettings(true, "high", 0.3, 4096), false, definitions(new AtomicInteger()))))
                .collectList().block(Duration.ofSeconds(10));

        StreamView view = StreamView.of(responses);
        assertThat(view.reasoning()).isEqualTo("Read the problem first.");
        assertThat(view.toolCalls()).containsExactly(
                new ToolCall("call_1", "function", "problem__read", READ_ARGS),
                new ToolCall("call_2", "function", "context__search_exact", SEARCH_ARGS));
        Usage usage = view.lastUsage();
        assertThat(usage.getPromptTokens()).isEqualTo(1200);
        assertThat(usage.getCompletionTokens()).isEqualTo(80);
        // gap: cache hits are only in the native usage, not in the portable field.
        assertThat(usage.getCacheReadInputTokens()).isNull();
        assertThat(((DeepSeekApi.Usage) usage.getNativeUsage()).promptTokensDetails().cachedTokens()).isEqualTo(1024);
        JsonNode body = provider.onlyRequest().json();
        assertThat(body.path("stream").asBoolean(false)).isTrue();
        assertThat(body.has("stream_options")).as("no include_usage is sent").isFalse();
    }

    @Test
    void item4_textDeltasArriveInOrder() {
        provider.reply(Reply.sse(ProviderPayloads.deepSeekTextStream()));

        List<ChatResponse> responses = model(noRetry()).stream(new Prompt(loopMessages(),
                        options(new CallSettings(true, "high", 0.3, 4096), false, List.of())))
                .collectList().block(Duration.ofSeconds(10));

        StreamView view = StreamView.of(responses);
        assertThat(view.textDeltas()).containsSubsequence("Hel", "lo");
        assertThat(view.text()).isEqualTo("Hello");
        assertThat(view.reasoning()).isEqualTo("Think.");
    }

    // ---- Gate item 5: errors and retry ---------------------------------------------------------

    /** gap: the default error handler puts the whole Provider body into the message. */
    @ParameterizedTest(name = "HTTP {0}")
    @CsvSource({
            "400, NonTransientAiException",
            "401, NonTransientAiException",
            "402, NonTransientAiException",
            "429, NonTransientAiException",
            "500, TransientAiException",
            "503, TransientAiException"
    })
    void item5_theDefaultErrorHandlerClassifiesByStatusAndLeaksTheBody(int status, String exceptionType) {
        provider.reply(Reply.json(status, ProviderPayloads.error("server_error", "rejected"), "Retry-After", "1"));

        Throwable error = catchThrowable(() -> model(noRetry()).call(simplePrompt()));

        assertThat(error.getClass().getSimpleName()).isEqualTo(exceptionType);
        assertThat(error.getMessage()).startsWith(status + " - ").contains(BODY_SENTINEL);
        assertThat(provider.requests()).as("429 is not transient: no retry, Retry-After ignored").hasSize(1);
    }

    @Test
    void item5_springAiRetriesTransientErrorsItselfUnlessTheTemplateIsReplaced() {
        for (int i = 0; i < 11; i++) {
            provider.reply(Reply.json(503, ProviderPayloads.error("server_unavailable", "busy")));
        }

        assertThatThrownBy(() -> model(RetryUtils.SHORT_RETRY_TEMPLATE).call(simplePrompt()))
                .isInstanceOf(TransientAiException.class);
        // DEFAULT_RETRY_TEMPLATE has the same 10 retries, but 2 s × 5 backoff capped at 3 min.
        assertThat(provider.requests()).hasSize(11);
    }

    @Test
    void item5_aCustomErrorHandlerKeepsTheBodyOut() {
        provider.reply(Reply.json(429, ProviderPayloads.error("rate_limit_reached_error", "slow down")));
        ResponseErrorHandler handler = new ResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                return response.getStatusCode().isError();
            }

            @Override
            public void handleError(java.net.URI url, org.springframework.http.HttpMethod method,
                                    org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                throw new NonTransientAiException("provider HTTP " + response.getStatusCode().value());
            }
        };

        assertThatThrownBy(() -> model(noRetry(), handler).call(simplePrompt()))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessage("provider HTTP 429");
    }

    @Test
    void item5_streamErrorsKeepTheBodyOutOfTheMessageButOnTheException() {
        provider.reply(Reply.json(400, ProviderPayloads.error("invalid_request_error", "bad")));

        Throwable error = catchThrowable(() -> model(noRetry()).stream(simplePrompt()).collectList()
                .block(Duration.ofSeconds(10)));

        assertThat(error).isInstanceOf(WebClientResponseException.class);
        assertThat(error.getMessage()).doesNotContain(BODY_SENTINEL);
        assertThat(((WebClientResponseException) error).getResponseBodyAsString()).contains(BODY_SENTINEL);
    }

    // ---- Gate item 6: timeouts, cancellation, connection reuse ---------------------------------

    @Test
    void item6_theReadTimeoutBelongsToTheClientNotToTheCall() {
        provider.reply(Reply.delayed(1_500, Reply.json(200, ProviderPayloads.deepSeekText("late", null))));
        DeepSeekChatModel model = model(noRetry(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER,
                requestFactory(Duration.ofMillis(300)));

        long started = System.nanoTime();
        assertThatThrownBy(() -> model.call(simplePrompt())).isInstanceOf(ResourceAccessException.class);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_400);
        // ResourceAccessException is in the default templates' retry list, so without
        // noRetry() a timeout would be retried ten times.
        assertThat(provider.requests()).hasSize(1);
    }

    @Test
    void item6_sequentialCallsReuseOneConnection() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("one", null)));
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("two", null)));
        DeepSeekChatModel model = model(noRetry());

        model.call(simplePrompt());
        model.call(simplePrompt());

        assertThat(provider.requests()).extracting(FakeProvider.RecordedRequest::remotePort)
                .as("same client port means a kept-alive connection").containsOnly(provider.requests().get(0).remotePort());
    }

    /**
     * gap: a cancelled stream has to close the connection, because Providers
     * stop generating (and billing) only when the client goes away. Spring AI
     * streams DeepSeek through WebClient; with the JDK connector (no Reactor
     * Netty on the classpath) cancelling leaves the socket open. 4 MB of deltas
     * overflow any socket buffer, so the server would fail a write if the
     * client had closed; it blocks instead.
     */
    @Test
    void item6_cancellingAStreamLeavesTheConnectionOpen() throws InterruptedException {
        provider.reply(Reply.sse(ProviderPayloads.deepSeekLongTextStream(4_000), 0));

        ChatResponse first = model(noRetry()).stream(simplePrompt()).blockFirst(Duration.ofSeconds(5));

        assertThat(first).isNotNull();
        Thread.sleep(2_000);
        assertThat(provider.abandonedReplies()).as("server never saw the client go away").isZero();
    }

    /** The control for the gap above: the JDK client itself closes the connection when the body is closed. */
    @Test
    void item6_aPlainJdkHttpClientClosesTheConnectionWhenTheStreamIsClosed() throws Exception {
        provider.reply(Reply.sse(ProviderPayloads.deepSeekLongTextStream(4_000), 0));
        java.net.http.HttpResponse<java.util.stream.Stream<String>> response = HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(provider.chatUrl()))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofLines());

        try (java.util.stream.Stream<String> lines = response.body()) {
            assertThat(lines.limit(3).count()).isEqualTo(3);
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (provider.abandonedReplies() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(provider.abandonedReplies()).isEqualTo(1);
    }

    @Test
    void item6_interruptingABlockingCallEndsItPromptly() throws Exception {
        provider.reply(Reply.delayed(3_000, Reply.json(200, ProviderPayloads.deepSeekText("late", null))));
        DeepSeekChatModel model = model(noRetry());
        CountDownLatch ended = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> call = caller.submit(() -> {
                try {
                    model.call(simplePrompt());
                } finally {
                    ended.countDown();
                }
            });
            Thread.sleep(300);
            call.cancel(true);

            assertThat(ended.await(1, TimeUnit.SECONDS)).as("call returned soon after the interrupt").isTrue();
        } finally {
            caller.shutdownNow();
        }
    }

    // ---- Gate item 7: no prompt or completion text in observations -----------------------------

    @Test
    void item7_observationsCarryNoPromptOrCompletionText() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText(COMPLETION, PRIOR_REASONING)));
        List<String> recorded = new ArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                recorded.add(context.getName() + " " + context.getAllKeyValues());
            }
        });

        model(noRetry(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER, requestFactory, registry)
                .call(new Prompt(loopMessages(), options(new CallSettings(true, "high", 0.3, 4096), false,
                        definitions(new AtomicInteger()))));

        assertThat(recorded).singleElement().asString()
                .startsWith("gen_ai.client.operation")
                .contains("gen_ai.usage.input_tokens", "problem__read")
                .doesNotContain("system-text-31c4", "user-text-8b2e", "completion-text-5d8e", "reasoning-text-46a1");
    }

    /** gap: an answer without choices is logged at WARN together with the whole prompt. */
    @Test
    void item7_anAnswerWithoutChoicesIsLoggedWithThePromptText() {
        provider.reply(Reply.json(200, "{\"id\":\"ds-x\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"deepseek-v4-pro\",\"choices\":null}"));
        Logger logger = (Logger) LoggerFactory.getLogger(DeepSeekChatModel.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ChatResponse response = model(noRetry()).call(simplePrompt());

            assertThat(response.getResults()).isEmpty();
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("user-text-8b2e");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private DeepSeekChatModel model(RetryTemplate retryTemplate) {
        return model(retryTemplate, RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
    }

    private DeepSeekChatModel model(RetryTemplate retryTemplate, ResponseErrorHandler errorHandler) {
        return model(retryTemplate, errorHandler, requestFactory);
    }

    private DeepSeekChatModel model(RetryTemplate retryTemplate, ResponseErrorHandler errorHandler,
                                    JdkClientHttpRequestFactory factory) {
        return model(retryTemplate, errorHandler, factory, ObservationRegistry.NOOP);
    }

    private DeepSeekChatModel model(RetryTemplate retryTemplate, ResponseErrorHandler errorHandler,
                                    JdkClientHttpRequestFactory factory, ObservationRegistry registry) {
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(provider.baseUrl())
                .completionsPath("/chat/completions")
                .apiKey(API_KEY)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .webClientBuilder(WebClient.builder())
                .responseErrorHandler(errorHandler)
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(DeepSeekChatOptions.builder().model("deepseek-v4-pro").build())
                .retryTemplate(retryTemplate)
                .observationRegistry(registry)
                .build();
    }

    /** What a T1.2b DeepSeek adapter would build from {@link CallSettings}. */
    private static DeepSeekChatOptions options(CallSettings settings, boolean json, List<ToolCallback> tools) {
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder()
                .model("deepseek-v4-pro")
                .maxTokens(settings.maxTokens())
                .toolCallbacks(tools);
        if (!tools.isEmpty()) {
            builder.toolChoice("auto");
        }
        if (settings.thinkingEnabled()) {
            builder.enableThinking();
            if ("max".equalsIgnoreCase(settings.reasoningEffort())) {
                builder.reasoningEffortMax();
            } else {
                builder.reasoningEffortHigh();
            }
        } else {
            builder.disableThinking().temperature(settings.temperature());
        }
        if (json) {
            builder.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        }
        return builder.build();
    }

    /** Definitions only: the gate requires that Spring AI never calls them. */
    static List<ToolCallback> definitions(AtomicInteger executed) {
        return List.of(definition("problem__read", "Read a problem.", READ_SCHEMA, executed),
                definition("context__search_exact", "Search the conversation.", SEARCH_SCHEMA, executed));
    }

    private static ToolCallback definition(String name, String description, String schema, AtomicInteger executed) {
        ToolDefinition definition = ToolDefinition.builder().name(name).description(description)
                .inputSchema(schema).build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                executed.incrementAndGet();
                throw new IllegalStateException("tools run in ToolBroker, never inside Spring AI");
            }
        };
    }

    static List<Message> loopMessages() {
        return List.of(new SystemMessage(SYSTEM), new UserMessage(USER),
                new DeepSeekAssistantMessage.Builder().content("").reasoningContent(PRIOR_REASONING)
                        .toolCalls(List.of(new ToolCall("call_0", "function", "problem__read", "{\"id\":7}"))).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponse("call_0", "problem__read", TOOL_RESULT))).build());
    }

    private static ToolResponseMessage toolResults() {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponse("call_1", "problem__read", TOOL_RESULT),
                new ToolResponse("call_2", "context__search_exact", "[]"))).build();
    }

    private static Prompt simplePrompt() {
        return new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(USER)),
                DeepSeekChatOptions.builder().model("deepseek-v4-pro").disableThinking().maxTokens(256).build());
    }

    private static RetryTemplate noRetry() {
        return new RetryTemplate(RetryPolicy.builder().maxRetries(0).build());
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    /** Folds a stream into what a caller sees. */
    record StreamView(List<String> textDeltas, String reasoning, List<ToolCall> toolCalls, Usage lastUsage) {
        static StreamView of(List<ChatResponse> responses) {
            List<String> deltas = new ArrayList<>();
            StringBuilder reasoning = new StringBuilder();
            List<ToolCall> toolCalls = new ArrayList<>();
            Usage usage = null;
            for (ChatResponse response : responses) {
                for (Generation generation : response.getResults()) {
                    AssistantMessage message = generation.getOutput();
                    if (message.getText() != null && !message.getText().isEmpty()) {
                        deltas.add(message.getText());
                    }
                    if (message instanceof DeepSeekAssistantMessage deepSeek && deepSeek.getReasoningContent() != null) {
                        reasoning.append(deepSeek.getReasoningContent());
                    }
                    toolCalls.addAll(message.getToolCalls());
                }
                if (response.getMetadata().getUsage() != null) {
                    usage = response.getMetadata().getUsage();
                }
            }
            return new StreamView(deltas, reasoning.toString(), toolCalls, usage);
        }

        String text() {
            return String.join("", textDeltas);
        }
    }
}
