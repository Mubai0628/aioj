package com.aioj.next.ai.agent.model;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.tool.ToolAuditLevel;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.common.error.DomainException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeepSeekToolCallAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private DeepSeekToolCallAdapter adapter;
    private final AtomicReference<String> recordedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new DeepSeekToolCallAdapter(objectMapper, builder.build());
        recordedBody.set(null);
    }

    @Test
    void thinkingEnabledBodyHasThinkingFlagNoTemperatureAndEffort() throws Exception {
        respondWithChat("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
        adapter.execute(config("deepseek-v4-pro"), new CallSettings(true, "high", 0.3, 4096), request(List.of()));

        JsonNode body = objectMapper.readTree(recordedBody.get());
        assertThat(body.path("thinking").path("type").asString()).isEqualTo("enabled");
        assertThat(body.path("reasoning_effort").asString()).isEqualTo("high");
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(body.path("stream").asBoolean()).isFalse();
    }

    @Test
    void thinkingDisabledBodyKeepsTemperature() throws Exception {
        respondWithChat("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
        adapter.execute(config("deepseek-v4-pro"), new CallSettings(false, "high", 0.3, 4096), request(List.of()));

        JsonNode body = objectMapper.readTree(recordedBody.get());
        assertThat(body.path("thinking").path("type").asString()).isEqualTo("disabled");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.3);
    }

    @Test
    void requiredToolChoiceIsRejectedBeforeAnyHttpCall() {
        assertThatThrownBy(() -> adapter.execute(config("deepseek-v4-pro"),
                new CallSettings(true, "high", 0.3, 4096), request(tools(), ToolChoiceMode.REQUIRED)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("tool_choice=required");
        assertThat(recordedBody.get()).isNull();
    }

    @Test
    void toolNamesAreWireEncodedInRequestAndDecodedInResponse() throws Exception {
        respondWithChat("{\"choices\":[{\"message\":{\"content\":null,\"reasoning_content\":\"private plan\",\"tool_calls\":["
                + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"context__search_exact\","
                + "\"arguments\":\"{\\\"exactTerms\\\":[\\\"二分\\\"]}\"}}]},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":7,\"prompt_cache_hit_tokens\":12}}");
        GatewayResponse response = adapter.execute(config("deepseek-v4-pro"),
                new CallSettings(false, "high", 0.3, 4096), request(tools(), ToolChoiceMode.AUTO));

        JsonNode body = objectMapper.readTree(recordedBody.get());
        assertThat(body.path("tools").get(0).path("function").path("name").asString())
                .isEqualTo("context__search_exact");
        assertThat(body.path("tool_choice").asString()).isEqualTo("auto");

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls().get(0).name()).isEqualTo("context.search_exact");
        assertThat(response.toolCalls().get(0).callId()).isEqualTo("call_1");
        assertThat(response.toolCalls().get(0).argumentsJson()).contains("二分");
        assertThat(response.promptTokens()).isEqualTo(20);
        assertThat(response.completionTokens()).isEqualTo(7);
        assertThat(response.cacheHitTokens()).isEqualTo(12);
        assertThat(response.reasoningContent()).isEqualTo("private plan");
    }

    @Test
    void reasoningContentIsEchoedOnlyOnAssistantMessageInsideToolLoop() throws Exception {
        respondWithChat("{\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}");
        GatewayToolCall call = new GatewayToolCall("call_1", "context.search_exact", "{}");
        GatewayRequest request = new GatewayRequest(List.of(
                GatewayMessage.user("question"),
                GatewayMessage.assistant("", "private chain", List.of(call)),
                GatewayMessage.toolResult("call_1", "{\"status\":\"SUCCESS\"}")),
                tools(), ToolChoiceMode.AUTO, CallProfile.CHAT_STREAM);

        adapter.execute(config("deepseek-v4-pro"), new CallSettings(true, "high", 0.3, 4096), request);

        JsonNode messages = objectMapper.readTree(recordedBody.get()).path("messages");
        assertThat(messages.get(0).has("reasoning_content")).isFalse();
        assertThat(messages.get(1).path("reasoning_content").asString()).isEqualTo("private chain");
        assertThat(messages.get(2).has("reasoning_content")).isFalse();
    }

    @Test
    void explicitThinkingCapabilityRejectionHasTypedFallbackSignal() {
        server.expect(request -> recordedBody.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withBadRequest().body("{\"error\":{\"message\":\"thinking is unsupported for this model\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.execute(config("deepseek-chat"),
                new CallSettings(true, "high", 0.3, 4096), request(List.of())))
                .isInstanceOf(ThinkingModeUnavailableException.class)
                .satisfies(error -> {
                    ThinkingModeUnavailableException capability = (ThinkingModeUnavailableException) error;
                    assertThat(capability.provider()).isEqualTo("deepseek");
                    assertThat(capability.httpStatus()).isEqualTo(400);
                });
    }

    @Test
    void curatorProfileForcesJsonObjectResponseFormat() throws Exception {
        respondWithChat("{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}");
        adapter.execute(config("deepseek-v4-pro"), new CallSettings(false, "high", 0.1, 4096),
                new GatewayRequest(List.of(GatewayMessage.user("整理")), List.of(), ToolChoiceMode.AUTO, CallProfile.CURATOR));

        JsonNode body = objectMapper.readTree(recordedBody.get());
        assertThat(body.path("response_format").path("type").asString()).isEqualTo("json_object");
    }

    @Test
    void chatStreamProfileDoesNotForceJsonOutput() throws Exception {
        respondWithChat("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
        adapter.execute(config("deepseek-v4-pro"), new CallSettings(false, "high", 0.3, 4096), request(List.of()));

        JsonNode body = objectMapper.readTree(recordedBody.get());
        assertThat(body.has("response_format")).isFalse();
    }

    private void respondWithChat(String json) {
        server.expect(request -> recordedBody.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private GatewayRequest request(List<ToolDescriptor> tools) {
        return request(tools, ToolChoiceMode.AUTO);
    }

    private GatewayRequest request(List<ToolDescriptor> tools, ToolChoiceMode mode) {
        return new GatewayRequest(List.of(GatewayMessage.user("你好")), tools, mode, CallProfile.CHAT_STREAM);
    }

    private List<ToolDescriptor> tools() throws Exception {
        JsonNode schema = objectMapper.readTree("""
                {"type":"object","additionalProperties":false,"required":["exactTerms"],
                 "properties":{"exactTerms":{"type":"array","items":{"type":"string","minLength":1}}}}
                """);
        return List.of(new ToolDescriptor("context.search_exact", "1.0.0", "search", schema,
                ToolRiskLevel.LOW, true, true, Set.of("AI_CHAT"), Set.of(DataClassification.USER_PRIVATE),
                2000, Duration.ofSeconds(5), ToolAuditLevel.FULL));
    }

    private AiModelEffectiveConfig config(String model) {
        return new AiModelEffectiveConfig(AiModelScope.TEXT_GENERATION, true, false, "DATABASE",
                "deepseek", "https://api.deepseek.com/chat/completions", "sk-test", "sk-***", "environment",
                "DEEPSEEK_API_KEY", model, false, true, "high", 0.3, 4096, null, null, null);
    }
}
