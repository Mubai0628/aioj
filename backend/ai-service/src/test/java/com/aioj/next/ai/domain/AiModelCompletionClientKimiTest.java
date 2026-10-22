package com.aioj.next.ai.domain;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Kimi request options must match what KimiToolCallAdapter sends for the same models. */
class AiModelCompletionClientKimiTest {
    private final AiModelCompletionClient client = new AiModelCompletionClient(new ObjectMapper(), RestClient.builder().build());
    private final List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", "hi"));

    @Test
    void k3AlwaysThinksWithAnEffortLevelAndTemperatureOne() {
        Map<String, Object> off = client.completionBody(messages, 0.3, 0, config("kimi-k3", false, "high"), false, null);
        Map<String, Object> max = client.completionBody(messages, 0.3, 0, config("kimi-k3-preview", true, "max"), false, null);

        assertThat(off).doesNotContainKey("thinking");
        assertThat(off).containsEntry("reasoning_effort", "high").containsEntry("temperature", 1.0);
        assertThat(max).containsEntry("reasoning_effort", "max").containsEntry("temperature", 1.0);
    }

    @Test
    void k26KeepsItsThinkingSwitch() {
        Map<String, Object> on = client.completionBody(messages, 0.3, 0, config("kimi-k2.6", true, "high"), false, null);
        Map<String, Object> off = client.completionBody(messages, 0.3, 0, config("kimi-k2.6", false, "high"), false, null);

        assertThat(on).containsEntry("thinking", Map.of("type", "enabled")).containsEntry("temperature", 1.0);
        assertThat(off).containsEntry("thinking", Map.of("type", "disabled")).containsEntry("temperature", 0.6);
        assertThat(on).doesNotContainKey("reasoning_effort");
    }

    private static AiModelEffectiveConfig config(String model, boolean thinking, String effort) {
        return new AiModelEffectiveConfig(AiModelScope.TEXT_GENERATION, true, false, "test", "moonshot",
                "https://api.moonshot.cn/v1/chat/completions", "test-key", null, "environment", "MOONSHOT_API_KEY",
                model, true, thinking, effort, null, null, null, null, null);
    }
}
