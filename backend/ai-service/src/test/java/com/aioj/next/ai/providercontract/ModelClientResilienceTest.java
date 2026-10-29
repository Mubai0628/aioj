package com.aioj.next.ai.providercontract;

import com.aioj.next.ai.model.DefaultModelClient;
import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.CancellationSignal;
import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.config.ModelClientProperties;
import com.aioj.next.ai.providercontract.FakeProvider.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.aioj.next.ai.model.api.EndpointTuning;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.aioj.next.ai.providercontract.ModelContractFixtures.client;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.endpoint;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.fastProperties;
import static com.aioj.next.ai.providercontract.ModelContractFixtures.userOnly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan A1.5 fault injection: 429 and 5xx, slow answers, refused connections, the
 * circuit breaker opening and recovering through one probe, and one full scope
 * that leaves the others alone.
 */
class ModelClientResilienceTest {
    private final FakeProvider provider = new FakeProvider();
    private final ModelContractFixtures.Recorder recorder = new ModelContractFixtures.Recorder();

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    @Test
    void anUnavailableEndpointIsRetriedUntilItAnswers() {
        provider.reply(Reply.json(503, ProviderPayloads.error("server_unavailable", "busy")));
        provider.reply(Reply.json(502, "{}"));
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("ok", null)));
        ModelEndpointConfig deepSeek = deepSeek();

        try (DefaultModelClient client = client(deepSeek, fastProperties(), recorder)) {
            ModelResponse response = client.complete(userOnly(deepSeek, CallProfile.CHAT_STREAM), CallOptions.of("test"));
            assertThat(response.content()).isEqualTo("ok");
        }
        assertThat(provider.requests()).hasSize(3);
        assertThat(recorder.last().attempts()).isEqualTo(3);
    }

    @Test
    void aRefusedConnectionIsRetriedThenUnavailable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        ModelEndpointConfig nowhere = endpoint(ProviderId.DEEPSEEK, "http://127.0.0.1:" + closedPort + "/chat/completions",
                "deepseek-v4-pro", false, "high");
        // Windows answers a refused loopback connect only after its SYN retries; a short
        // connect timeout ends each attempt the same way.
        ModelClientProperties properties = fastProperties();
        properties.setConnectTimeout(Duration.ofMillis(500));

        try (DefaultModelClient client = client(nowhere, properties, recorder)) {
            assertThatThrownBy(() -> client.complete(userOnly(nowhere, CallProfile.CHAT_STREAM), CallOptions.of("test")))
                    .isInstanceOfSatisfying(ModelException.class, error -> {
                        assertThat(error.kind()).isEqualTo(ModelErrorKind.UNAVAILABLE);
                        assertThat(error.getMessage()).doesNotContain(String.valueOf(closedPort));
                    });
        }
        assertThat(recorder.last().attempts()).isEqualTo(3);
    }

    @Test
    void aSlowAnswerStopsAtTheCallersDeadline() {
        provider.reply(Reply.delayed(2_000, Reply.json(200, ProviderPayloads.deepSeekText("late", null))));
        ModelEndpointConfig deepSeek = deepSeek();
        long started = System.nanoTime();

        try (DefaultModelClient client = client(deepSeek, fastProperties(), recorder)) {
            assertThatThrownBy(() -> client.complete(userOnly(deepSeek, CallProfile.CHAT_STREAM),
                    CallOptions.of("test").withTimeout(Duration.ofMillis(300))))
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.TIMEOUT));
        }
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_500);
        assertThat(provider.requests()).hasSize(1);
    }

    @Test
    void theBreakerOpensAfterConsecutiveFailuresAndClosesAfterOneGoodProbe() throws InterruptedException {
        ModelClientProperties properties = fastProperties();
        properties.getRetry().setMaxRetries(0);
        properties.getBreaker().setConsecutiveFailures(3);
        properties.getBreaker().setOpenDuration(Duration.ofMillis(300));
        ModelEndpointConfig deepSeek = deepSeek();
        for (int i = 0; i < 3; i++) {
            provider.reply(Reply.json(503, ProviderPayloads.error("server_unavailable", "busy")));
        }

        try (DefaultModelClient client = client(deepSeek, properties, recorder)) {
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> call(client, deepSeek)).isInstanceOf(ModelException.class);
            }
            assertThatThrownBy(() -> call(client, deepSeek))
                    .as("open: fails fast without a request")
                    .isInstanceOfSatisfying(ModelException.class, error -> {
                        assertThat(error.kind()).isEqualTo(ModelErrorKind.UNAVAILABLE);
                        assertThat(error.getMessage()).contains("temporarily unavailable");
                    });
            assertThat(provider.requests()).hasSize(3);
            assertThat(client.textCircuitStates()).isNotEmpty().allSatisfy((scope, state) ->
                    assertThat(state).isEqualTo("OPEN"));
            assertThat(client.textCircuitStates().toString()).doesNotContain("http://", "https://");

            Thread.sleep(350);
            provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("probe", null)));
            provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("closed", null)));
            assertThat(call(client, deepSeek).content()).isEqualTo("probe");
            assertThat(call(client, deepSeek).content()).isEqualTo("closed");
            assertThat(client.textCircuitStates().values()).containsOnly("CLOSED");
        }
        assertThat(provider.requests()).hasSize(5);
    }

    @Test
    void aFailedProbeOpensTheBreakerAgain() throws InterruptedException {
        ModelClientProperties properties = fastProperties();
        properties.getRetry().setMaxRetries(0);
        properties.getBreaker().setConsecutiveFailures(1);
        properties.getBreaker().setOpenDuration(Duration.ofMillis(200));
        ModelEndpointConfig deepSeek = deepSeek();
        provider.reply(Reply.json(500, ProviderPayloads.error("server_error", "down")));
        provider.reply(Reply.json(500, ProviderPayloads.error("server_error", "still down")));

        try (DefaultModelClient client = client(deepSeek, properties, recorder)) {
            assertThatThrownBy(() -> call(client, deepSeek)).isInstanceOf(ModelException.class);
            Thread.sleep(250);
            assertThatThrownBy(() -> call(client, deepSeek)).as("the probe fails").isInstanceOf(ModelException.class);
            assertThatThrownBy(() -> call(client, deepSeek))
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.getMessage()).contains("temporarily unavailable"));
        }
        assertThat(provider.requests()).hasSize(2);
    }

    @Test
    void aClientErrorDoesNotOpenTheBreaker() {
        ModelClientProperties properties = fastProperties();
        properties.getBreaker().setConsecutiveFailures(2);
        ModelEndpointConfig deepSeek = deepSeek();
        for (int i = 0; i < 3; i++) {
            provider.reply(Reply.json(400, ProviderPayloads.error("invalid_request_error", "bad")));
        }
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("ok", null)));

        try (DefaultModelClient client = client(deepSeek, properties, recorder)) {
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> call(client, deepSeek))
                        .isInstanceOfSatisfying(ModelException.class,
                                error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.INVALID_REQUEST));
            }
            assertThat(call(client, deepSeek).content()).isEqualTo("ok");
        }
    }

    @Test
    void aFullScopeLeavesOtherScopesAlone() {
        ModelClientProperties properties = fastProperties();
        properties.getBulkhead().getScopes().put("PROBLEM_DRAFT", 1);
        properties.getBulkhead().setMaxWaiting(0);
        ModelEndpointConfig deepSeek = deepSeek();
        provider.reply(Reply.delayed(1_000, Reply.json(200, ProviderPayloads.deepSeekText("draft", null))));
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("tutor", null)));

        try (DefaultModelClient client = client(deepSeek, properties, recorder)) {
            CompletableFuture<ModelResponse> draft = CompletableFuture.supplyAsync(
                    () -> client.complete(request("PROBLEM_DRAFT", deepSeek), CallOptions.of("draft")));
            waitForRequests(1);

            assertThatThrownBy(() -> client.complete(request("PROBLEM_DRAFT", deepSeek), CallOptions.of("draft")))
                    .as("the draft scope is full and nobody may wait")
                    .isInstanceOfSatisfying(ModelException.class, error -> {
                        assertThat(error.kind()).isEqualTo(ModelErrorKind.UNAVAILABLE);
                        assertThat(error.getMessage()).contains("capacity");
                    });
            assertThat(client.complete(request("TEXT_GENERATION", deepSeek), CallOptions.of("tutor")).content())
                    .as("another scope is not blocked").isEqualTo("tutor");
            assertThat(draft.join().content()).isEqualTo("draft");
        }
        assertThat(provider.requests()).hasSize(2);
    }

    @Test
    void waitingForCapacityStopsAtTheDeadline() {
        ModelClientProperties properties = fastProperties();
        properties.getBulkhead().getScopes().put("REPORT_ANALYSIS", 1);
        ModelEndpointConfig deepSeek = deepSeek();
        provider.reply(Reply.delayed(1_000, Reply.json(200, ProviderPayloads.deepSeekText("first", null))));

        try (DefaultModelClient client = client(deepSeek, properties, recorder)) {
            CompletableFuture<ModelResponse> first = CompletableFuture.supplyAsync(
                    () -> client.complete(request("REPORT_ANALYSIS", deepSeek), CallOptions.of("report")));
            waitForRequests(1);
            long started = System.nanoTime();

            assertThatThrownBy(() -> client.complete(request("REPORT_ANALYSIS", deepSeek),
                    CallOptions.of("report").withTimeout(Duration.ofMillis(200))))
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.TIMEOUT));
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(800);
            assertThat(first.join().content()).isEqualTo("first");
        }
    }

    @Test
    void cancellingDuringTheBackoffStopsTheRetry() {
        ModelClientProperties properties = fastProperties();
        properties.getRetry().setInitialBackoff(Duration.ofSeconds(2));
        properties.getRetry().setMaxBackoff(Duration.ofSeconds(2));
        ModelEndpointConfig deepSeek = deepSeek();
        provider.reply(Reply.json(503, ProviderPayloads.error("server_unavailable", "busy")));
        CancellationSignal cancellation = new CancellationSignal();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        long started = System.nanoTime();

        try (DefaultModelClient client = client(deepSeek, properties, recorder)) {
            timer.schedule(cancellation::cancel, 200, TimeUnit.MILLISECONDS);
            assertThatThrownBy(() -> client.complete(userOnly(deepSeek, CallProfile.CHAT_STREAM),
                    CallOptions.of("test").withCancellation(cancellation)))
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.CANCELLED));
        } finally {
            timer.shutdownNow();
        }
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(900);
        assertThat(provider.requests()).hasSize(1);
    }

    /** Plan A6.2: saving clears the config cache, so the next call resolves and follows the new settings. */
    @Test
    void theNextCallFollowsASavedTimeoutAndRetryCount() {
        AtomicReference<ModelEndpointConfig> saved = new AtomicReference<>(deepSeek());
        provider.reply(Reply.delayed(400, Reply.json(200, ProviderPayloads.deepSeekText("in time", null))));
        provider.reply(Reply.delayed(400, Reply.json(200, ProviderPayloads.deepSeekText("late", null))));
        provider.reply(Reply.json(503, ProviderPayloads.error("server_unavailable", "busy")));

        try (DefaultModelClient client = new DefaultModelClient(scope -> saved.get(), List.of(recorder),
                fastProperties())) {
            assertThat(client.complete(resolved("TEXT_GENERATION"), CallOptions.of("test")).content())
                    .isEqualTo("in time");

            saved.set(tuned(new EndpointTuning(null, null, Duration.ofMillis(150), null, 0, null, null)));
            assertThatThrownBy(() -> client.complete(resolved("TEXT_GENERATION"), CallOptions.of("test")))
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.TIMEOUT));

            saved.set(tuned(new EndpointTuning(null, null, null, null, 0, null, null)));
            assertThatThrownBy(() -> client.complete(resolved("TEXT_GENERATION"), CallOptions.of("test")))
                    .as("no retries left for the 503")
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.UNAVAILABLE));
        }
        assertThat(provider.requests()).hasSize(3);
        assertThat(recorder.last().attempts()).isEqualTo(1);
    }

    @Test
    void aSavedConcurrencyCountsForTheNextPermit() {
        ModelClientProperties properties = fastProperties();
        properties.getBulkhead().setMaxWait(Duration.ofMillis(100));
        AtomicReference<ModelEndpointConfig> saved = new AtomicReference<>(
                tuned(new EndpointTuning(null, null, null, 1, null, null, null)));
        provider.reply(Reply.delayed(800, Reply.json(200, ProviderPayloads.deepSeekText("first", null))));
        provider.reply(Reply.json(200, ProviderPayloads.deepSeekText("second", null)));

        try (DefaultModelClient client = new DefaultModelClient(scope -> saved.get(), List.of(recorder), properties)) {
            CompletableFuture<ModelResponse> first = CompletableFuture.supplyAsync(
                    () -> client.complete(resolved("TEXT_GENERATION"), CallOptions.of("test")));
            waitForRequests(1);
            assertThatThrownBy(() -> client.complete(resolved("TEXT_GENERATION"), CallOptions.of("test")))
                    .as("one permit, and it is taken")
                    .isInstanceOfSatisfying(ModelException.class,
                            error -> assertThat(error.getMessage()).contains("capacity"));

            saved.set(tuned(new EndpointTuning(null, null, null, 2, null, null, null)));
            assertThat(client.complete(resolved("TEXT_GENERATION"), CallOptions.of("test")).content())
                    .isEqualTo("second");
            assertThat(first.join().content()).isEqualTo("first");
        }
    }

    private ModelEndpointConfig tuned(EndpointTuning tuning) {
        ModelEndpointConfig base = deepSeek();
        return new ModelEndpointConfig(base.provider(), base.baseUrl(), base.apiKey(), base.model(), base.enabled(),
                base.jsonOutputEnabled(), base.thinkingEnabled(), base.reasoningEffort(), base.temperature(),
                base.maxTokens(), base.embeddingDimension(), tuning);
    }

    /** A request without an endpoint: the client resolves the scope's current configuration. */
    private static ModelRequest resolved(String scope) {
        return ModelRequest.builder(scope).message(ChatMessage.user("hi")).build();
    }

    private ModelEndpointConfig deepSeek() {
        return endpoint(ProviderId.DEEPSEEK, provider.chatUrl(), "deepseek-v4-pro", false, "high");
    }

    private static ModelResponse call(DefaultModelClient client, ModelEndpointConfig endpoint) {
        return client.complete(userOnly(endpoint, CallProfile.CHAT_STREAM), CallOptions.of("test"));
    }

    private static ModelRequest request(String scope, ModelEndpointConfig endpoint) {
        return ModelRequest.builder(scope).endpoint(endpoint).message(ChatMessage.user("hi")).build();
    }

    private void waitForRequests(int count) {
        long until = System.nanoTime() + 5_000_000_000L;
        while (provider.requests().size() < count && System.nanoTime() < until) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(provider.requests()).hasSizeGreaterThanOrEqualTo(count);
    }
}
