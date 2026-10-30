package com.aioj.next.common.http;

import com.aioj.next.common.security.InternalServiceProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class InternalRestClientFactoryTest {
    private HttpServer server;
    @AfterEach
    void stop() { if (server != null) { server.stop(0); } }

    @Test
    void credentialsAreDirectionalAndCannotLeakToPublicOrOtherOrigins() throws Exception {
        var header = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var properties = new InternalServiceProperties();
        properties.getOutbound().computeIfAbsent("problem-service", ignored -> new InternalServiceProperties.Credential())
                .setToken("ai-to-problem-test");
        properties.getOutbound().computeIfAbsent("agent-executor", ignored -> new InternalServiceProperties.Credential())
                .setToken("ai-to-executor-test");
        var factory = new InternalRestClientFactory(properties);
        var client = factory.create("problem-service", base);
        client.get().uri("/api/v1/internal/ai/context").retrieve().toBodilessEntity();
        assertThat(header.get()).isEqualTo("ai-to-problem-test");
        factory.create("agent-executor", base).get().uri("/api/v1/internal/sandbox/compile").retrieve().toBodilessEntity();
        assertThat(header.get()).isEqualTo("ai-to-executor-test");
        client.get().uri("/api/v1/problems").header("X-Internal-Token", "must-remove").retrieve().toBodilessEntity();
        assertThat(header.get()).isNull();
        factory.create("problem-service", "http://other.invalid").get().uri(base + "/api/v1/internal/ai/context")
                .retrieve().toBodilessEntity();
        assertThat(header.get()).isNull();
    }

    @Test
    void theCallersTraceIdGoesToTheSameOriginOnly() throws Exception {
        var trace = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            trace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var properties = new InternalServiceProperties();
        properties.getOutbound().computeIfAbsent("problem-service", ignored -> new InternalServiceProperties.Credential())
                .setToken("ai-to-problem-test");
        var factory = new InternalRestClientFactory(properties);
        var client = factory.create("problem-service", base);
        try {
            MDC.put("traceId", "turn-trace-1");
            client.get().uri("/api/v1/internal/ai/context").retrieve().toBodilessEntity();
            assertThat(trace.get()).isEqualTo("turn-trace-1");
            client.get().uri("/api/v1/problems").header("X-Trace-Id", "explicit-1").retrieve().toBodilessEntity();
            assertThat(trace.get()).as("an id the client set itself is kept").isEqualTo("explicit-1");
            factory.create("problem-service", "http://other.invalid").get().uri(base + "/api/v1/internal/ai/context")
                    .retrieve().toBodilessEntity();
            assertThat(trace.get()).as("another origin gets no trace id").isNull();

            MDC.put("traceId", "bad\nid");
            client.get().uri("/api/v1/internal/ai/context").retrieve().toBodilessEntity();
            assertThat(trace.get()).isNull();
            MDC.remove("traceId");
            client.get().uri("/api/v1/internal/ai/context").retrieve().toBodilessEntity();
            assertThat(trace.get()).isNull();
        } finally {
            MDC.remove("traceId");
        }
    }
}
