package com.aioj.next.common.error;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserErrorFeedbackTest {

    /** Plan 15.2: text AI is DeepSeek only since 2026-10-29, and every refusal says so the same way. */
    @ParameterizedTest
    @ValueSource(strings = {
            "Text AI provider kimi was retired on 2026-10-29; choose deepseek",
            "Text AI provider openai-compatible was retired on 2026-10-29; choose deepseek",
            "AI provider kimi was retired on 2026-10-29; use deepseek",
            "Unknown text AI provider zhipu; choose deepseek"
    })
    void retiredAndUnknownTextProvidersReadAsDeepSeekOnly(String raw) {
        UserErrorFeedback feedback = UserErrorFeedback.forDomain(ErrorCode.BAD_REQUEST, raw, "ai-service");

        assertThat(feedback.key()).isEqualTo("ai.providerUnsupported");
        assertThat(feedback.message()).isEqualTo("文本 AI 只能选择 DeepSeek（Kimi 已于 2026-10-29 停用）。");
    }
}
