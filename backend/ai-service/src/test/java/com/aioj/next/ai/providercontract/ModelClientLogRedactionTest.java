package com.aioj.next.ai.providercontract;

import com.aioj.next.ai.logging.LogCanary;
import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.model.DefaultModelClient;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.providercontract.FakeProvider.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S5 log redaction on the model path (T2.5): whatever a call carries (the prompt, the answer, the
 * reasoning, the key) and whatever a Provider sends back when it fails stays out of the log.
 */
class ModelClientLogRedactionTest {
    private static final String PROMPT = "CANARY-PROMPT-6f1c";
    private static final String CODE = "int canaryCode7d2 = 42;";
    private static final String ANSWER = "CANARY-ANSWER-91ab";
    private static final String REASONING = "CANARY-THINK-38e0";
    /** Built at run time: a fake key, but a literal of that shape would trip the secret scan. */
    private static final String KEY = String.join("-", "sk", "canary", "redaction", "fake");
    private static final List<String> MARKERS = List.of(PROMPT, "canaryCode7d2", ANSWER, REASONING, "canary-redaction");

    private final FakeProvider provider = new FakeProvider();

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    @Test
    void anAnswerWithReasoningLeavesNothingOfTheCallInTheLog() {
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText(ANSWER, REASONING)));
        try (LogCanary log = new LogCanary(); DefaultModelClient client = client()) {
            assertThat(client.complete(request(), CallOptions.of("redaction")).content()).isEqualTo(ANSWER);

            assertClean(log);
        }
    }

    @Test
    void aProviderThatEchoesTheRequestInItsErrorDoesNotGetItIntoTheLog() {
        String echoed = "messages[0].content '" + PROMPT + " " + CODE + "' was rejected, key " + KEY;
        for (int i = 0; i < 4; i++) {
            provider.reply(Reply.json(400, ProviderPayloads.error("invalid_request_error", echoed)));
            provider.reply(Reply.json(503, ProviderPayloads.error("server_error", echoed)));
        }
        try (LogCanary log = new LogCanary(); DefaultModelClient client = client()) {
            assertThatThrownBy(() -> client.complete(request(), CallOptions.of("redaction")));
            assertThatThrownBy(() -> client.complete(request(), CallOptions.of("redaction")));

            assertThat(log.lines()).as("the failures were logged").isPositive();
            assertClean(log);
        }
    }

    @Test
    void anAnswerThatDoesNotParseIsNotQuotedInTheLog() {
        provider.reply(Reply.json(200, "{\"choices\":[{\"message\":{\"content\":\"" + ANSWER + "\" " + REASONING));
        try (LogCanary log = new LogCanary(); DefaultModelClient client = client()) {
            assertThatThrownBy(() -> client.complete(request(), CallOptions.of("redaction")));

            assertClean(log);
        }
    }

    private static void assertClean(LogCanary log) {
        String text = log.text();
        for (String marker : MARKERS) {
            assertThat(text).as("log must not contain %s", marker).doesNotContain(marker);
        }
    }

    private DefaultModelClient client() {
        return ModelContractFixtures.client(endpoint(), ModelContractFixtures.fastProperties());
    }

    private ModelEndpointConfig endpoint() {
        return ModelEndpointConfig.builder(ProviderId.DEEPSEEK).baseUrl(provider.chatUrl()).apiKey(KEY)
                .model("deepseek-v4-pro").thinking(true, "high").temperature(0.3).maxTokens(4096).build();
    }

    private ModelRequest request() {
        return ModelRequest.builder(ModelContractFixtures.SCOPE).endpoint(endpoint())
                .message(ChatMessage.system("You are a tutor."))
                .message(ChatMessage.user(PROMPT + "\n" + CODE))
                .profile(CallProfile.CHAT_STREAM).build();
    }
}
