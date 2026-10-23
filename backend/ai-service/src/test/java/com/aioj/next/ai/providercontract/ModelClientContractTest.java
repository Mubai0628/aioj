package com.aioj.next.ai.providercontract;

import com.aioj.next.ai.model.DefaultModelClient;
import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.EmbeddingRequest;
import com.aioj.next.ai.model.api.EmbeddingResponse;
import com.aioj.next.ai.model.api.ModelCallRecord;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelInfo;
import com.aioj.next.ai.model.api.ModelOperation;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.api.ToolCall;
import com.aioj.next.ai.model.api.ToolChoice;
import com.aioj.next.ai.providercontract.FakeProvider.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static com.aioj.next.ai.providercontract.ContractFixtures.API_KEY;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.SCOPE;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.client;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.endpoint;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.fastProperties;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.loopRequest;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.userOnly;
import static com.aioj.next.ai.providercontract.ProviderPayloads.BODY_SENTINEL;
import static com.aioj.next.ai.providercontract.WireAssertions.assertLoopMessages;
import static com.aioj.next.ai.providercontract.WireAssertions.assertToolsPassThrough;
import static com.aioj.next.ai.providercontract.WireAssertions.strictFlag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan T1.2b: the model call package against {@link FakeProvider}, held to every
 * wire assertion of T1.2a's baseline for the old adapters (deleted with them in
 * T1.3), with the gaps that baseline pinned turned into the correct behavior
 * (appendix G.4 item 7).
 */
