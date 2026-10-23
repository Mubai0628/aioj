package com.aioj.next.ai.providercontract;

import com.aioj.next.ai.model.DefaultModelClient;
import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.CancellationSignal;
import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.model.api.ModelCallListener;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ModelStreamListener;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.api.ToolCall;
import com.aioj.next.ai.model.api.ToolChoice;
import com.aioj.next.ai.model.api.ToolSpec;
import com.aioj.next.ai.model.config.ModelClientProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan T1.2b, A1.3: real-provider smoke of the model call package. Billed, so it
 * runs only with {@code AIOJ_LIVE_PROVIDER_TESTS=true} and the provider's key, and
 * stops at the authorized budget (DeepSeek 8 calls, Kimi 4). Retries are off, so
 * every attempt is one counted call. Prints usage facts only, never content.
 */
@EnabledIfEnvironmentVariable(named = "AIOJ_LIVE_PROVIDER_TESTS", matches = "true")
class ModelClientLiveTest {
    static final int DEEPSEEK_BUDGET = 8;
    static final int KIMI_BUDGET = 4;
    private static final AtomicInteger DEEPSEEK_CALLS = new AtomicInteger();
    private static final AtomicInteger KIMI_CALLS = new AtomicInteger();

    private static final String TOOL_SYSTEM = "You are a programming tutor. Before answering any question about a "
            + "problem, call the problem.read tool with its id. Answer in one short sentence.";
    private static final ToolSpec READ = new ToolSpec("problem.read", "Read a problem statement by id.",
            ContractFixtures.schema("""
                    {"type":"object","additionalProperties":false,
                     "properties":{"id":{"type":"integer","minimum":1}},"required":["id"]}"""));

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void deepSeekThinkingToolLoopRunsEndToEnd() {
        ModelEndpointConfig deepSeek = deepSeek(true);
        try (DefaultModelClient client = client(DEEPSEEK_CALLS)) {
            Loop loop = toolLoop(client, DEEPSEEK_CALLS, deepSeek, ToolChoice.AUTO, 3);
            print("deepseek tool loop", loop.last, "steps=" + loop.steps + " toolCalls=" + loop.toolCalls
                    + " reasoningEchoed=" + loop.reasoningEchoed);
            assertThat(loop.toolCalls).as("the model used the tool").isPositive();
            assertThat(loop.last.content()).isNotBlank();
        }
        assertWithinBudget();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void deepSeekStreamsTextDeltasAndUsage() {
        ModelEndpointConfig deepSeek = deepSeek(false);
        List<String> deltas = new ArrayList<>();
        try (DefaultModelClient client = client(DEEPSEEK_CALLS)) {
            spend(DEEPSEEK_CALLS);
            ModelResponse response = client.stream(request(deepSeek, "Count from 1 to 20, separated by spaces.", 96),
                    CallOptions.of("live.stream"), new ModelStreamListener() {
                        @Override
                        public void onTextDelta(String delta) {
                            deltas.add(delta);
                        }
                    });
            print("deepseek stream", response, "deltas=" + deltas.size());
            assertThat(deltas.size()).isGreaterThan(1);
            assertThat(String.join("", deltas)).isEqualTo(response.content());
            assertThat(response.usage().reported()).isTrue();
        }
        assertWithinBudget();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void deepSeekStreamStopsAtOnceWhenCancelled() {
        ModelEndpointConfig deepSeek = deepSeek(false);
        CancellationSignal cancellation = new CancellationSignal();
        AtomicLong cancelledAt = new AtomicLong();
        StringBuilder received = new StringBuilder();
        try (DefaultModelClient client = client(DEEPSEEK_CALLS)) {
            spend(DEEPSEEK_CALLS);
            assertThatThrownBy(() -> client.stream(
                    request(deepSeek, "Write every integer from 1 to 3000, separated by spaces.", 4000),
                    CallOptions.of("live.cancel").withCancellation(cancellation), new ModelStreamListener() {
                        @Override
                        public void onTextDelta(String delta) {
                            received.append(delta);
                            if (received.length() >= 20 && cancelledAt.get() == 0) {
                                cancelledAt.set(System.nanoTime());
                                cancellation.cancel();
                            }
                        }
                    }))
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.CANCELLED));
        }
        long stopMillis = (System.nanoTime() - cancelledAt.get()) / 1_000_000;
        System.out.println("[live] deepseek cancel: chars before cancel=" + received.length()
                + " stoppedWithinMs=" + stopMillis);
        assertThat(stopMillis).isLessThan(1_000);
        assertWithinBudget();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void deepSeekReportsPrefixCacheHits() throws InterruptedException {
        ModelEndpointConfig deepSeek = deepSeek(false);
        String prefix = ("The judge compiles each submission with -O2 and runs it against hidden tests; "
                + "memory is measured as peak resident set size; time limits include start-up. ").repeat(40);
        try (DefaultModelClient client = client(DEEPSEEK_CALLS)) {
            ModelRequest request = ModelRequest.builder("TEXT_GENERATION").endpoint(deepSeek)
                    .message(ChatMessage.system(prefix)).message(ChatMessage.user("Reply with the word ok."))
                    .profile(CallProfile.RECOVERY_ANSWER).build();
            spend(DEEPSEEK_CALLS);
            ModelResponse first = client.complete(request, CallOptions.of("live.cache"));
            Thread.sleep(2_000);
            spend(DEEPSEEK_CALLS);
            ModelResponse second = client.complete(request, CallOptions.of("live.cache"));
            print("deepseek cache first", first, "");
            print("deepseek cache second", second, "");
            assertThat(second.usage().cacheHitTokens()).as("the repeated prefix is served from cache").isPositive();
        }
        assertWithinBudget();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KIMI_API_KEY", matches = ".+")
    void kimiToolLoopRunsEndToEndWithNativeRequired() {
        ModelEndpointConfig kimi = kimi();
        try (DefaultModelClient client = client(KIMI_CALLS)) {
            Loop loop = toolLoop(client, KIMI_CALLS, kimi, ToolChoice.REQUIRED, 2);
            print("kimi tool loop", loop.last, "steps=" + loop.steps + " toolCalls=" + loop.toolCalls
                    + " reasoningEchoed=" + loop.reasoningEchoed);
            assertThat(loop.toolCalls).isPositive();
            assertThat(loop.steps).isEqualTo(2);
            assertThat(loop.last.content()).isNotBlank();
        }
        assertWithinBudget();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KIMI_API_KEY", matches = ".+")
    void kimiStreamReportsUsage() {
        ModelEndpointConfig kimi = kimi();
        try (DefaultModelClient client = client(KIMI_CALLS)) {
            spend(KIMI_CALLS);
            ModelResponse response = client.stream(request(kimi, "Say hello in five words.", 1024),
                    CallOptions.of("live.stream"), ModelStreamListener.NONE);
            print("kimi stream", response, "");
            assertThat(response.usage().reported()).as("stream_options.include_usage").isTrue();
            assertThat(response.content()).isNotBlank();
        }
        assertWithinBudget();
    }

    /** Tool loop: the first step uses {@code firstChoice}, later steps AUTO; stops when the model answers. */
    private static Loop toolLoop(DefaultModelClient client, AtomicInteger counter, ModelEndpointConfig endpoint,
                                 ToolChoice firstChoice, int maxSteps) {
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system(TOOL_SYSTEM),
                ChatMessage.user("What are the limits of problem 7?")));
        Loop loop = new Loop();
        for (int step = 0; step < maxSteps; step++) {
            ModelRequest request = ModelRequest.builder("TEXT_GENERATION").endpoint(endpoint).messages(messages)
                    .tools(List.of(READ)).toolChoice(step == 0 ? firstChoice : ToolChoice.AUTO)
                    .profile(CallProfile.CHAT_STREAM).build();
            spend(counter);
            ModelResponse response = client.complete(request, CallOptions.of("live.tool-loop"));
            loop.steps++;
            loop.last = response;
            if (!response.hasToolCalls()) {
                break;
            }
            loop.toolCalls += response.toolCalls().size();
            loop.reasoningEchoed |= response.reasoningContent() != null && !response.reasoningContent().isBlank();
            messages.add(ChatMessage.assistant(response.content(), response.reasoningContent(), response.toolCalls()));
            for (ToolCall call : response.toolCalls()) {
                messages.add(ChatMessage.toolResult(call.id(),
                        "{\"id\":7,\"title\":\"Knapsack\",\"limits\":\"n <= 100, W <= 10^5, 1 second\"}"));
            }
        }
        return loop;
    }

    private static ModelRequest request(ModelEndpointConfig endpoint, String prompt, int maxTokens) {
        ModelEndpointConfig limited = new ModelEndpointConfig(endpoint.provider(), endpoint.baseUrl(), endpoint.apiKey(),
                endpoint.model(), true, true, endpoint.thinkingEnabled(), endpoint.reasoningEffort(), null, maxTokens,
                null);
        return ModelRequest.builder("TEXT_GENERATION").endpoint(limited).message(ChatMessage.user(prompt))
                .profile(CallProfile.CHAT_STREAM).build();
    }

    private static ModelEndpointConfig deepSeek(boolean thinking) {
        return ModelEndpointConfig.builder(ProviderId.DEEPSEEK)
                .baseUrl(env("AIOJ_LIVE_DEEPSEEK_BASE_URL", "https://api.deepseek.com/chat/completions"))
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                .model(env("AIOJ_LIVE_DEEPSEEK_MODEL", "deepseek-v4-flash"))
                .thinking(thinking, "high").maxTokens(2048).build();
    }

    private static ModelEndpointConfig kimi() {
        return ModelEndpointConfig.builder(ProviderId.KIMI)
                .baseUrl(env("AIOJ_LIVE_KIMI_BASE_URL", "https://api.moonshot.cn/v1/chat/completions"))
                .apiKey(System.getenv("KIMI_API_KEY"))
                .model(env("AIOJ_LIVE_KIMI_MODEL", "kimi-k3"))
                .thinking(true, "high").maxTokens(2048).build();
    }

    /** Counts every attempt against the budget. */
    private static DefaultModelClient client(AtomicInteger counter) {
        ModelClientProperties properties = new ModelClientProperties();
        properties.getRetry().setMaxRetries(0);
        properties.setTotalTimeout(Duration.ofSeconds(180));
        ModelCallListener counting = record -> counter.addAndGet(record.attempts());
        return new DefaultModelClient(scope -> {
            throw new IllegalStateException("live requests carry their endpoint");
        }, List.of(counting), properties);
    }

    /** Called before every live call: the budget is a hard stop. */
    private static void spend(AtomicInteger counter) {
        int budget = counter == DEEPSEEK_CALLS ? DEEPSEEK_BUDGET : KIMI_BUDGET;
        if (counter.get() >= budget) {
            throw new IllegalStateException("live call budget exhausted: " + counter.get() + "/" + budget);
        }
    }

    private static void assertWithinBudget() {
        System.out.println("[live] calls so far: deepseek=" + DEEPSEEK_CALLS.get() + "/" + DEEPSEEK_BUDGET
                + " kimi=" + KIMI_CALLS.get() + "/" + KIMI_BUDGET);
        assertThat(DEEPSEEK_CALLS.get()).isLessThanOrEqualTo(DEEPSEEK_BUDGET);
        assertThat(KIMI_CALLS.get()).isLessThanOrEqualTo(KIMI_BUDGET);
    }

    private static void print(String label, ModelResponse response, String extra) {
        System.out.println("[live] " + label + ": model=" + response.model() + " finish=" + response.finishReason()
                + " prompt=" + response.usage().promptTokens() + " completion=" + response.usage().completionTokens()
                + " cacheHit=" + response.usage().cacheHitTokens() + " reported=" + response.usage().reported()
                + (extra.isEmpty() ? "" : " " + extra));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static final class Loop {
        private int steps;
        private int toolCalls;
        private boolean reasoningEchoed;
        private ModelResponse last;
    }
}
