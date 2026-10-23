package com.aioj.next.ai.providercontract;

import com.aioj.next.ai.agent.model.CallSettings;
import com.aioj.next.ai.agent.model.DeepSeekToolCallAdapter;
import com.aioj.next.ai.agent.model.GatewayResponse;
import com.aioj.next.ai.agent.model.GatewayToolCall;
import com.aioj.next.ai.agent.model.KimiToolCallAdapter;
import com.aioj.next.ai.agent.model.ThinkingModeUnavailableException;
import com.aioj.next.ai.agent.model.ToolCallAdapter;
import com.aioj.next.ai.agent.model.ToolChoiceMode;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.providercontract.FakeProvider.Reply;
import com.aioj.next.common.error.DomainException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.aioj.next.ai.providercontract.ContractFixtures.API_KEY;
import static com.aioj.next.ai.providercontract.ContractFixtures.config;
import static com.aioj.next.ai.providercontract.ContractFixtures.loopRequest;
import static com.aioj.next.ai.providercontract.ProviderPayloads.BODY_SENTINEL;
import static com.aioj.next.ai.providercontract.WireAssertions.assertLoopMessages;
import static com.aioj.next.ai.providercontract.WireAssertions.assertToolsPassThrough;
import static com.aioj.next.ai.providercontract.WireAssertions.strictFlag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan T1.2a step 1: the contract baseline. The current non-streaming adapters
 * talk real HTTP to {@link FakeProvider}, which answers the way the official
 * docs describe. These tests pin today's wire behavior, including its known
 * gaps (marked "gap"), so the T1.2b adapters can be held to the same contract
 * and the gaps closed on purpose. Streaming has no current adapter; the
 * Spring AI gate tests cover the stream payloads.
 */
class LegacyAdapterContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeProvider provider = new FakeProvider();

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    @Test
    void deepSeekThinkingRequestHasTheOfficialShape() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("ok", "plan")));

        deepSeek(30_000).execute(config("deepseek", provider.chatUrl(), "deepseek-v4-pro", true, "max"),
                new CallSettings(true, "max", 0.3, 4096), loopRequest(ToolChoiceMode.AUTO));

        FakeProvider.RecordedRequest request = provider.onlyRequest();
        assertThat(request.path()).isEqualTo("/chat/completions");
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + API_KEY);
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

    @Test
    void kimiK3RequestHasTheOfficialShapeExceptTwoDeprecatedOrDiscouragedFields() {
        provider.reply(Reply.json(200, ProviderPayloads.kimiText("ok", "plan")));

        kimi(30_000).execute(config("kimi", provider.chatUrl(), "kimi-k3", true, "high"),
                new CallSettings(true, "high", 0.3, 4096), loopRequest(ToolChoiceMode.REQUIRED));

        JsonNode body = provider.onlyRequest().json();
        assertThat(body.path("model").asString()).isEqualTo("kimi-k3");
        assertThat(body.path("reasoning_effort").asString()).isEqualTo("high");
        assertThat(body.has("thinking")).as("K3 always thinks and takes no thinking field").isFalse();
        assertThat(body.path("tool_choice").asString()).isEqualTo("required");
        assertLoopMessages(body);
        assertToolsPassThrough(body);
        assertThat(strictFlag(body, 0)).isTrue();
        assertThat(strictFlag(body, 1)).isTrue();
        // gap: the K3 guide fixes temperature at 1.0 and asks callers not to send it,
        // and max_tokens is deprecated in favor of max_completion_tokens.
        assertThat(body.path("temperature").asDouble()).isEqualTo(1.0);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(body.has("max_completion_tokens")).isFalse();
    }

    @Test
    void deepSeekParallelToolCallsAreParsedWithReasoningAndCacheHits() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekToolCalls("need the statement")));

        GatewayResponse response = deepSeek(30_000).execute(
                config("deepseek", provider.chatUrl(), "deepseek-v4-pro", true, "high"),
                new CallSettings(true, "high", 0.3, 4096), loopRequest(ToolChoiceMode.AUTO));

        assertThat(response.content()).as("content: null becomes empty text").isEmpty();
        assertThat(response.reasoningContent()).isEqualTo("need the statement");
        assertThat(response.toolCalls()).containsExactly(
                new GatewayToolCall("call_1", "problem.read", ProviderPayloads.READ_ARGS),
                new GatewayToolCall("call_2", "context.search_exact", ProviderPayloads.SEARCH_ARGS));
        assertThat(response.finishReason()).isEqualTo("tool_calls");
        assertThat(response.promptTokens()).isEqualTo(1200);
        assertThat(response.completionTokens()).isEqualTo(80);
        assertThat(response.cacheHitTokens()).isEqualTo(1024);
    }

    @Test
    void kimiToolCallsAreParsedButCachedTokensAreDropped() {
        provider.reply(Reply.json(200, ProviderPayloads.kimiToolCalls("need the statement")));

        GatewayResponse response = kimi(30_000).execute(config("kimi", provider.chatUrl(), "kimi-k3", true, "high"),
                new CallSettings(true, "high", 0.3, 4096), loopRequest(ToolChoiceMode.AUTO));

        assertThat(response.reasoningContent()).isEqualTo("need the statement");
        assertThat(response.toolCalls()).extracting(GatewayToolCall::name)
                .containsExactly("problem.read", "context.search_exact");
        assertThat(response.promptTokens()).isEqualTo(1200);
        // gap: Kimi reports cache hits as cached_tokens; the adapter reads only
        // DeepSeek's prompt_cache_hit_tokens.
        assertThat(response.cacheHitTokens()).isZero();
    }

    @Test
    void finishReasonsOutsideTheOpenAiSetAreKeptAsText() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekFinishReason("insufficient_system_resource")));

        GatewayResponse response = deepSeek(30_000).execute(
                config("deepseek", provider.chatUrl(), "deepseek-v4-pro", false, "high"),
                new CallSettings(false, "high", 0.3, 4096), loopRequest(ToolChoiceMode.AUTO));

        assertThat(response.finishReason()).isEqualTo("insufficient_system_resource");
        assertThat(response.content()).isEqualTo("partial");
    }

    /**
     * gap: the Provider body reaches the exception message (redacted, cut at
     * 500 chars). Only a 401 hides it, and by accident: HttpURLConnection,
     * behind SimpleClientHttpRequestFactory, cannot read the body of a 401
     * answer to a streamed POST.
     */
    @ParameterizedTest(name = "HTTP {0} {1}")
    @CsvSource({
            "400, invalid_request_error,    true",
            "401, authentication_error,     false",
            "402, insufficient_balance,     true",
            "429, rate_limit_reached_error, true",
            "500, server_error,             true",
            "503, server_unavailable,       true"
    })
    void providerErrorsFailOnceWithoutRetry(int status, String type, boolean bodyInMessage) {
        provider.reply(Reply.json(status, ProviderPayloads.error(type, "rejected"), "Retry-After", "2"));

        assertThatThrownBy(() -> deepSeek(30_000).execute(
                config("deepseek", provider.chatUrl(), "deepseek-v4-pro", false, "high"),
                new CallSettings(false, "high", 0.3, 4096), loopRequest(ToolChoiceMode.AUTO)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("AI provider HTTP " + status)
                .satisfies(error -> assertThat(error.getMessage().contains(BODY_SENTINEL)).isEqualTo(bodyInMessage));
        assertThat(provider.requests()).as("no retry, Retry-After ignored").hasSize(1);
    }

    @Test
    void kimiGatewayTimeoutHtmlPageFailsCleanly() {
        provider.reply(Reply.html(504, "<html><body><h1>504 Gateway Time-out</h1></body></html>"));

        assertThatThrownBy(() -> kimi(30_000).execute(config("kimi", provider.chatUrl(), "kimi-k3", true, "high"),
                new CallSettings(true, "high", 0.3, 4096), loopRequest(ToolChoiceMode.AUTO)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("AI provider HTTP 504");
    }

    @Test
    void deepSeekThinkingRejectionBecomesThinkingModeUnavailable() {
        provider.reply(Reply.json(400, ProviderPayloads.error("invalid_request_error",
                "thinking mode is not supported for this model")));

        assertThatThrownBy(() -> deepSeek(30_000).execute(
                config("deepseek", provider.chatUrl(), "deepseek-v4-pro", true, "high"),
                new CallSettings(true, "high", 0.3, 4096), loopRequest(ToolChoiceMode.AUTO)))
                .isInstanceOf(ThinkingModeUnavailableException.class)
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(BODY_SENTINEL));
    }

    @Test
    void readTimeoutFailsTheCallOnceWithoutRetry() {
        provider.reply(Reply.delayed(1_500, Reply.json(200, ProviderPayloads.deepSeekText("late", null))));

        long started = System.nanoTime();
        assertThatThrownBy(() -> deepSeek(300).execute(
                config("deepseek", provider.chatUrl(), "deepseek-v4-pro", false, "high"),
                new CallSettings(false, "high", 0.3, 4096), loopRequest(ToolChoiceMode.AUTO)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("AI provider request failed");
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_400);
        assertThat(provider.requests()).hasSize(1);
    }

    private ToolCallAdapter deepSeek(int readTimeoutMillis) {
        return new DeepSeekToolCallAdapter(objectMapper, properties(readTimeoutMillis));
    }

    private ToolCallAdapter kimi(int readTimeoutMillis) {
        return new KimiToolCallAdapter(objectMapper, properties(readTimeoutMillis));
    }

    private static AiProperties properties(int readTimeoutMillis) {
        AiProperties properties = new AiProperties();
        properties.getHttp().setConnectTimeoutMs(2_000);
        properties.getHttp().setReadTimeoutMs(readTimeoutMillis);
        return properties;
    }
}
