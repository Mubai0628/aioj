package com.aioj.next.problem.domain.search;

import com.aioj.next.problem.config.PlagiarismProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemEmbeddingClientTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesTheVectorAndSendsThePurpose() throws IOException {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        serve(200, "{\"code\":0,\"data\":{\"embedding\":[0.5,-0.25],\"model\":\"embed-v4\",\"dimension\":2}}", request, token);

        var result = client().embed("迷宫最短路", "PROBLEM_QUERY");

        assertThat(result.failure()).isNull();
        assertThat(result.embedding().vector()).containsExactly(0.5f, -0.25f);
        assertThat(result.embedding().model()).isEqualTo("embed-v4");
        assertThat(request.get()).contains("\"purpose\":\"PROBLEM_QUERY\"").contains("迷宫最短路");
        assertThat(token.get()).isEqualTo("internal-token");
    }

    @Test
    void anApplicationErrorIsAFailureWithItsCode() throws IOException {
        serve(200, "{\"code\":50300,\"message\":\"busy\",\"data\":null}", new AtomicReference<>(), new AtomicReference<>());

        var result = client().embed("text", "PROBLEM_INDEX");

        assertThat(result.embedding()).isNull();
        assertThat(result.failure()).isEqualTo("ai-service error 50300");
    }

    @Test
    void anHttpErrorIsAFailureWithItsStatus() throws IOException {
        serve(503, "{\"code\":50300}", new AtomicReference<>(), new AtomicReference<>());

        assertThat(client().embed("text", "PROBLEM_INDEX").failure()).isEqualTo("HTTP 503");
    }

    private void serve(int status, String body, AtomicReference<String> request, AtomicReference<String> token)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/ai/embeddings", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private ProblemEmbeddingClient client() {
        PlagiarismProperties properties = new PlagiarismProperties();
        properties.setAiServiceUri("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setInternalApiToken("internal-token");
        return new ProblemEmbeddingClient(properties, JsonMapper.builder().build());
    }
}
