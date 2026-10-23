package com.aioj.next.ai.model.transport;

import com.aioj.next.ai.model.api.CancellationSignal;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ProviderId;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * HTTP transport on the JDK HttpClient (appendix G.4 item 1). Closing a stream's
 * body closes the connection, which is what stops the provider from generating
 * and billing a cancelled answer; T1.2a measured that Spring AI's clients did not.
 * Errors carry no response body and no URL query.
 */
public final class HttpTransport implements AutoCloseable {
    private static final int ERROR_BODY_LIMIT = 64 * 1024;
    private static final String CLOSED_BY_CANCEL = "cancel";
    private static final String CLOSED_BY_DEADLINE = "deadline";

    private final ExecutorService executor;
    private final ScheduledExecutorService watchdog;
    private final Duration connectTimeout;
    /** The JDK client fixes the connect timeout per client; the configs use only a few values. */
    private final ConcurrentMap<Duration, HttpClient> clients = new ConcurrentHashMap<>();

    public HttpTransport(Duration connectTimeout) {
        this.executor = Executors.newCachedThreadPool(daemonThreads("model-http"));
        this.watchdog = Executors.newSingleThreadScheduledExecutor(daemonThreads("model-stream-deadline"));
        this.connectTimeout = connectTimeout;
    }

    /**
     * One request to a provider. {@code body == null} sends a GET.
     *
     * @param connectTimeout the endpoint's own, or null for the transport's default
     */
    public record Exchange(URI uri, Map<String, String> headers, String body, ProviderId provider, String model,
                           Duration connectTimeout) {
        public Exchange(URI uri, Map<String, String> headers, String body, ProviderId provider, String model) {
            this(uri, headers, body, provider, model, null);
        }
    }

    /** Called for each event of a stream; it may throw to abort the stream. */
    @FunctionalInterface
    public interface EventConsumer {
        void accept(String data);
    }

    private HttpClient client(Exchange exchange) {
        Duration timeout = exchange.connectTimeout() == null ? connectTimeout : exchange.connectTimeout();
        return clients.computeIfAbsent(timeout, value -> HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(value)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build());
    }

    /** Sends the request and reads the whole answer within {@code timeout}. */
    public TransportResponse send(Exchange exchange, Duration timeout, CancellationSignal cancellation) {
        HttpRequest request = request(exchange, timeout);
        CompletableFuture<HttpResponse<String>> future =
                client(exchange).sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        CancellationSignal.Registration registration = onCancel(cancellation, () -> future.cancel(true));
        try {
            HttpResponse<String> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new TransportResponse(response.statusCode(), response.headers().map(), response.body(), false);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw timeout(exchange);
        } catch (CancellationException ex) {
            throw cancelled(exchange);
        } catch (ExecutionException ex) {
            if (cancellation != null && cancellation.isCancelled()) {
                throw cancelled(exchange);
            }
            throw failure(exchange, ex.getCause());
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw cancelled(exchange);
        } finally {
            registration.close();
        }
    }

