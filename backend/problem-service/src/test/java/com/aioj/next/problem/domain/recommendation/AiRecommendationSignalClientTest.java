package com.aioj.next.problem.domain.recommendation;

import com.aioj.next.problem.config.PlagiarismProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiRecommendationSignalClientTest {
    private static com.aioj.next.common.http.InternalRestClientFactory clientFactory() {
        var credentials = new com.aioj.next.common.security.InternalServiceProperties();
        credentials.getOutbound().computeIfAbsent("ai-service", ignored ->
                new com.aioj.next.common.security.InternalServiceProperties.Credential()).setToken("internal-token");
        return new com.aioj.next.common.http.InternalRestClientFactory(credentials);
    }
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsSignalsWithTheInternalToken() throws IOException {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        serve(200, """
                {"code":0,"message":"ok","data":[
                  {"profileKey":"binary_search","label":"二分查找","confidence":0.81,"evidenceCount":4},
                  {"profileKey":"","label":"blank keys are dropped","confidence":0.9,"evidenceCount":1},
                  {"profileKey":"dp","label":null,"confidence":null,"evidenceCount":null}
                ]}
                """, 0, token, query);

        var signals = client().weaknesses(7L, 20);

        assertThat(token.get()).isEqualTo("internal-token");
        assertThat(query.get()).isEqualTo("limit=20");
        assertThat(signals).hasSize(2);
        assertThat(signals.get(0).profileKey()).isEqualTo("binary_search");
        assertThat(signals.get(0).confidence()).isEqualTo(0.81);
        assertThat(signals.get(1).profileKey()).isEqualTo("dp");
        assertThat(signals.get(1).confidence()).isZero();
    }

    @Test
    void applicationErrorMeansNoSignals() throws IOException {
        serve(200, "{\"code\":50300,\"message\":\"busy\",\"data\":null}", 0, new AtomicReference<>(), new AtomicReference<>());

        assertThat(client().weaknesses(7L, 20)).isEmpty();
    }

    @Test
    void slowServiceTimesOutToNoSignals() throws IOException {
        serve(200, "{\"code\":0,\"data\":[]}", 3000, new AtomicReference<>(), new AtomicReference<>());

        long started = System.nanoTime();
        assertThat(client().weaknesses(7L, 20)).isEmpty();
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3500);
    }

    @Test
    void unreachableServiceMeansNoSignals() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        PlagiarismProperties properties = new PlagiarismProperties();
        properties.setAiServiceUri("http://127.0.0.1:" + closedPort);

        assertThat(new AiRecommendationSignalClient(properties, JsonMapper.builder().build(), clientFactory()).weaknesses(7L, 20)).isEmpty();
    }

    private void serve(int status, String body, long delayMillis, AtomicReference<String> token,
                       AtomicReference<String> query) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/ai/users/7/recommendation-signals", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            query.set(exchange.getRequestURI().getRawQuery());
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        // Handlers off the dispatcher thread, so stop() does not wait out a slow one.
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    private AiRecommendationSignalClient client() {
        PlagiarismProperties properties = new PlagiarismProperties();
        properties.setAiServiceUri("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        return new AiRecommendationSignalClient(properties, JsonMapper.builder().build(), clientFactory());
    }
}
