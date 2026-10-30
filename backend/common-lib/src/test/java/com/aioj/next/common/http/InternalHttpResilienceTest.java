package com.aioj.next.common.http;

import com.aioj.next.common.resilience.CircuitBreaker;
import com.aioj.next.common.security.InternalServiceProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalHttpResilienceTest {
    private HttpServer server;
    private ExecutorService threads;
    private final InternalServiceProperties properties = new InternalServiceProperties();
    private final AtomicLong clock = new AtomicLong();

    @AfterEach
    void stop() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        if (server != null) { server.stop(0); }
        if (threads != null) { threads.shutdownNow(); }
    }

    @Test
    void onlyGetRetriesOnceFor502503504WhilePostAndBusinessFailuresNeverRetry() throws Exception {
        var requests = new AtomicInteger();
        var status = new AtomicInteger(503);
        start(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, status.get(), "reply");
        });
        var client = client(factory(), "read", Duration.ofSeconds(2));
        for (int code : new int[]{502, 503, 504}) {
            status.set(code);
            // Use distinct targets so repeated failures in this retry-only test do not trip a circuit.
            var independent = new InternalRestClientFactory(properties);
            var attempt = client(independent, "retry-" + code, Duration.ofSeconds(2));
            int before = requests.get();
            assertThatThrownBy(() -> attempt.get().uri("/test").retrieve().toBodilessEntity()).isInstanceOf(RestClientException.class);
            assertThat(requests.get() - before).isEqualTo(2);
        }
        status.set(503);
        int before = requests.get();
        assertThatThrownBy(() -> client.post().uri("/test").body("payload").retrieve().toBodilessEntity()).isInstanceOf(RestClientException.class);
        assertThat(requests.get() - before).isEqualTo(1);
        for (int code : new int[]{400, 401, 403, 404, 409, 429, 500}) {
            status.set(code);
            before = requests.get();
            assertThatThrownBy(() -> client.get().uri("/test").retrieve().toBodilessEntity()).isInstanceOf(RestClientException.class);
            assertThat(requests.get() - before).isEqualTo(1);
        }
    }

    @Test
    void readTimeoutUsesTheConfiguredProfileAndReleasesItsPermit() throws Exception {
        var requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            try { Thread.sleep(700); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            try { respond(exchange, 200, "late"); } catch (IOException ignored) { exchange.close(); }
        });
        var profile = new InternalHttpProperties.Client();
        profile.setConnectTimeout(Duration.ofMillis(200));
        profile.setReadTimeout(Duration.ofMillis(100));
        properties.getHttp().getClients().put("short", profile);
        var factory = factory();
        long started = System.nanoTime();
        assertThatThrownBy(() -> client(factory, "short", Duration.ofMinutes(3)).get().uri("/slow").retrieve().body(String.class))
                .isInstanceOf(RestClientException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(requests.get()).isEqualTo(2);
        assertThat(factory.snapshots().get("target").inUse()).isZero();
    }

    @Test
    void bodyStallsAreAlsoBoundedAndThePermitIsNotReleasedAtHeaders() throws Exception {
        var headers = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        start(exchange -> {
            exchange.sendResponseHeaders(200, 4);
            exchange.getResponseBody().write('a');
            exchange.getResponseBody().flush();
            headers.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var config = new InternalHttpProperties.Target();
        config.setBulkheadLimit(1);
        properties.getHttp().getTargets().put("target", config);
        var factory = factory();
        var first = client(factory, "first", Duration.ofMillis(300));
        var second = client(factory, "second", Duration.ofSeconds(1));
        var pending = threads.submit(() -> first.get().uri("/body").retrieve().body(String.class));
        try {
            assertThat(headers.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(factory.snapshots().get("target").inUse()).isEqualTo(1);
            long started = System.nanoTime();
            assertThatThrownBy(() -> second.get().uri("/body").retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class).hasMessageContaining("bulkhead_full");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(250));
            assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(RestClientException.class);
            assertThat(factory.snapshots().get("target").inUse()).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void circuitIsSharedAcrossProfilesAndAllowsOnlyOneHalfOpenProbe() throws Exception {
        var requests = new AtomicInteger();
        var status = new AtomicInteger(503);
        var probe = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        start(exchange -> {
            requests.incrementAndGet();
            if (status.get() == 200) {
                probe.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            respond(exchange, status.get(), "ok");
        });
        var factory = factory();
        var first = client(factory, "first", Duration.ofSeconds(3));
        var second = client(factory, "second", Duration.ofSeconds(3));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> first.post().uri("/bad").retrieve().toBodilessEntity()).isInstanceOf(RestClientException.class);
        }
        assertThat(factory.snapshots().get("target").circuit()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> second.get().uri("/bad").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class).hasMessageContaining("circuit_open");
        assertThat(requests.get()).isEqualTo(3);
        clock.addAndGet(Duration.ofSeconds(31).toNanos());
        status.set(200);
        var pending = threads.submit(() -> first.get().uri("/probe").retrieve().body(String.class));
        try {
            assertThat(probe.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(factory.snapshots().get("target").circuit()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
            assertThatThrownBy(() -> second.get().uri("/probe").retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class).hasMessageContaining("circuit_open");
            assertThat(requests.get()).isEqualTo(4);
        } finally { release.countDown(); }
        assertThat(pending.get(2, TimeUnit.SECONDS)).isEqualTo("ok");
        assertThat(factory.snapshots().get("target").circuit()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(factory.snapshots().get("target").inUse()).isZero();
    }

    @Test
    void aBusyTargetsRetryAfter503NeverOpensTheCircuitButAPlain503Does() throws Exception {
        var retryAfter = new AtomicBoolean(true);
        start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (retryAfter.get()) { exchange.getResponseHeaders().add("Retry-After", "1"); }
            respond(exchange, 503, "busy");
        });
        var factory = factory();
        var client = client(factory, "busy", Duration.ofSeconds(2));
        for (int i = 0; i < 25; i++) {
            assertThatThrownBy(() -> client.post().uri("/run").body("payload").retrieve().toBodilessEntity())
                    .isInstanceOf(RestClientException.class);
        }
        assertThat(factory.snapshots().get("target").circuit()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(factory.snapshots().get("target").inUse()).isZero();
        retryAfter.set(false);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.post().uri("/run").body("payload").retrieve().toBodilessEntity())
                    .isInstanceOf(RestClientException.class);
        }
        assertThat(factory.snapshots().get("target").circuit()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void transactionGuardWarnsAndCountsInProductionButErrorsBeforeNetworkInStrictMode() throws Exception {
        var requests = new AtomicInteger();
        var violations = new AtomicInteger();
        start(exchange -> { requests.incrementAndGet(); respond(exchange, 200, "ok"); });
        var factory = new InternalRestClientFactory(properties, target -> violations.incrementAndGet(), clock::get);
        var client = client(factory, "context", Duration.ofSeconds(1));
        assertThat(client.get().uri("/ok").retrieve().body(String.class)).isEqualTo("ok");
        assertThat(violations.get()).isZero();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(client.get().uri("/ok").retrieve().body(String.class)).isEqualTo("ok");
        assertThat(violations.get()).isEqualTo(1);
        properties.getHttp().setTransactionGuard(InternalHttpProperties.TransactionGuard.ERROR);
        assertThatThrownBy(() -> client.post().uri("/ok").retrieve().toBodilessEntity())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inside database transaction");
        assertThat(requests.get()).isEqualTo(2);
        assertThat(violations.get()).isEqualTo(2);
    }

    @Test
    void closingAResponseTwiceCannotLeakOrOverReleasePermits() throws Exception {
        start(exchange -> respond(exchange, 200, "ok"));
        var factory = factory();
        client(factory, "context", Duration.ofSeconds(1)).get().uri("/ok").exchange((request, response) -> {
            response.close();
            response.close();
            return null;
        });
        assertThat(factory.snapshots().get("target").inUse()).isZero();
    }

    private InternalRestClientFactory factory() { return new InternalRestClientFactory(properties, target -> { }, clock::get); }
    private RestClient client(InternalRestClientFactory factory, String id, Duration read) {
        return factory.create("target", "http://127.0.0.1:" + server.getAddress().getPort(), id, Duration.ofSeconds(1), read);
    }
    private void start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        threads = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads);
        server.createContext("/", handler);
        server.start();
    }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
