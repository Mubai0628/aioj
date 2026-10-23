package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ProviderId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CallSettingsTest {
    private final DeepSeekAdapter deepSeek = new DeepSeekAdapter();
    private final KimiAdapter kimi = new KimiAdapter();

    /** The T1.3 eval case: effort max with an 8,192 budget spent everything on reasoning. */
    @Test
    void deepSeekThinkingAtMaxEffortGetsRoomToAnswer() {
        ModelEndpointConfig endpoint = endpoint(ProviderId.DEEPSEEK, "deepseek-flash", "max", 8192);

        CallSettings settings = resolve(endpoint, request(endpoint, CallProfile.CHAT_STREAM, null), deepSeek);

        assertThat(settings.thinking()).isTrue();
        assertThat(settings.reasoningEffort()).isEqualTo("max");
        assertThat(settings.maxTokens()).isEqualTo(CallSettings.DEEPSEEK_MAX_EFFORT_MIN_TOKENS);
    }

    @Test
    void anExplicitSmallBudgetIsRaisedTooWhileThinkingAtMaxEffort() {
        ModelEndpointConfig endpoint = endpoint(ProviderId.DEEPSEEK, "deepseek-flash", "max", 8192);

        CallSettings settings = resolve(endpoint, request(endpoint, CallProfile.COMPLETION, 1800), deepSeek);

        assertThat(settings.maxTokens()).isEqualTo(CallSettings.DEEPSEEK_MAX_EFFORT_MIN_TOKENS);
    }

    @Test
    void aLargerConfiguredBudgetIsKept() {
        ModelEndpointConfig endpoint = endpoint(ProviderId.DEEPSEEK, "deepseek-flash", "max", 32768);

        assertThat(resolve(endpoint, request(endpoint, CallProfile.CHAT_STREAM, null), deepSeek).maxTokens())
                .isEqualTo(32768);
    }

    @Test
    void highEffortKeepsTheGeneralFloor() {
        ModelEndpointConfig endpoint = endpoint(ProviderId.DEEPSEEK, "deepseek-flash", "high", 8192);

        assertThat(resolve(endpoint, request(endpoint, CallProfile.CHAT_STREAM, null), deepSeek).maxTokens())
                .isEqualTo(8192);
        assertThat(resolve(endpoint, request(endpoint, CallProfile.COMPLETION, 1800), deepSeek).maxTokens())
                .isEqualTo(CallProfile.MIN_MAX_TOKENS_WHEN_THINKING);
    }

    @Test
    void withoutThinkingNoFloorApplies() {
        ModelEndpointConfig endpoint = endpoint(ProviderId.DEEPSEEK, "deepseek-flash", "max", 8192);

        CallSettings recovery = resolve(endpoint, request(endpoint, CallProfile.RECOVERY_ANSWER, null), deepSeek);
        CallSettings disabled = resolve(endpoint,
                request(endpoint, CallProfile.CHAT_STREAM, null).withThinkingDisabled(), deepSeek);

        assertThat(recovery.thinking()).isFalse();
        assertThat(recovery.maxTokens()).isEqualTo(8192);
        assertThat(disabled.thinking()).isFalse();
        assertThat(disabled.maxTokens()).isEqualTo(8192);
    }

    /** Kimi's output limits are not checked yet, so it keeps the general floor at any effort. */
    @Test
    void kimiKeepsTheGeneralFloorAtMaxEffort() {
        ModelEndpointConfig endpoint = endpoint(ProviderId.KIMI, "kimi-k3", "max", null);

        CallSettings settings = resolve(endpoint, request(endpoint, CallProfile.COMPLETION, 1800), kimi);

        assertThat(settings.thinking()).isTrue();
        assertThat(settings.maxTokens()).isEqualTo(CallProfile.MIN_MAX_TOKENS_WHEN_THINKING);
    }

    private static CallSettings resolve(ModelEndpointConfig endpoint, ModelRequest request, ChatAdapter adapter) {
        return CallSettings.resolve(endpoint, request, adapter.capabilities(endpoint.model()));
    }

    private static ModelEndpointConfig endpoint(ProviderId provider, String model, String effort, Integer maxTokens) {
        return ModelEndpointConfig.builder(provider).baseUrl("http://127.0.0.1:9/chat/completions").apiKey("test-key")
                .model(model).enabled(true).thinking(true, effort).maxTokens(maxTokens).build();
    }

    private static ModelRequest request(ModelEndpointConfig endpoint, CallProfile profile, Integer maxTokens) {
        return ModelRequest.builder("TEXT_GENERATION").endpoint(endpoint).messages(List.of(ChatMessage.user("hi")))
                .profile(profile).maxTokens(maxTokens).build();
    }
}
