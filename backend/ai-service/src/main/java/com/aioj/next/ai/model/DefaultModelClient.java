package com.aioj.next.ai.model;

import com.aioj.next.ai.model.api.CallOptions;
import com.aioj.next.ai.model.api.CancellationSignal;
import com.aioj.next.ai.model.api.EmbeddingRequest;
import com.aioj.next.ai.model.api.EmbeddingResponse;
import com.aioj.next.ai.model.api.ModelCallListener;
import com.aioj.next.ai.model.api.ModelCallRecord;
import com.aioj.next.ai.model.api.ModelClient;
import com.aioj.next.ai.model.api.ModelConfigSource;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ModelInfo;
import com.aioj.next.ai.model.api.ModelOperation;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ModelResponse;
import com.aioj.next.ai.model.api.ModelStreamListener;
import com.aioj.next.ai.model.api.ModelUsage;
import com.aioj.next.ai.model.api.ProviderCapabilities;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.api.ToolChoice;
import com.aioj.next.ai.model.config.ModelClientProperties;
import com.aioj.next.ai.model.provider.CallSettings;
import com.aioj.next.ai.model.provider.ChatAdapter;
import com.aioj.next.ai.model.provider.DeepSeekAdapter;
import com.aioj.next.ai.model.provider.EmbeddingAdapter;
// Retired on 2026-10-29 with their registrations below.
// import com.aioj.next.ai.model.provider.GenericOpenAiAdapter;
// import com.aioj.next.ai.model.provider.KimiAdapter;
import com.aioj.next.ai.model.provider.ModelCatalog;
import com.aioj.next.ai.model.provider.StreamAccumulator;
import com.aioj.next.common.error.SafeErrors;
import com.aioj.next.common.resilience.CircuitBreaker;
import com.aioj.next.ai.model.resilience.CircuitBreakers;
import com.aioj.next.ai.model.resilience.RetryPolicy;
import com.aioj.next.ai.model.resilience.ScopeBulkheads;
import com.aioj.next.ai.model.transport.HttpTransport;
import com.aioj.next.ai.model.transport.TransportResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The pipeline of plan §3.2.1: configuration, capability check, scope bulkhead,
 * circuit breaker, retry while nothing was produced, deadline, provider adapter,
 * transport, listeners.
 */
