package com.aioj.next.ai.providercontract;

import com.aioj.next.ai.model.DefaultModelClient;
import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.CancellationSignal;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ModelStreamListener;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.api.ToolCall;
import com.aioj.next.ai.model.api.ToolChoice;
import com.aioj.next.ai.providercontract.FakeProvider.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.aioj.next.ai.providercontract.ModelContractFixtures.client;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.endpoint;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.fastProperties;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.loopRequest;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.userOnly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan T1.2b, plan A1.4: streaming against {@link FakeProvider} with the payloads
 * T1.2a recorded from both providers' docs, and cancellation that really closes
 * the connection (appendix G.4 items 1 and 4).
 */
class ModelClientStreamContractTest {
    private final FakeProvider provider = new FakeProvider();
    private final ModelContractFixtures.Recorder recorder = new ModelContractFixtures.Recorder();

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    @Test
    void deepSeekToolStreamIsMergedByIndexWithReasoningAndUsageFromTheFinishChunk() {
        provider.reply(Reply.sse(ProviderPayloads.deepSeekToolStream()));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", true, "high");
        Listener listener = new Listener();

        ModelResponse response = stream(deepSeek, loopRequest(deepSeek, ToolChoice.AUTO), listener, null);

        JsonNode body = provider.onlyRequest().json();
        assertThat(body.path("stream").asBoolean(false)).isTrue();
        assertThat(body.has("stream_options")).as("DeepSeek reports usage without it").isFalse();
        assertThat(provider.onlyRequest().header("Accept")).isEqualTo("text/event-stream");
        assertThat(response.reasoningContent()).isEqualTo("Read the problem first.");
        assertThat(response.content()).isEmpty();
        assertThat(response.toolCalls()).containsExactly(
                new ToolCall("call_1", "problem.read", ProviderPayloads.READ_ARGS),
                new ToolCall("call_2", "context.search_exact", ProviderPayloads.SEARCH_ARGS));
        assertThat(response.finishReason()).isEqualTo("tool_calls");
        assertThat(response.usage().promptTokens()).isEqualTo(1200);
        assertThat(response.usage().cacheHitTokens()).isEqualTo(1024);
        assertThat(listener.toolCalls).containsExactly("0:problem.read", "1:context.search_exact");
        assertThat(listener.deltas).as("reasoning never reaches the listener").isEmpty();
    }

    @Test
    void kimiStreamAsksForUsageAndReadsItFromTheEmptyChoicesChunk() {
        provider.reply(Reply.sse(ProviderPayloads.kimiToolStream()));
        ModelEndpointConfig kimi = endpoint(ProviderId.KIMI, provider.chatUrl(), "kimi-k3", true, "high");

        ModelResponse response = stream(kimi, loopRequest(kimi, ToolChoice.AUTO), new Listener(), null);

        JsonNode body = provider.onlyRequest().json();
        assertThat(body.path("stream_options").path("include_usage").asBoolean(false)).isTrue();
        assertThat(response.toolCalls()).extracting(ToolCall::name)
                .containsExactly("problem.read", "context.search_exact");
        assertThat(response.usage().promptTokens()).isEqualTo(1200);
        assertThat(response.usage().completionTokens()).isEqualTo(80);
        assertThat(response.usage().cacheHitTokens()).isEqualTo(1024);
    }

    @Test
    void textDeltasReachTheListenerInOrder() {
        provider.reply(Reply.sse(ProviderPayloads.deepSeekTextStream()));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", true, "high");
        Listener listener = new Listener();

        ModelResponse response = stream(deepSeek, userOnly(deepSeek, CallProfile.CHAT_STREAM), listener, null);

        assertThat(listener.deltas).containsExactly("Hel", "lo");
        assertThat(response.content()).isEqualTo("Hello");
        assertThat(response.reasoningContent()).isEqualTo("Think.");
        assertThat(response.finishReason()).isEqualTo("stop");
    }

    /**
     * T1.2a showed Spring AI's clients either kept the socket or read the whole
     * stream after a cancel, so the provider kept generating. Here the server's
     * next write must fail: the connection is gone.
     */
    @Test
    void cancellingAStreamClosesTheConnection() throws InterruptedException {
        provider.reply(Reply.sse(ProviderPayloads.deepSeekLongTextStream(2_000), 2));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", false, "high");
        CancellationSignal cancellation = new CancellationSignal();
        Listener listener = new Listener() {
            @Override
            public void onTextDelta(String delta) {
                super.onTextDelta(delta);
                cancellation.cancel();
            }
        };
        long started = System.nanoTime();

        assertThatThrownBy(() -> stream(deepSeek, userOnly(deepSeek, CallProfile.CHAT_STREAM), listener, cancellation))
                .isInstanceOfSatisfying(ModelException.class, error -> {
                    assertThat(error.kind()).isEqualTo(ModelErrorKind.CANCELLED);
                    assertThat(error.outputStarted()).isTrue();
                });

        assertThat((System.nanoTime() - started) / 1_000_000).as("returns at once, not after 4 s of stream")
                .isLessThan(2_000);
        long until = System.nanoTime() + 5_000_000_000L;
        while (provider.abandonedReplies() == 0 && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertThat(provider.abandonedReplies()).as("the server's write failed: connection closed").isEqualTo(1);
        assertThat(provider.completedReplies()).isZero();
        assertThat(provider.requests()).as("a cancelled call is not retried").hasSize(1);
        assertThat(recorder.last().errorKind()).isEqualTo(ModelErrorKind.CANCELLED);
    }

    @Test
    void aStreamCutOffAfterOutputFailsWithoutRetry() {
        provider.reply(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(("data: " + ProviderPayloads.deepSeekTextStream().get(1) + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        });
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", false, "high");

        assertThatThrownBy(() -> stream(deepSeek, userOnly(deepSeek, CallProfile.CHAT_STREAM), new Listener(), null))
                .isInstanceOfSatisfying(ModelException.class, error -> {
                    assertThat(error.kind()).isEqualTo(ModelErrorKind.UNAVAILABLE);
                    assertThat(error.outputStarted()).isTrue();
                });
        assertThat(provider.requests()).hasSize(1);
    }

    @Test
    void aRateLimitedStreamIsRetriedBecauseNothingWasProduced() {
        provider.reply(Reply.json(429, ProviderPayloads.error("rate_limit_reached_error", "slow down")));
        provider.reply(Reply.sse(ProviderPayloads.deepSeekTextStream()));
        ModelEndpointConfig deepSeek = endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", true, "high");

        ModelResponse response = stream(deepSeek, userOnly(deepSeek, CallProfile.CHAT_STREAM), new Listener(), null);

        assertThat(response.content()).isEqualTo("Hello");
        assertThat(provider.requests()).hasSize(2);
        assertThat(recorder.last().attempts()).isEqualTo(2);
    }

    private ModelResponse stream(ModelEndpointConfig endpoint, com.aioj.next.ai.model.api.ModelRequest request,
                                 ModelStreamListener listener, CancellationSignal cancellation) {
        try (DefaultModelClient client = client(endpoint, fastProperties(), recorder)) {
            return client.stream(request, CallOptions.of("test").withCancellation(cancellation), listener);
        }
    }

    private static class Listener implements ModelStreamListener {
        final List<String> deltas = new CopyOnWriteArrayList<>();
        final List<String> toolCalls = new CopyOnWriteArrayList<>();

        @Override
        public void onTextDelta(String delta) {
            deltas.add(delta);
        }

        @Override
        public void onToolCallStarted(int index, String name) {
            toolCalls.add(index + ":" + name);
        }
    }
}
