package com.aioj.next.common.http;

import com.aioj.next.common.api.TraceIds;
import com.aioj.next.common.resilience.CircuitBreaker;
import com.aioj.next.common.resilience.CircuitBreaker.Outcome;
import com.aioj.next.common.security.InternalServiceProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Service-scoped state: credentials per direction, transport settings per client, protection per target. */
public class InternalRestClientFactory {
    public static final String TOKEN_HEADER = "X-Internal-Token";
    private static final Logger log = LoggerFactory.getLogger(InternalRestClientFactory.class);
    private final InternalServiceProperties properties;
    private final Consumer<String> transactionCounter;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final LongSupplier nanoClock;
    private final Map<String, TargetState> targets = new ConcurrentHashMap<>();
    private final Map<TransportKey, HttpClient> transports = new ConcurrentHashMap<>();

    public InternalRestClientFactory(InternalServiceProperties properties) {
        this(properties, target -> { });
    }

    public InternalRestClientFactory(InternalServiceProperties properties, Consumer<String> transactionCounter) {
        this(properties, transactionCounter, System::nanoTime);
    }

    /**
     * What the services wire (T2.5): every call times into {@code http.client.requests}, and each
     * target's breaker state, rejected calls and in-transaction calls are meters. Either may be null.
     */
    public InternalRestClientFactory(InternalServiceProperties properties, MeterRegistry meters,
                                     ObservationRegistry observations) {
        this(properties, meters == null ? target -> { }
                        : target -> meters.counter("aioj.internal.http.transaction", "target", target).increment(),
                meters, observations, System::nanoTime);
    }

    InternalRestClientFactory(InternalServiceProperties properties, Consumer<String> transactionCounter,
                              LongSupplier nanoClock) {
        this(properties, transactionCounter, null, null, nanoClock);
    }

    InternalRestClientFactory(InternalServiceProperties properties, Consumer<String> transactionCounter,
                              MeterRegistry meters, ObservationRegistry observations, LongSupplier nanoClock) {
        this.properties = properties;
        this.transactionCounter = transactionCounter;
        this.meters = meters;
        this.observations = observations;
        this.nanoClock = nanoClock;
    }

    public RestClient create(String target, String baseUrl) {
        return create(target, baseUrl, target, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    public RestClient create(String target, String baseUrl, Duration connectTimeout, Duration readTimeout) {
        return create(target, baseUrl, target, connectTimeout, readTimeout);
    }

    public RestClient create(String target, String baseUrl, String clientId,
                             Duration connectTimeout, Duration readTimeout) {
        var configured = properties.getHttp().getClients().get(clientId);
        Duration connect = positive(configured == null || configured.getConnectTimeout() == null
                ? connectTimeout : configured.getConnectTimeout());
        Duration read = positive(configured == null || configured.getReadTimeout() == null
                ? readTimeout : configured.getReadTimeout());
        var http = transports.computeIfAbsent(new TransportKey(target, connect), ignored -> HttpClient.newBuilder()
                .connectTimeout(connect).followRedirects(HttpClient.Redirect.NEVER).build());
        // Spring's intercepting request creates the transport request inside execution.execute.
        // A per-thread attempt timeout keeps concurrent calls independent and is always removed.
        var attemptTimeout = new ThreadLocal<Duration>();
        ClientHttpRequestFactory requests = (uri, method) -> {
            var factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(attemptTimeout.get() == null ? read : attemptTimeout.get());
            return factory.createRequest(uri, method);
        };
        URI origin = URI.create(baseUrl);
        TargetState state = targets.computeIfAbsent(target, this::newTarget);
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(requests);
        if (observations != null) {
            builder.observationRegistry(observations);
        }
        return builder
                .requestInterceptor((request, body, execution) -> {
                    guardTransaction(target, clientId);
                    request.getHeaders().remove(TOKEN_HEADER);
                    boolean sameOrigin = origin.getScheme().equalsIgnoreCase(request.getURI().getScheme())
                            && origin.getAuthority().equalsIgnoreCase(request.getURI().getAuthority());
                    if (sameOrigin && request.getURI().getPath().startsWith("/api/v1/internal/")) {
                        request.getHeaders().set(TOKEN_HEADER, properties.outboundToken(target));
                    }
                    // The callee logs under the caller's trace id; nothing leaves for another origin.
                    String traceId = MDC.get(TraceIds.MDC_KEY);
                    if (sameOrigin && TraceIds.isValid(traceId) && !request.getHeaders().containsHeader(TraceIds.HEADER)) {
                        request.getHeaders().set(TraceIds.HEADER, traceId);
                    }
                    if (!state.permits.tryAcquire()) {
                        throw rejected(target, "bulkhead_full");
                    }
                    if (!state.breaker.tryPass()) {
                        state.permits.release();
                        throw rejected(target, "circuit_open");
                    }
                    try {
                        ClientHttpResponse response = execute(request, body, execution, read, attemptTimeout);
                        return new ProtectedResponse(response, state, outcome(response));
                    } catch (IOException | RuntimeException ex) {
                        state.breaker.record(Thread.currentThread().isInterrupted() ? Outcome.NEUTRAL : Outcome.FAILURE);
                        state.permits.release();
                        throw ex;
                    }
                }).build();
    }

    /** The breaker and bulkhead remain shared across every client/profile for this target. */
    public Map<String, TargetSnapshot> snapshots() {
        Map<String, TargetSnapshot> result = new TreeMap<>();
        targets.forEach((name, state) -> result.put(name, new TargetSnapshot(
                state.breaker.state(), state.capacity - state.permits.availablePermits(), state.capacity)));
        return Map.copyOf(result);
    }

    private ClientHttpResponse execute(org.springframework.http.HttpRequest request, byte[] body,
                                       ClientHttpRequestExecution execution, Duration read,
                                       ThreadLocal<Duration> attemptTimeout) throws IOException {
        boolean retryable = request.getMethod() == HttpMethod.GET;
        long deadline = System.nanoTime() + read.toNanos() + positive(properties.getHttp().getRetryGrace()).toNanos();
        try {
            for (int attempt = 0; ; attempt++) {
                long remaining = deadline - System.nanoTime();
                if (attempt > 0 && remaining <= 0) {
                    throw new java.net.SocketTimeoutException("Internal HTTP retry budget exhausted");
                }
                attemptTimeout.set(attempt == 0 ? read
                        : Duration.ofNanos(Math.max(1_000_000L, Math.min(read.toNanos(), remaining))));
                try {
                    ClientHttpResponse response = execution.execute(request, body);
                    int status = response.getStatusCode().value();
                    if (retryable && attempt == 0 && (status == 502 || status == 503 || status == 504)) {
                        response.close();
                        backoff();
                        continue;
                    }
                    return response;
                } catch (IOException ex) {
                    if (!retryable || attempt != 0 || Thread.currentThread().isInterrupted()) { throw ex; }
                    backoff();
                }
            }
        } finally {
            attemptTimeout.remove();
        }
    }

    /**
     * A 5xx counts against the target's health, except a 503 with Retry-After: that is a target at
     * capacity turning work away on purpose (agent-executor when busy), and it says nothing about health.
     */
    private static Outcome outcome(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (!status.is5xxServerError()) {
            return Outcome.SUCCESS;
        }
        return status.value() == 503 && response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)
                ? Outcome.NEUTRAL : Outcome.FAILURE;
    }

    private void guardTransaction(String target, String clientId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { return; }
        transactionCounter.accept(target);
        if (properties.getHttp().getTransactionGuard() == InternalHttpProperties.TransactionGuard.ERROR) {
            throw new IllegalStateException("Internal HTTP inside database transaction: target=" + target + " client=" + clientId);
        }
        log.warn("Internal HTTP inside database transaction target={} client={}", target, clientId);
    }

    private TargetState newTarget(String target) {
        var settings = properties.getHttp().getTargets().getOrDefault(target, new InternalHttpProperties.Target());
        if (settings.getBulkheadLimit() < 1 || settings.getWindowSize() < 1 || settings.getConsecutiveFailures() < 1
                || settings.getFailureRateThreshold() <= 0 || settings.getFailureRateThreshold() > 1) {
            throw new IllegalArgumentException("Invalid internal HTTP protection settings for " + target);
        }
        TargetState state = new TargetState(new CircuitBreaker(settings.getWindowSize(), settings.getFailureRateThreshold(),
                settings.getConsecutiveFailures(), positive(settings.getOpenDuration()), nanoClock), settings.getBulkheadLimit());
        if (meters != null) {
            Gauge.builder("aioj.internal.http.circuit.state", state, s -> circuitValue(s.breaker.state()))
                    .description("Breaker of an internal target: 0 closed, 1 half-open, 2 open")
                    .tag("target", target)
                    .register(meters);
        }
        return state;
    }

    private static double circuitValue(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
        };
    }