public final class DefaultModelClient implements ModelClient, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DefaultModelClient.class);
    /** Listing a catalog is an admin action; it gets its own small bulkhead. */
    private static final String CATALOG_SCOPE = "MODEL_CATALOG";

    private final ModelConfigSource configSource;
    private final List<ModelCallListener> listeners;
    private final Map<ProviderId, ChatAdapter> chatAdapters = new EnumMap<>(ProviderId.class);
    private final EmbeddingAdapter embeddings = new EmbeddingAdapter();
    private final ModelCatalog catalog = new ModelCatalog();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpTransport transport;
    private final ScopeBulkheads bulkheads;
    private final CircuitBreakers breakers;
    private final Map<String, CircuitBreaker> textScopeCircuits = new java.util.concurrent.ConcurrentHashMap<>();
    private final RetryPolicy retryPolicy;
    private final Duration firstByteTimeout;
    private final Duration totalTimeout;

    public DefaultModelClient(ModelConfigSource configSource, List<ModelCallListener> listeners,
                              ModelClientProperties properties) {
        this.configSource = configSource;
        this.listeners = List.copyOf(listeners);
        chatAdapters.put(ProviderId.DEEPSEEK, new DeepSeekAdapter());
        // Kimi and the plain OpenAI-compatible text option were retired on 2026-10-29 (phase 1, batch 6). Their
        // code is kept so they can come back; until then OPENAI_COMPATIBLE serves embeddings only.
        // chatAdapters.put(ProviderId.KIMI, new KimiAdapter());
        // chatAdapters.put(ProviderId.OPENAI_COMPATIBLE, new GenericOpenAiAdapter());
        this.transport = new HttpTransport(properties.getConnectTimeout());
        ModelClientProperties.Bulkhead bulkhead = properties.getBulkhead();
        this.bulkheads = new ScopeBulkheads(bulkhead::concurrencyFor, bulkhead.getMaxWaiting(), bulkhead.getMaxWait());
        ModelClientProperties.Breaker breaker = properties.getBreaker();
        this.breakers = new CircuitBreakers(breaker.getWindowSize(), breaker.getFailureRateThreshold(),
                breaker.getConsecutiveFailures(), breaker.getOpenDuration(), System::nanoTime);
        ModelClientProperties.Retry retry = properties.getRetry();
        this.retryPolicy = new RetryPolicy(retry.getMaxRetries(), retry.getInitialBackoff(), retry.getMaxBackoff(),
                retry.getMaxRetryAfter(), () -> ThreadLocalRandom.current().nextDouble());
        this.firstByteTimeout = properties.getFirstByteTimeout();
        this.totalTimeout = properties.getTotalTimeout();
    }

    @Override
    public Map<String, String> textCircuitStates() {
        Map<String, String> states = new java.util.TreeMap<>();
        textScopeCircuits.forEach((scope, circuit) -> states.put(scope, circuit.state().name()));
        return Map.copyOf(states);
    }

    @Override
    public ModelEndpointConfig resolve(String scope) {
        ModelEndpointConfig endpoint = configSource.resolve(scope);
        if (endpoint == null || endpoint.provider() == null) {
            throw new ModelException(ModelErrorKind.CONFIGURATION_INVALID, null, null,
                    "No AI model is configured for " + scope);
        }
        return endpoint;
    }

    @Override
    public ProviderCapabilities capabilities(ModelEndpointConfig endpoint) {
        ChatAdapter adapter = chatAdapters.get(endpoint.provider());
        return adapter != null
                ? adapter.capabilities(endpoint.model())
                : embeddings.capabilities(endpoint.provider(), endpoint.model());
    }

    @Override
    public ModelResponse complete(ModelRequest request, CallOptions options) {
        return chat(ModelOperation.COMPLETE, request, options, ModelStreamListener.NONE);
    }

    @Override
    public ModelResponse stream(ModelRequest request, CallOptions options, ModelStreamListener listener) {
        return chat(ModelOperation.STREAM, request, options, listener == null ? ModelStreamListener.NONE : listener);
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request, CallOptions options) {
        Call call = new Call(ModelOperation.EMBED, request.scope(), options);
        try {
            ModelEndpointConfig endpoint = request.endpoint() != null ? request.endpoint() : resolve(request.scope());
            call.endpoint = endpoint;
            ensureNotRetired(endpoint);
            ensureUsable(endpoint);
            if (!capabilities(endpoint).embeddings()) {
                throw new ModelException(ModelErrorKind.CAPABILITY_UNSUPPORTED, endpoint.provider(), endpoint.model(),
                        "AI provider " + endpoint.provider().configName() + " has no embedding API");
            }
            HttpTransport.Exchange exchange = new HttpTransport.Exchange(embeddings.uri(endpoint),
                    headers(endpoint, true, "application/json"),
                    write(embeddings.body(endpoint, request.inputs())), endpoint.provider(), endpoint.model(),
                    endpoint.tuning().connectTimeout());
            EmbeddingResponse response;
            try (ScopeBulkheads.Permit ignored = acquire(request.scope(), endpoint.tuning().maxConcurrency(),
                    call.options, endpoint)) {
                response = withRetries(call, endpoint, () -> {
                    TransportResponse answer = transport.send(exchange, attemptTimeout(call.options, endpoint),
                            call.options.cancellation());
                    if (!answer.successful()) {
                        throw embeddings.classify(answer.status(), answer.body(), answer.retryAfter(), endpoint);
                    }
                    return embeddings.parse(answer.body(), endpoint, request.inputs().size());
                });
            }
            call.usage = response.usage();
            return response;
        } catch (RuntimeException ex) {
            call.fail(ex);
            throw ex;
        } finally {
            report(call);
        }
    }

    @Override
    public List<ModelInfo> listModels(ModelEndpointConfig candidate, CallOptions options) {
        Call call = new Call(ModelOperation.LIST_MODELS, CATALOG_SCOPE, options);
        try {
            call.endpoint = candidate;
            if (candidate == null || candidate.provider() == null) {
                throw new ModelException(ModelErrorKind.CONFIGURATION_INVALID, null, null, "No AI provider was given");
            }
            ensureNotRetired(candidate);
            if (!candidate.hasApiKey()) {
                throw new ModelException(ModelErrorKind.CONFIGURATION_INVALID, candidate.provider(), candidate.model(),
                        "Server environment API key is not configured for this provider.");
            }
            HttpTransport.Exchange exchange = new HttpTransport.Exchange(catalog.modelsUri(candidate),
                    headers(candidate, false, "application/json"), null, candidate.provider(), candidate.model(),
                    candidate.tuning().connectTimeout());
            try (ScopeBulkheads.Permit ignored = acquire(CATALOG_SCOPE, null, call.options, candidate)) {
                return withRetries(call, candidate, () -> {
                    TransportResponse answer = transport.send(exchange, attemptTimeout(call.options, candidate),
                            call.options.cancellation());
                    if (!answer.successful()) {
                        throw catalog.classify(answer.status(), answer.body(), answer.retryAfter(), candidate);
                    }
                    return catalog.parse(answer.body(), candidate);
                });
            }
        } catch (RuntimeException ex) {
            call.fail(ex);
            throw ex;
        } finally {
            report(call);
        }
    }

    @Override
    public void close() {
        transport.close();
    }

    private ModelResponse chat(ModelOperation operation, ModelRequest request, CallOptions options,
                               ModelStreamListener listener) {
        Call call = new Call(operation, request.scope(), options);
        try {
            ModelEndpointConfig endpoint = request.endpoint() != null ? request.endpoint() : resolve(request.scope());
            call.endpoint = endpoint;
            ensureNotRetired(endpoint);
            ensureUsable(endpoint);
            ChatAdapter adapter = chatAdapters.get(endpoint.provider());
            if (adapter == null) {
                throw new ModelException(ModelErrorKind.CAPABILITY_UNSUPPORTED, endpoint.provider(), endpoint.model(),
                        "AI provider " + endpoint.provider().configName() + " is not available for chat");
            }
            boolean stream = operation == ModelOperation.STREAM;
            ProviderCapabilities capabilities = adapter.capabilities(endpoint.model());
            check(capabilities, request, stream, endpoint);
            CallSettings settings = CallSettings.resolve(endpoint, request, capabilities);
            ObjectNode body = adapter.chatBody(endpoint, settings, request, stream);
            HttpTransport.Exchange exchange = new HttpTransport.Exchange(adapter.chatUri(endpoint),
                    headers(endpoint, true, stream ? "text/event-stream" : "application/json"), write(body),
                    endpoint.provider(), endpoint.model(), endpoint.tuning().connectTimeout());
            ModelResponse response;
            try (ScopeBulkheads.Permit ignored = acquire(request.scope(), endpoint.tuning().maxConcurrency(),
                    call.options, endpoint)) {
                response = withRetries(call, endpoint, () -> stream
                        ? streamOnce(adapter, exchange, endpoint, settings, call.options, listener)
                        : completeOnce(adapter, exchange, endpoint, settings, call.options));
            }
            call.usage = response.usage();
            return response;
        } catch (RuntimeException ex) {
            call.fail(ex);
            throw ex;
        } finally {
            report(call);
        }
    }

    private ModelResponse completeOnce(ChatAdapter adapter, HttpTransport.Exchange exchange,
                                       ModelEndpointConfig endpoint, CallSettings settings, CallOptions options) {
        TransportResponse answer = transport.send(exchange, attemptTimeout(options, endpoint), options.cancellation());
        if (!answer.successful()) {
            throw adapter.classify(answer.status(), answer.body(), answer.retryAfter(), endpoint, settings);
        }
        return adapter.parseChat(answer.body(), endpoint);
    }

    private ModelResponse streamOnce(ChatAdapter adapter, HttpTransport.Exchange exchange,
                                     ModelEndpointConfig endpoint, CallSettings settings, CallOptions options,
                                     ModelStreamListener listener) {
        StreamAccumulator accumulator = new StreamAccumulator(listener);
        Duration attempt = attemptTimeout(options, endpoint);
        Duration firstByte = endpoint.tuning().firstByteTimeout() == null
                ? firstByteTimeout : endpoint.tuning().firstByteTimeout();
        Duration headers = attempt.compareTo(firstByte) < 0 ? attempt : firstByte;
        TransportResponse answer = transport.stream(exchange, headers, Instant.now().plus(attempt),
                options.cancellation(), data -> {
                    try {
                        adapter.onStreamEvent(data, accumulator);
                    } catch (ModelException ex) {
                        throw accumulator.outputStarted() ? ex.afterOutput() : ex;
                    }
                }, accumulator::outputStarted);
        if (!answer.successful()) {
            throw adapter.classify(answer.status(), answer.body(), answer.retryAfter(), endpoint, settings);
        }
        if (!accumulator.finished() && !answer.streamCompleted()) {
            ModelException early = new ModelException(ModelErrorKind.UNAVAILABLE, endpoint.provider(),
                    endpoint.model(), "AI provider stream ended before the answer was complete");
            throw accumulator.outputStarted() ? early.afterOutput() : early;
        }
        return accumulator.toResponse(endpoint.provider(), endpoint.model());
    }

    private <T> T withRetries(Call call, ModelEndpointConfig endpoint, Attempt<T> attempt) {
        CircuitBreaker breaker = breakers.forEndpoint(endpoint.provider().configName() + " "
                + String.valueOf(endpoint.baseUrl()).trim());
        if (call.scope != null && (call.operation == ModelOperation.COMPLETE || call.operation == ModelOperation.STREAM)) {
            textScopeCircuits.put(call.scope, breaker);
        }
        while (true) {
            throwIfCancelledOrLate(call.options, endpoint);
            if (!breaker.tryPass()) {
                throw new ModelException(ModelErrorKind.UNAVAILABLE, endpoint.provider(), endpoint.model(),
                        "AI provider " + endpoint.provider().configName()
                                + " is temporarily unavailable after repeated failures");
            }
            call.attempts++;
            try {
                T result = attempt.run();
                breaker.record(CircuitBreaker.Outcome.SUCCESS);
                return result;
            } catch (ModelException failure) {
                breaker.record(CircuitBreakers.outcomeOf(failure.kind()));
                Optional<Duration> delay = retryPolicy.delayBeforeRetry(failure, call.attempts,
                        call.options.deadline(), endpoint.tuning().maxRetries());
                if (delay.isEmpty()) {
                    throw failure;
                }
                log.warn("Model call retry: provider={} model={} kind={} status={} type={} attempt={} delayMs={}",
                        endpoint.provider().configName(), endpoint.model(), failure.kind(), failure.httpStatus(),
                        failure.providerErrorType(), call.attempts, delay.get().toMillis());
                pause(delay.get(), call.options.cancellation(), endpoint);
            } catch (RuntimeException other) {
                breaker.record(CircuitBreaker.Outcome.NEUTRAL);
                throw other;
            }
        }
    }

    private void check(ProviderCapabilities capabilities, ModelRequest request, boolean stream,
                       ModelEndpointConfig endpoint) {
        if (request.messages().isEmpty()) {
            throw new IllegalArgumentException("a chat request needs at least one message");
        }
        String subject = "AI model " + endpoint.model() + " of " + endpoint.provider().configName();
        if (!request.tools().isEmpty() && !capabilities.toolCalling()) {
            throw new ModelException(ModelErrorKind.CAPABILITY_UNSUPPORTED, endpoint.provider(), endpoint.model(),
                    subject + " cannot call tools");
        }
        if (request.toolChoice() == ToolChoice.REQUIRED && !capabilities.toolChoiceRequiredNative()) {
            throw new ModelException(ModelErrorKind.CAPABILITY_UNSUPPORTED, endpoint.provider(), endpoint.model(),
                    subject + " has no native tool_choice=required; the Agent loop must simulate it");
        }
        if (stream && !capabilities.streaming()) {
            throw new ModelException(ModelErrorKind.CAPABILITY_UNSUPPORTED, endpoint.provider(), endpoint.model(),
                    subject + " cannot stream");
        }
    }

    /** A stored configuration may still name a retired provider; say so instead of a missing key or adapter. */
    private static void ensureNotRetired(ModelEndpointConfig endpoint) {
        if (endpoint.provider().retired()) {
            throw new ModelException(ModelErrorKind.CONFIGURATION_INVALID, endpoint.provider(), endpoint.model(),
                    "AI provider " + endpoint.provider().configName() + " was retired on 2026-10-29; use deepseek");
        }
    }

    private static void ensureUsable(ModelEndpointConfig endpoint) {
        if (!endpoint.enabled()) {
            throw new ModelException(ModelErrorKind.CONFIGURATION_INVALID, endpoint.provider(), endpoint.model(),
                    "AI model configuration is disabled");
        }
        if (!endpoint.hasApiKey()) {
            throw new ModelException(ModelErrorKind.CONFIGURATION_INVALID, endpoint.provider(), endpoint.model(),
                    "AI API key is not configured");
        }
    }

    private ScopeBulkheads.Permit acquire(String scope, Integer limit, CallOptions options,
                                          ModelEndpointConfig endpoint) {
        return bulkheads.acquire(scope, limit, options.deadline(), options.cancellation(), endpoint.provider(),
                endpoint.model());
    }

    /** The time one attempt may take: the endpoint's or configured total, cut to what is left of the deadline. */
    private Duration attemptTimeout(CallOptions options, ModelEndpointConfig endpoint) {
        Duration totalTimeout = endpoint.tuning().totalTimeout() == null
                ? this.totalTimeout : endpoint.tuning().totalTimeout();
        if (options.deadline() == null) {
            return totalTimeout;
        }
        Duration left = Duration.between(Instant.now(), options.deadline());
        if (left.isNegative() || left.isZero()) {
            throw late(endpoint);
        }
        return left.compareTo(totalTimeout) < 0 ? left : totalTimeout;
    }

    private static void throwIfCancelledOrLate(CallOptions options, ModelEndpointConfig endpoint) {
        if (options.cancellation() != null && options.cancellation().isCancelled()) {
            throw new ModelException(ModelErrorKind.CANCELLED, endpoint.provider(), endpoint.model(),
                    "AI model call was cancelled");
        }
        if (options.deadline() != null && !Instant.now().isBefore(options.deadline())) {
            throw late(endpoint);
        }
    }

    private static ModelException late(ModelEndpointConfig endpoint) {
        return new ModelException(ModelErrorKind.TIMEOUT, endpoint.provider(), endpoint.model(),
                "AI model call ran out of time");
    }

    private static void pause(Duration delay, CancellationSignal cancellation, ModelEndpointConfig endpoint) {
        CountDownLatch woken = new CountDownLatch(1);
        CancellationSignal.Registration registration = cancellation == null ? () -> {
        } : cancellation.onCancel(woken::countDown);
        try {
            woken.await(delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelErrorKind.CANCELLED, endpoint.provider(), endpoint.model(),
                    "AI model call was interrupted");
        } finally {
            registration.close();
        }
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ModelException(ModelErrorKind.CANCELLED, endpoint.provider(), endpoint.model(),
                    "AI model call was cancelled");
        }
    }

    private Map<String, String> headers(ModelEndpointConfig endpoint, boolean jsonBody, String accept) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + endpoint.apiKey());
        if (jsonBody) {
            headers.put("Content-Type", "application/json");
        }
        headers.put("Accept", accept);
        return headers;
    }

    private String write(ObjectNode body) {
        return json.writeValueAsString(body);
    }

    private void report(Call call) {
        ModelEndpointConfig endpoint = call.endpoint;
        ModelCallRecord record = new ModelCallRecord(call.operation, call.scope, call.options.feature(),
                endpoint == null ? null : endpoint.provider(), endpoint == null ? null : endpoint.model(), call.usage,
                (System.nanoTime() - call.startNanos) / 1_000_000L, call.attempts, call.errorKind,
                call.options.correlationId(), call.options.subjectUserId(), call.startedAt);
        if (call.errorKind != null && call.errorKind != ModelErrorKind.CANCELLED) {
            log.warn("Model call failed: operation={} scope={} feature={} provider={} model={} kind={} status={} "
                            + "type={} attempts={}", call.operation, call.scope, record.feature(),
                    endpoint == null || endpoint.provider() == null ? null : endpoint.provider().configName(),
                    record.model(), call.errorKind, call.httpStatus, call.providerErrorType, call.attempts);
        }
        for (ModelCallListener listener : listeners) {
            try {
                listener.onFinish(record);
            } catch (RuntimeException ex) {
                log.warn("Model call listener {} failed: {}", listener.getClass().getName(), SafeErrors.describe(ex));
            }
        }
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T run();
    }

    /** What one logical call did, for the listeners. */
    private static final class Call {
        private final ModelOperation operation;
        private final String scope;
        private final CallOptions options;
        private final Instant startedAt = Instant.now();
        private final long startNanos = System.nanoTime();
        private ModelEndpointConfig endpoint;
        private int attempts;
        private ModelUsage usage = ModelUsage.NONE;
        private ModelErrorKind errorKind;
        private Integer httpStatus;
        private String providerErrorType;

        private Call(ModelOperation operation, String scope, CallOptions options) {
            this.operation = operation;
            this.scope = scope;
            this.options = options == null ? CallOptions.of(null) : options;
        }

        private void fail(RuntimeException failure) {
            if (failure instanceof ModelException model) {
                errorKind = model.kind();
                httpStatus = model.httpStatus();
                providerErrorType = model.providerErrorType();
            } else {
                // The caller's own code (a stream listener, a bad argument) stopped the call.
                errorKind = ModelErrorKind.CANCELLED;
            }
        }
    }
}