    /**
     * Sends a streaming request and hands every event's data to the consumer until
     * {@code [DONE]} or the end of the stream. A non-2xx answer is returned with its
     * body instead. Cancellation and the stream deadline close the connection.
     *
     * @param outputStarted whether the consumer already produced output; failures after
     *                      that are marked so they are never retried
     */
    public TransportResponse stream(Exchange exchange, Duration firstByteTimeout, Instant streamDeadline,
                                    CancellationSignal cancellation, EventConsumer consumer,
                                    BooleanSupplier outputStarted) {
        HttpResponse<InputStream> response = awaitHeaders(exchange, firstByteTimeout, cancellation);
        InputStream body = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new TransportResponse(response.statusCode(), response.headers().map(), readLimited(body), false);
        }
        AtomicReference<String> closedBy = new AtomicReference<>();
        CancellationSignal.Registration registration = onCancel(cancellation, () -> {
            closedBy.compareAndSet(null, CLOSED_BY_CANCEL);
            closeQuietly(body);
        });
        ScheduledFuture<?> deadlineTask = streamDeadline == null ? null : watchdog.schedule(() -> {
            closedBy.compareAndSet(null, CLOSED_BY_DEADLINE);
            closeQuietly(body);
        }, Math.max(0, Duration.between(Instant.now(), streamDeadline).toMillis()), TimeUnit.MILLISECONDS);
        boolean done = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            SseEvents events = new SseEvents(reader);
            String data;
            while ((data = events.next()) != null) {
                if ("[DONE]".equals(data.strip())) {
                    done = true;
                    break;
                }
                consumer.accept(data);
            }
        } catch (IOException ex) {
            ModelException failure = switch (String.valueOf(closedBy.get())) {
                case CLOSED_BY_CANCEL -> cancelled(exchange);
                case CLOSED_BY_DEADLINE -> timeout(exchange);
                default -> unavailable(exchange, "AI provider stream was interrupted");
            };
            throw outputStarted.getAsBoolean() ? failure.afterOutput() : failure;
        } finally {
            registration.close();
            if (deadlineTask != null) {
                deadlineTask.cancel(false);
            }
        }
        if (!done && closedBy.get() != null) {
            ModelException failure = CLOSED_BY_CANCEL.equals(closedBy.get()) ? cancelled(exchange) : timeout(exchange);
            throw outputStarted.getAsBoolean() ? failure.afterOutput() : failure;
        }
        return new TransportResponse(response.statusCode(), response.headers().map(), "", done);
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        executor.shutdownNow();
    }

    private HttpResponse<InputStream> awaitHeaders(Exchange exchange, Duration timeout, CancellationSignal cancellation) {
        // No HttpRequest.timeout here: it must not cut a long stream short. The wait
        // for the headers is bounded by the future instead.
        HttpRequest request = request(exchange, null);
        CompletableFuture<HttpResponse<InputStream>> future =
                client(exchange).sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        CancellationSignal.Registration registration = onCancel(cancellation, () -> future.cancel(true));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw timeout(exchange);
        } catch (CancellationException ex) {
            throw cancelled(exchange);
        } catch (ExecutionException ex) {
            if (cancellation != null && cancellation.isCancelled()) {
                throw cancelled(exchange);
            }
            throw failure(exchange, ex.getCause());
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw cancelled(exchange);
        } finally {
            registration.close();
        }
    }

    private static HttpRequest request(Exchange exchange, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(exchange.uri());
        exchange.headers().forEach(builder::header);
        if (timeout != null) {
            builder.timeout(timeout);
        }
        if (exchange.body() == null) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(exchange.body(), StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private static CancellationSignal.Registration onCancel(CancellationSignal cancellation, Runnable action) {
        return cancellation == null ? () -> {
        } : cancellation.onCancel(action);
    }

    private static String readLimited(InputStream body) {
        try (body) {
            byte[] bytes = body.readNBytes(ERROR_BODY_LIMIT);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // Closing is the point; a failure to close cleanly changes nothing.
        }
    }

    private static ModelException failure(Exchange exchange, Throwable cause) {
        Throwable root = cause;
        while (root instanceof CompletionException && root.getCause() != null) {
            root = root.getCause();
        }
        if (root instanceof HttpConnectTimeoutException) {
            return unavailable(exchange, "AI provider did not accept the connection in time");
        }
        if (root instanceof HttpTimeoutException) {
            return timeout(exchange);
        }
        if (root instanceof ConnectException) {
            return unavailable(exchange, "AI provider refused the connection");
        }
        return unavailable(exchange, "AI provider request failed: " + root.getClass().getSimpleName());
    }

    private static ModelException timeout(Exchange exchange) {
        return new ModelException(ModelErrorKind.TIMEOUT, exchange.provider(), exchange.model(),
                "AI provider did not respond in time");
    }

    private static ModelException cancelled(Exchange exchange) {
        return new ModelException(ModelErrorKind.CANCELLED, exchange.provider(), exchange.model(),
                "AI model call was cancelled");
    }

    private static ModelException unavailable(Exchange exchange, String message) {
        return new ModelException(ModelErrorKind.UNAVAILABLE, exchange.provider(), exchange.model(), message);
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
