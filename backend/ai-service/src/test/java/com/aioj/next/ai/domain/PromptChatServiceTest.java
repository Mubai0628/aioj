package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer;
import com.aioj.next.ai.domain.context.AiIntentAnalysis;
import com.aioj.next.contract.ai.AiChatRequest;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The pre-V3 prompt pipeline: intent fallback, prompt assembly and degrade-to-empty memory extraction. */
class PromptChatServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PromptChatService provider;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        provider = new PromptChatService(properties, objectMapper, new ClarificationSchemaRepairer(),
                scope -> null, mock(ModelCompletions.class), new AiCapacityService(properties));
        AiFailureMetrics.reset();
    }

    @Test
    void defaultModelUsesDeepSeekV4Pro() {
        AiProperties properties = new AiProperties();

        assertThat(properties.getProvider()).isEqualTo("deepseek");
        assertThat(properties.getBaseUrl()).isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(properties.getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(properties.getIntent().getModel()).isBlank();
        assertThat(properties.getDeepseek().isThinkingEnabled()).isFalse();
        assertThat(properties.getDeepseek().getReasoningEffort()).isEqualTo("high");
    }

    @Test
    void applicationYamlUsesDeepSeekKeyAndIgnoresGenericAiKey() throws IOException {
        MockEnvironment environment = environmentFromApplicationYaml();

        assertThat(environment.getProperty("aioj.ai.api-key")).isEmpty();
        assertThat(environment.getProperty("aioj.ai.model")).isEqualTo("deepseek-v4-pro");

        environment.setProperty("DEEPSEEK_API_KEY", "deepseek-key");
        environment.setProperty("DEEPSEEK_MODEL", "deepseek-v4-flash");
        assertThat(environment.getProperty("aioj.ai.api-key")).isEqualTo("deepseek-key");
        assertThat(environment.getProperty("aioj.ai.model")).isEqualTo("deepseek-v4-flash");

        environment.setProperty("AI_API_KEY", "generic-key");
        environment.setProperty("AI_MODEL", "custom-model");
        assertThat(environment.getProperty("aioj.ai.api-key")).isEqualTo("deepseek-key");
        assertThat(environment.getProperty("aioj.ai.model")).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void fallbackIntentDetectsCompositeCodeAndExplanationRequest() throws Exception {
        Method method = PromptChatService.class.getDeclaredMethod("fallbackIntentAnalysis", AiChatRequest.class);
        method.setAccessible(true);

        AiIntentAnalysis analysis = (AiIntentAnalysis) method.invoke(provider,
                new AiChatRequest("c1", 1L, "能给我这道题你的解题思路和代码吗？按照你的思路讲解你的代码", "hint", null, null, null));

        assertThat(analysis.primaryIntent()).isEqualTo(AiIntentAnalysis.UserIntent.DIRECT_SOLUTION_WITH_CODE);
        assertThat(analysis.answerStyle()).isEqualTo(AiIntentAnalysis.AnswerStyle.CODE_FIRST_THEN_EXPLAIN);
        assertThat(analysis.wantsCodeFirst()).isTrue();
        assertThat(analysis.blocksHelpfulClarification()).isTrue();
    }

    @Test
    void dynamicPromptCarriesIntentAnalysisAsHiddenInstruction() throws Exception {
        Method intentMethod = PromptChatService.class.getDeclaredMethod("fallbackIntentAnalysis", AiChatRequest.class);
        intentMethod.setAccessible(true);
        Method promptMethod = PromptChatService.class.getDeclaredMethod("chatUserPrompt", AiChatRequest.class, AiChatContext.class, AiIntentAnalysis.class);
        promptMethod.setAccessible(true);
        AiChatRequest request = new AiChatRequest("c1", 1L, "能给我这道题你的解题思路和代码吗？按照你的思路讲解你的代码", "hint", null, null, null);
        AiIntentAnalysis analysis = (AiIntentAnalysis) intentMethod.invoke(provider, request);

        String prompt = (String) promptMethod.invoke(provider, request, AiChatContext.empty(), analysis);

        assertThat(prompt).contains("<INTENT_ANALYSIS>");
        assertThat(prompt).contains("answerStyle: CODE_FIRST_THEN_EXPLAIN");
        assertThat(prompt).contains("先给完整可提交代码");
        assertThat(prompt).contains("<STUDENT_QUESTION>");
    }

    @Test
    void contestPolicyPromptRemainsVisibleAlongsideCodeFirstIntent() throws Exception {
        Field systemPromptField = PromptChatService.class.getDeclaredField("CHAT_SYSTEM_PROMPT");
        systemPromptField.setAccessible(true);
        String systemPrompt = (String) systemPromptField.get(null);
        Method intentMethod = PromptChatService.class.getDeclaredMethod("fallbackIntentAnalysis", AiChatRequest.class);
        intentMethod.setAccessible(true);
        Method promptMethod = PromptChatService.class.getDeclaredMethod("chatUserPrompt", AiChatRequest.class, AiChatContext.class, AiIntentAnalysis.class);
        promptMethod.setAccessible(true);
        AiChatRequest request = new AiChatRequest("c1", 1L, "比赛中也请先给我完整代码和讲解", "hint", null, null, null);
        AiIntentAnalysis analysis = (AiIntentAnalysis) intentMethod.invoke(provider, request);
        AiChatContext context = new AiChatContext(
                "",
                "",
                "",
                "",
                """
                        [Contest AI policy]
                        activeContestProblem: true
                        allowFullCodeInResponse: false
                        allowOwnSubmissionCodeToAi: false
                        policyMessage: 比赛进行中只能提供思路、复杂度、边界情况和调试方向，不能提供完整可提交代码。
                        """
        );

        String prompt = (String) promptMethod.invoke(provider, request, context, analysis);

        assertThat(systemPrompt)
                .contains("比赛进行中只能提供思路")
                .contains("禁止输出完整可提交代码");
        assertThat(prompt)
                .contains("answerStyle: CODE_FIRST_THEN_EXPLAIN")
                .contains("先给完整可提交代码")
                .contains("allowFullCodeInResponse: false")
                .contains("不能提供完整可提交代码");
    }

    /** W1.6: a failing memory extraction is counted and still degrades to no memories. */
    @Test
    void extractMemoriesFailureIsCountedAndStillReturnsEmpty() {
        AiProperties properties = new AiProperties();
        ModelCompletions completions = mock(ModelCompletions.class);
        when(completions.complete(any(), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenThrow(new RuntimeException("provider boom"));
        PromptChatService failing = new PromptChatService(properties, objectMapper, new ClarificationSchemaRepairer(),
                scope -> memoryConfig(), completions, new AiCapacityService(properties));

        assertThat(failing.extractMemories("请记住我习惯用 Java 刷题。", "好的，已记住。")).isEmpty();
        assertThat(AiFailureMetrics.memoryExtractionFailures()).isEqualTo(1);
    }

    private static AiModelEffectiveConfig memoryConfig() {
        return new AiModelEffectiveConfig(AiModelScope.MEMORY_EXTRACTION, true, false, "test", "deepseek",
                "http://127.0.0.1:1", "test-key", "", "", "", "chat-model", true, false, "high", null, null, null,
                Instant.now(), null);
    }

    private MockEnvironment environmentFromApplicationYaml() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application.yml", new ClassPathResource("application.yml"));
        for (PropertySource<?> source : sources) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }
}
