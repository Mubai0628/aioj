package com.aioj.next.ai.providercontract;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scripted OpenAI-compatible Provider on the JDK's built-in HTTP server (plan
 * T1.2a, §6.2). Every request takes the next scripted reply and is recorded as
 * the client sent it, so a contract test can check the wire format in both
 * directions over real HTTP, including timeouts, keep-alive and streams the
 * client abandons.
 */
final class FakeProvider implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger abandonedReplies = new AtomicInteger();
    private final AtomicInteger completedReplies = new AtomicInteger();

    FakeProvider() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Full endpoint, the way ai_model_configs stores it for the current adapters. */
    String chatUrl() {
        return baseUrl() + "/chat/completions";
    }

    FakeProvider reply(Reply reply) {
        replies.add(reply);
        return this;
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    RecordedRequest onlyRequest() {
        assertThat(requests).as("requests the client sent").hasSize(1);
        return requests.get(0);
    }

    /** Replies whose write failed because the client had already gone (cancel or timeout). */
    int abandonedReplies() {
        return abandonedReplies.get();
    }

    /** Replies written to the end; for a cancelled stream this means the client kept reading. */
    int completedReplies() {
        return completedReplies.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                headers, new String(body, StandardCharsets.UTF_8), exchange.getRemoteAddress().getPort()));
        Reply reply = replies.poll();
        if (reply == null) {
            reply = Reply.json(500, "{\"error\":{\"message\":\"no reply scripted\",\"type\":\"server_error\"}}");
        }
        try {
            reply.writeTo(exchange);
            completedReplies.incrementAndGet();
        } catch (IOException ex) {
            abandonedReplies.incrementAndGet();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    record RecordedRequest(String method, String path, Map<String, String> headers, String body, int remotePort) {
        JsonNode json() {
            return JSON.readTree(body);
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** One scripted response. */
    interface Reply {
        void writeTo(HttpExchange exchange) throws IOException, InterruptedException;

        static Reply json(int status, String body, String... headerPairs) {
            return exchange -> {
                addHeaders(exchange, headerPairs);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                send(exchange, status, body);
            };
        }

        static Reply html(int status, String body) {
            return exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                send(exchange, status, body);
            };
        }

        /** Server-sent events: each element is one {@code data:} payload, then {@code [DONE]}. */
        static Reply sse(List<String> events) {
            return sse(events, 0);
        }

        static Reply sse(List<String> events, long delayBetweenMillis) {
            return exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                for (String event : events) {
                    out.write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (delayBetweenMillis > 0) {
                        Thread.sleep(delayBetweenMillis);
                    }
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            };
        }

        /** Waits before replying, for read timeouts and cancellation. */
        static Reply delayed(long millis, Reply then) {
            return exchange -> {
                Thread.sleep(millis);
                then.writeTo(exchange);
            };
        }

        private static void addHeaders(HttpExchange exchange, String... headerPairs) {
            for (int i = 0; i + 1 < headerPairs.length; i += 2) {
                exchange.getResponseHeaders().set(headerPairs[i], headerPairs[i + 1]);
            }
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