class ModelClientContractTest {
    private final FakeProvider provider = new FakeProvider();
    private final ModelContractFixtures.Recorder recorder = new ModelContractFixtures.Recorder();

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    @Test
    void deepSeekThinkingRequestHasTheOfficialShape() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("ok", "plan")));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", true, "max");

        complete(deepSeek, loopRequest(deepSeek, ToolChoice.AUTO));

        FakeProvider.RecordedRequest request = provider.onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/chat/completions");
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(request.header("Accept")).isEqualTo("application/json");
        JsonNode body = request.json();
        assertThat(body.path("model").asString()).isEqualTo("deepseek-v4-pro");
        assertThat(body.path("stream").asBoolean(true)).isFalse();
        assertThat(body.has("stream_options")).isFalse();
        assertThat(body.path("thinking").path("type").asString()).isEqualTo("enabled");
        assertThat(body.path("reasoning_effort").asString()).isEqualTo("max");
        assertThat(body.has("temperature")).as("no temperature in thinking mode").isFalse();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(body.path("tool_choice").asString()).isEqualTo("auto");
        assertLoopMessages(body);
        assertToolsPassThrough(body);
        assertThat(strictFlag(body, 0)).isNull();
    }

    /** gap closed: the K3 guide fixes the temperature and replaces max_tokens. */
    @Test
    void kimiK3RequestFollowsTheK3Guide() {
        provider.reply(Reply.json(200, ProviderPayloads.kimiText("ok", "plan")));
        ModelEndpointConfig kimi = endpoint(ProviderId.KIMI, provider.chatUrl(), "kimi-k3", true, "high");

        complete(kimi, loopRequest(kimi, ToolChoice.REQUIRED));

        JsonNode body = provider.onlyRequest().json();
        assertThat(body.path("model").asString()).isEqualTo("kimi-k3");
        assertThat(body.path("reasoning_effort").asString()).isEqualTo("high");
        assertThat(body.has("thinking")).as("K3 always thinks and takes no thinking field").isFalse();
        assertThat(body.path("tool_choice").asString()).isEqualTo("required");
        assertLoopMessages(body);
        assertToolsPassThrough(body);
        assertThat(strictFlag(body, 0)).isTrue();
        assertThat(strictFlag(body, 1)).isTrue();
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(4096);
    }

    @Test
    void kimiK26AndK27CodeSendNoTemperatureAndK27CodeAlwaysThinks() {
        provider.reply(Reply.json(200, ProviderPayloads.kimiText("ok", null)));
        provider.reply(Reply.json(200, ProviderPayloads.kimiText("ok", null)));
        ModelEndpointConfig k26 = endpoint(ProviderId.KIMI, provider.chatUrl(), "kimi-k2.6", true, "high");
        ModelEndpointConfig k27 = endpoint(ProviderId.KIMI, provider.chatUrl(), "kimi-k2.7-code", false, "high");

        try (DefaultModelClient client = client(k26, fastProperties())) {
            client.complete(userOnly(k26, CallProfile.CHAT_STREAM), CallOptions.of("test"));
            client.complete(userOnly(k27, CallProfile.CHAT_STREAM), CallOptions.of("test"));
            assertThat(client.capabilities(k26).toolChoiceRequiredNative()).isFalse();
            assertThat(client.capabilities(k27).thinkingAlwaysOn()).isTrue();
        }

        JsonNode k26Body = provider.requests().get(0).json();
        assertThat(k26Body.path("thinking").path("type").asString()).isEqualTo("enabled");
        assertThat(k26Body.has("temperature")).isFalse();
        assertThat(k26Body.path("max_tokens").asInt()).isEqualTo(4096);
        JsonNode k27Body = provider.requests().get(1).json();
        assertThat(k27Body.path("thinking").path("type").asString()).as("K2.7-code only works thinking")
                .isEqualTo("enabled");
        assertThat(k27Body.has("temperature")).isFalse();
    }

    @Test
    void deepSeekParallelToolCallsAreParsedWithReasoningAndCacheHits() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekToolCalls("need the statement")));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", true, "high");

        ModelResponse response = complete(deepSeek, loopRequest(deepSeek, ToolChoice.AUTO));

        assertThat(response.content()).as("content: null becomes empty text").isEmpty();
        assertThat(response.reasoningContent()).isEqualTo("need the statement");
        assertThat(response.toolCalls()).containsExactly(
                new ToolCall("call_1", "problem.read", ProviderPayloads.READ_ARGS),
                new ToolCall("call_2", "context.search_exact", ProviderPayloads.SEARCH_ARGS));
        assertThat(response.finishReason()).isEqualTo("tool_calls");
        assertThat(response.usage().promptTokens()).isEqualTo(1200);
        assertThat(response.usage().completionTokens()).isEqualTo(80);
        assertThat(response.usage().cacheHitTokens()).isEqualTo(1024);
        assertThat(response.usage().reported()).isTrue();
    }

    /** gap closed: Kimi reports cache hits as cached_tokens. */
    @Test
    void kimiToolCallsAreParsedWithCachedTokens() {
        provider.reply(Reply.json(200, ProviderPayloads.kimiToolCalls("need the statement")));
        ModelEndpointConfig kimi = endpoint(ProviderId.KIMI, provider.chatUrl(), "kimi-k3", true, "high");

        ModelResponse response = complete(kimi, loopRequest(kimi, ToolChoice.AUTO));

        assertThat(response.reasoningContent()).isEqualTo("need the statement");
        assertThat(response.toolCalls()).extracting(ToolCall::name)
                .containsExactly("problem.read", "context.search_exact");
        assertThat(response.usage().promptTokens()).isEqualTo(1200);
        assertThat(response.usage().cacheHitTokens()).isEqualTo(1024);
    }

    @Test
    void finishReasonsOutsideTheOpenAiSetAreKeptAsText() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekFinishReason("insufficient_system_resource")));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", false, "high");

        ModelResponse response = complete(deepSeek, loopRequest(deepSeek, ToolChoice.AUTO));

        assertThat(response.finishReason()).isEqualTo("insufficient_system_resource");
        assertThat(response.content()).isEqualTo("partial");
    }

    /**
     * gap closed: no Provider body in any message, whatever the status; rate limits
     * and unavailable endpoints are retried (twice), everything else fails at once.
     */
    @ParameterizedTest(name = "HTTP {0} {1} -> {2}")
    @CsvSource({
            "400, invalid_request_error,    INVALID_REQUEST, 1",
            "401, authentication_error,     AUTHENTICATION,  1",
            "402, insufficient_balance,     QUOTA_EXHAUSTED, 1",
            "404, not_found,                CONFIGURATION_INVALID, 1",
            "429, rate_limit_reached_error, RATE_LIMITED,    3",
            "500, server_error,             UNAVAILABLE,     3",
            "503, server_unavailable,       UNAVAILABLE,     3"
    })
    void providerErrorsAreTypedAndNeverCarryTheBody(int status, String type, ModelErrorKind kind, int requests) {
        for (int i = 0; i < 3; i++) {
            provider.reply(Reply.json(status, ProviderPayloads.error(type, "rejected " + API_KEY)));
        }
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", false, "high");

        assertThatThrownBy(() -> complete(deepSeek, loopRequest(deepSeek, ToolChoice.AUTO)))
                .isInstanceOfSatisfying(ModelException.class, error -> {
                    assertThat(error.kind()).isEqualTo(kind);
                    assertThat(error.httpStatus()).isEqualTo(status);
                    assertThat(error.providerErrorType()).isEqualTo(type);
                    assertThat(error.getMessage()).contains("HTTP " + status)
                            .doesNotContain(BODY_SENTINEL).doesNotContain(API_KEY);
                });
        assertThat(provider.requests()).hasSize(requests);
        assertThat(recorder.last().attempts()).isEqualTo(requests);
        assertThat(recorder.last().errorKind()).isEqualTo(kind);
    }

    @Test
    void retryAfterIsHonoredAndTheRetrySucceeds() {
        provider.reply(Reply.json(429, ProviderPayloads.error("rate_limit_reached_error", "slow down"), "Retry-After", "1"));
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("ok", null)));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", false, "high");
        long started = System.nanoTime();

        ModelResponse response = complete(deepSeek, loopRequest(deepSeek, ToolChoice.AUTO));

        assertThat(response.content()).isEqualTo("ok");
        assertThat((System.nanoTime() - started) / 1_000_000).as("waited for Retry-After").isGreaterThanOrEqualTo(950);
        assertThat(provider.requests()).hasSize(2);
        assertThat(recorder.last().attempts()).isEqualTo(2);
        assertThat(recorder.last().succeeded()).isTrue();
    }

    @Test
    void aRetryAfterBeyondTheCapIsNotWaitedFor() {
        provider.reply(Reply.json(429, ProviderPayloads.error("rate_limit_reached_error", "slow down"), "Retry-After", "60"));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", false, "high");

        assertThatThrownBy(() -> complete(deepSeek, loopRequest(deepSeek, ToolChoice.AUTO)))
                .isInstanceOfSatisfying(ModelException.class,
                        error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.RATE_LIMITED));
        assertThat(provider.requests()).hasSize(1);
    }

    @Test
    void kimiQuotaExhaustionIsNotRetriedLikeARateLimit() {
        provider.reply(Reply.json(429, ProviderPayloads.error("exceeded_current_quota_error", "no balance")));
        ModelEndpointConfig kimi = endpoint(ProviderId.KIMI, provider.chatUrl(), "kimi-k3", true, "high");

        assertThatThrownBy(() -> complete(kimi, loopRequest(kimi, ToolChoice.AUTO)))
                .isInstanceOfSatisfying(ModelException.class, error -> {
                    assertThat(error.kind()).isEqualTo(ModelErrorKind.QUOTA_EXHAUSTED);
                    assertThat(error.getMessage()).doesNotContain(BODY_SENTINEL);
                });
        assertThat(provider.requests()).hasSize(1);
    }

    @Test
    void kimiGatewayTimeoutHtmlPageIsUnavailableAndRetried() {
        for (int i = 0; i < 3; i++) {
            provider.reply(Reply.html(504, "<html><body><h1>504 Gateway Time-out</h1></body></html>"));
        }
        ModelEndpointConfig kimi = endpoint(ProviderId.KIMI, provider.chatUrl(), "kimi-k3", true, "high");

        assertThatThrownBy(() -> complete(kimi, loopRequest(kimi, ToolChoice.AUTO)))
                .isInstanceOfSatisfying(ModelException.class, error -> {
                    assertThat(error.kind()).isEqualTo(ModelErrorKind.UNAVAILABLE);
                    assertThat(error.getMessage()).contains("HTTP 504").doesNotContain("<html>");
                    assertThat(error.providerErrorType()).isNull();
                });
        assertThat(provider.requests()).hasSize(3);
    }

    @Test
    void deepSeekThinkingRejectionBecomesThinkingUnavailable() {
        provider.reply(Reply.json(400, ProviderPayloads.error("invalid_request_error",
                "thinking mode is not supported for this model")));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", true, "high");

        assertThatThrownBy(() -> complete(deepSeek, loopRequest(deepSeek, ToolChoice.AUTO)))
                .isInstanceOfSatisfying(ModelException.class, error -> {
                    assertThat(error.kind()).isEqualTo(ModelErrorKind.THINKING_UNAVAILABLE);
                    assertThat(error.getMessage()).doesNotContain(BODY_SENTINEL);
                });
    }

    @Test
    void theThinkingFallbackTurnsThinkingOffForThatCallOnly() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("ok", null)));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", true, "high");

        complete(deepSeek, loopRequest(deepSeek, ToolChoice.AUTO).withThinkingDisabled());

        JsonNode body = provider.onlyRequest().json();
        assertThat(body.path("thinking").path("type").asString()).isEqualTo("disabled");
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.3);
    }

    @Test
    void structuredProfilesForceThinkingOffAndAJsonObject() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("{\"ok\":true}", null)));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", true, "max");

        complete(deepSeek, userOnly(deepSeek, CallProfile.STRUCTURED_SMALL));

        JsonNode body = provider.onlyRequest().json();
        assertThat(body.path("thinking").path("type").asString()).isEqualTo("disabled");
        assertThat(body.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(body.has("tools")).isFalse();
    }

    @Test
    void readTimeoutFailsTheCallOnceWithoutRetry() {
        provider.reply(Reply.delayed(1_500, Reply.json(200, ProviderPayloads.deepSeekText("late", null))));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", false, "high");
        var properties = fastProperties();
        properties.setTotalTimeout(java.time.Duration.ofMillis(300));
        long started = System.nanoTime();

        try (DefaultModelClient client = client(deepSeek, properties, recorder)) {
            assertThatThrownBy(() -> client.complete(loopRequest(deepSeek, ToolChoice.AUTO), CallOptions.of("test")))
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.TIMEOUT));
        }

        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_400);
        assertThat(provider.requests()).hasSize(1);
    }

    @Test
    void requiredToolChoiceFailsBeforeSendingWhereItIsNotNative() {
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", true, "high");

        assertThatThrownBy(() -> complete(deepSeek, loopRequest(deepSeek, ToolChoice.REQUIRED)))
                .isInstanceOfSatisfying(ModelException.class,
                        error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.CAPABILITY_UNSUPPORTED));
        assertThat(provider.requests()).isEmpty();
        assertThat(recorder.last().attempts()).isZero();
    }

    @Test
    void disabledOrKeylessEndpointsFailBeforeSending() {
        ModelEndpointConfig disabled = ModelEndpointConfig.builder(ProviderId.DEEPSEEK).baseUrl(provider.chatUrl())
                .apiKey(API_KEY).model("deepseek-v4-pro").enabled(false).build();
        ModelEndpointConfig keyless = ModelEndpointConfig.builder(ProviderId.DEEPSEEK).baseUrl(provider.chatUrl())
                .model("deepseek-v4-pro").build();

        for (ModelEndpointConfig unusable : List.of(disabled, keyless)) {
            assertThatThrownBy(() -> complete(unusable, userOnly(unusable, CallProfile.CHAT_STREAM)))
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.CONFIGURATION_INVALID));
        }
        assertThat(provider.requests()).isEmpty();
        assertThat(keyless.toString()).doesNotContain(API_KEY);
        assertThat(disabled.toString()).doesNotContain(API_KEY);
    }

    @Test
    void embeddingsGoToTheEmbeddingsEndpointWithTheConfiguredDimensions() {
        provider.reply(Reply.json(200, ProviderPayloads.embedding(8)));
        ModelEndpointConfig embedding = ModelEndpointConfig.builder(ProviderId.DASHSCOPE)
                .baseUrl(provider.baseUrl() + "/compatible-mode").apiKey(API_KEY).model("text-embedding-v4")
                .embeddingDimension(8).build();

        EmbeddingResponse response;
        try (DefaultModelClient client = client(embedding, fastProperties(), recorder)) {
            response = client.embed(EmbeddingRequest.of("EMBEDDING", "knapsack"), CallOptions.of("test"));
        }

        FakeProvider.RecordedRequest request = provider.onlyRequest();
        assertThat(request.path()).isEqualTo("/compatible-mode/v1/embeddings");
        JsonNode body = request.json();
        assertThat(body.path("model").asString()).isEqualTo("text-embedding-v4");
        assertThat(body.path("input").asString()).isEqualTo("knapsack");
        assertThat(body.path("encoding_format").asString()).isEqualTo("float");
        assertThat(body.path("dimensions").asInt()).isEqualTo(8);
        assertThat(response.first()).hasSize(8).startsWith(0.5, -0.25);
        assertThat(response.usage().promptTokens()).isEqualTo(5);
        assertThat(recorder.last().operation()).isEqualTo(ModelOperation.EMBED);
    }

    @Test
    void aChatOnlyProviderHasNoEmbeddings() {
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", false, "high");

        try (DefaultModelClient client = client(deepSeek, fastProperties())) {
            assertThatThrownBy(() -> client.embed(EmbeddingRequest.of("EMBEDDING", "x"), CallOptions.of("test")))
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.CAPABILITY_UNSUPPORTED));
        }
        assertThat(provider.requests()).isEmpty();
    }

    @Test
    void modelCatalogsAreReadFromEachProvidersPath() {
        String catalog = """
                {"object":"list","data":[{"id":"deepseek-v4-pro","object":"model","owned_by":"deepseek"},
                 {"id":"deepseek-v4-flash","object":"model","owned_by":"deepseek","context_length":131072}]}""";
        provider.reply(Reply.json(200, catalog));
        provider.reply(Reply.json(200, catalog));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", false, "high");
        ModelEndpointConfig kimi = endpoint(ProviderId.KIMI, provider.baseUrl() + "/v1/chat/completions", "kimi-k3",
                false, "high");
        ModelEndpointConfig dashScope = ModelEndpointConfig.builder(ProviderId.DASHSCOPE)
                .baseUrl(provider.baseUrl()).apiKey(API_KEY).model("text-embedding-v4").build();

        try (DefaultModelClient client = client(deepSeek, fastProperties())) {
            List<ModelInfo> models = client.listModels(deepSeek, CallOptions.of("test"));
            client.listModels(kimi, CallOptions.of("test"));
            assertThat(models).extracting(ModelInfo::id).containsExactly("deepseek-v4-pro", "deepseek-v4-flash");
            assertThat(models.get(1).contextLength()).isEqualTo(131072);
            assertThatThrownBy(() -> client.listModels(dashScope, CallOptions.of("test")))
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.CAPABILITY_UNSUPPORTED));
        }

        assertThat(provider.requests()).extracting(FakeProvider.RecordedRequest::method).containsExactly("GET", "GET");
        assertThat(provider.requests()).extracting(FakeProvider.RecordedRequest::path)
                .containsExactly("/models", "/v1/models");
    }

    @Test
    void everyCallIsReportedOnceWithItsUsageAndNoContent() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekToolCalls("need the statement")));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", true, "high");

        try (DefaultModelClient client = client(deepSeek, fastProperties(), recorder)) {
            client.complete(loopRequest(deepSeek, ToolChoice.AUTO),
                    CallOptions.of("tutor.turn").withCorrelation("turn-1", 42L));
        }

        assertThat(recorder.records).hasSize(1);
        ModelCallRecord record = recorder.last();
        assertThat(record.operation()).isEqualTo(ModelOperation.COMPLETE);
        assertThat(record.scope()).isEqualTo(SCOPE);
        assertThat(record.feature()).isEqualTo("tutor.turn");
        assertThat(record.provider()).isEqualTo(ProviderId.DEEPSEEK);
        assertThat(record.model()).isEqualTo("deepseek-v4-pro");
        assertThat(record.usage().cacheHitTokens()).isEqualTo(1024);
        assertThat(record.attempts()).isEqualTo(1);
        assertThat(record.correlationId()).isEqualTo("turn-1");
        assertThat(record.subjectUserId()).isEqualTo(42L);
        assertThat(record.succeeded()).isTrue();
        assertThat(record.toString()).doesNotContain(ContractFixtures.USER).doesNotContain("need the statement");
    }

    private ModelResponse complete(ModelEndpointConfig endpoint, com.aioj.next.ai.model.api.ModelRequest request) {
        try (DefaultModelClient client = client(endpoint, fastProperties(), recorder)) {
            return client.complete(request, CallOptions.of("test"));
        }
    }
}
