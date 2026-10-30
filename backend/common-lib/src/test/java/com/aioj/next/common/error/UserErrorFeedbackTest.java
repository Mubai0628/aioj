package com.aioj.next.common.error;

import org.junit.jupiter.api.Test;
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

    /** Plan S7: a stored error is a key; the failure's own text never reaches the column or the client. */
    @Test
    void aFailureIsStoredAsItsKey() {
        assertThat(UserErrorFeedback.storedKey(new DomainException(ErrorCode.TOO_MANY_REQUESTS, "AI quota exceeded")))
                .isEqualTo("ai.quotaExceeded");
        assertThat(UserErrorFeedback.storedKey(new DomainException(ErrorCode.INTERNAL_ERROR,
                "AI problem context failed: I/O error on GET request for \"http://problem-service:8082/x\"")))
                .isEqualTo("system.internal");
        assertThat(UserErrorFeedback.storedKey(new IllegalStateException("wrapped",
                new java.net.SocketTimeoutException("Read timed out")))).isEqualTo("request.timeout");
        assertThat(UserErrorFeedback.storedKey(new IllegalStateException("canary-5c1e at db-host:3306")))
                .isEqualTo("system.internal");
    }

    @Test
    void storedTextIsHandedOutAsAKeyOnly() {
        assertThat(UserErrorFeedback.storedKey("judge.sandboxUnavailable", "judge.systemError"))
                .isEqualTo("judge.sandboxUnavailable");
        assertThat(UserErrorFeedback.storedKey("Sandbox execution failed: Sandbox HTTP 500 on POST /run",
                "judge.systemError")).isEqualTo("judge.systemError");
        assertThat(UserErrorFeedback.storedKey("AI provider call failed: upstream said no", "ai.providerFailed"))
                .isEqualTo("ai.providerFailed");
        assertThat(UserErrorFeedback.storedKey("Read timed out", "judge.systemError")).isEqualTo("request.timeout");
        assertThat(UserErrorFeedback.storedKey(" ", "judge.systemError")).isNull();
        assertThat(UserErrorFeedback.isKey("request.timeout")).isTrue();
        assertThat(UserErrorFeedback.isKey("TESTCASE_PACKAGE_FORMAT_INVALID")).isFalse();
        assertThat(UserErrorFeedback.isKey("a b.c")).isFalse();
    }

    @Test
    void theUsersOwnInputIsShownShortAndWithoutControlCharacters() {
        UserErrorFeedback feedback = UserErrorFeedback.forDomain(ErrorCode.BAD_REQUEST,
                "Unsafe testcase zip path: ../x\n\u0007" + "y".repeat(200), "problem-service");

        assertThat(feedback.key()).isEqualTo("testcase.unsafePath");
        assertThat(feedback.params().get("path")).hasSize(120).doesNotContain("\n", "\u0007").startsWith("../x");
    }
}