    /** A call turned away before it left: the target's bulkhead is full or its breaker is open. */
    private ResourceAccessException rejected(String target, String reason) {
        if (meters != null) {
            meters.counter("aioj.internal.http.rejected", "target", target, "reason", reason).increment();
        }
        return unavailable(target, reason);
    }

    private static Duration positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("Internal HTTP timeouts and cooldown must be positive");
        }
        return value;
    }

    private static ResourceAccessException unavailable(String target, String reason) {
        return new ResourceAccessException("Internal HTTP unavailable: target=" + target + " reason=" + reason);
    }

    private static void backoff() throws InterruptedIOException {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(25, 76));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Internal HTTP retry interrupted");
        }
    }

    public record TargetSnapshot(CircuitBreaker.State circuit, int inUse, int capacity) { }
    private record TransportKey(String target, Duration connectTimeout) { }

    private static final class TargetState {
        private final CircuitBreaker breaker;
        private final Semaphore permits;
        private final int capacity;
        private TargetState(CircuitBreaker breaker, int capacity) {
            this.breaker = breaker;
            this.capacity = capacity;
            this.permits = new Semaphore(capacity);
        }
    }

    /** Retain the permit while the caller reads/streams the body; always release exactly once. */
    private static final class ProtectedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final TargetState state;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Outcome outcome;
        private InputStream body;
        private ProtectedResponse(ClientHttpResponse delegate, TargetState state, Outcome outcome) {
            this.delegate = delegate;
            this.state = state;
            this.outcome = outcome;
        }
        @Override
        public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override
        public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override
        public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                try {
                    body = new FilterInputStream(delegate.getBody()) {
                        @Override
                        public int read() throws IOException {
                            try { return super.read(); } catch (IOException ex) { outcome = Outcome.FAILURE; throw ex; }
                        }
                        @Override
                        public int read(byte[] bytes, int offset, int length) throws IOException {
                            try { return super.read(bytes, offset, length); } catch (IOException ex) { outcome = Outcome.FAILURE; throw ex; }
                        }
                    };
                } catch (IOException ex) { outcome = Outcome.FAILURE; throw ex; }
            }
            return body;
        }
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    delegate.close();
                } finally {
                    state.breaker.record(Thread.currentThread().isInterrupted() ? Outcome.NEUTRAL : outcome);
                    state.permits.release();
                }
            }
        }
    }
}
