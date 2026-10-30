package com.aioj.next.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-address token buckets in front of the five write paths S2 names (T2.2). The per-user limits in
 * the services are the main ones; these stop one address (a script, one person with many accounts)
 * from multiplying them. Redis trouble lets the request through, and counts it.
 */
public class RateLimitWebFilter implements WebFilter, Ordered {
    public static final int ORDER = ClientIpWebFilter.ORDER + 1;
    static final String REJECTED = "aioj.ratelimit.rejected";
    static final String BYPASSED = "aioj.ratelimit.bypassed";
    static final String ERROR_KEY = "request.tooManyFromNetwork";
    private static final Logger log = LoggerFactory.getLogger(RateLimitWebFilter.class);
    private static final long LOG_EVERY_MILLIS = 60_000;

    private final List<Policy> policies;
    private final Limiter limiter;
    private final List<ClientAddress.Network> sharedNetworks;
    private final boolean enabled;
    private final MeterRegistry meters;
    private final Map<String, Long> lastLogged = new ConcurrentHashMap<>();

    public RateLimitWebFilter(List<Policy> policies, Limiter limiter, GatewayProtectionProperties.RateLimit settings,
                              MeterRegistry meters) {
        this.policies = List.copyOf(policies);
        this.limiter = limiter;
        this.sharedNetworks = ClientAddress.networks(settings.getSharedNetworks());
        this.enabled = settings.isEnabled();
        this.meters = meters;
    }

    /** POST paths as the browser sends them, before the gateway strips {@code /api/v1}. */
    public static List<Policy> policies(GatewayProtectionProperties.RateLimit settings) {
        return List.of(
                Policy.of("auth", settings.getAuth(),
                        "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh"),
                Policy.of("submit", settings.getSubmit(), "/api/v1/submissions"),
                Policy.of("ai-chat", settings.getAiChat(), "/api/v1/ai/chat/send", "/api/v1/ai/chat/stream"),
                Policy.of("code-run", settings.getCodeRun(), "/api/v1/ai/code-runs"),
                Policy.of("draft", settings.getDraft(),
                        "/api/v1/ai/problem-drafts/generate", "/api/v1/ai/problem-drafts/generate/stream",
                        "/api/v1/ai/problem-drafts/generation-jobs",
                        "/api/v1/admin/problem-drafts/{id}/regeneration-job",
                        "/api/v1/admin/problem-drafts/{id}/refine", "/api/v1/admin/problem-drafts/{id}/regenerate",
                        "/api/v1/ai/admin/problem-draft-workspaces/{workspaceId}/turns"));
    }

    /** One Redis token bucket per policy, keyed by policy and client; Spring Cloud Gateway's own script. */
    public static Limiter redis(ApplicationContext context, List<Policy> policies) {
        Map<String, RedisRateLimiter> buckets = new HashMap<>();
        for (Policy policy : policies) {
            RedisRateLimiter bucket = new RedisRateLimiter(policy.bucket().replenishPerSecond(),
                    policy.bucket().burstTokens(), policy.bucket().tokensPerRequest());
            bucket.setApplicationContext(context);
            buckets.put(policy.name(), bucket);
        }
        return (policy, key) -> buckets.get(policy.name()).isAllowed(policy.name(), key);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Policy policy = enabled ? match(exchange.getRequest()) : null;
        String client = policy == null ? null : exchange.getAttribute(ClientIpWebFilter.ATTRIBUTE);
        if (client == null) {
            return chain.filter(exchange);
        }
        InetAddress address = ClientAddress.parse(client);
        if (address != null && ClientAddress.contains(sharedNetworks, address)) {
            return chain.filter(exchange);
        }
        String key = policy.name() + ":" + ClientAddress.limitKey(client);
        return limiter.isAllowed(policy, key)
                .onErrorResume(error -> {
                    count(BYPASSED, policy, "reason", "error");
                    return Mono.just(new RateLimiter.Response(true, Map.of()));
                })
                .flatMap(response -> {
                    if (response.isAllowed()) {
                        // RedisRateLimiter answers "allowed, -1 left" when Redis could not be asked.
                        if ("-1".equals(response.getHeaders().get(RedisRateLimiter.REMAINING_HEADER))) {
                            count(BYPASSED, policy, "reason", "redis_unavailable");
                        }
                        return chain.filter(exchange);
                    }
                    count(REJECTED, policy, "dimension", "ip");
                    return tooMany(exchange, policy, client);
                });
    }

    private Policy match(ServerHttpRequest request) {
        if (request.getMethod() != HttpMethod.POST) {
            return null;
        }
        PathContainer path = request.getPath().pathWithinApplication();
        for (Policy policy : policies) {
            if (policy.matches(path)) {
                return policy;
            }
        }
        return null;
    }

    /** The services' error body, so the browser shows the same kind of message whichever layer refused. */
    private Mono<Void> tooMany(ServerWebExchange exchange, Policy policy, String client) {
        long retryAfter = policy.bucket().retryAfterSeconds();
        ServerHttpResponse response = exchange.getResponse();
        String traceId = response.getHeaders().getFirst(TraceWebFilter.TRACE_HEADER);
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        logRejection(traceId, policy, client, retryAfter);
        // The trace id passed TraceWebFilter's character rule, so it needs no JSON escaping.
        String body = ("{\"code\":42900,\"message\":\"同一网络的请求过多，请稍后再试。\",\"data\":null,\"details\":null,"
                + "\"traceId\":\"%s\",\"timestamp\":\"%s\",\"errorKey\":\"%s\","
                + "\"errorParams\":{\"retryAfterSeconds\":\"%d\"}}")
                .formatted(traceId == null ? "n/a" : traceId, Instant.now(), ERROR_KEY, retryAfter);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** One line per client and policy a minute, so a flood does not become a log flood. */
    private void logRejection(String traceId, Policy policy, String client, long retryAfter) {
        long now = System.currentTimeMillis();
        String key = policy.name() + ":" + client;
        Long last = lastLogged.get(key);
        if (last != null && now - last < LOG_EVERY_MILLIS) {
            return;
        }
        if (lastLogged.size() > 10_000) {
            lastLogged.clear();
        }
        lastLogged.put(key, now);
        log.warn("[{}] rate limited policy={} client={} retryAfterSeconds={}", traceId, policy.name(), client,
                retryAfter);
    }

    private void count(String name, Policy policy, String tag, String value) {
        if (meters != null) {
            meters.counter(name, "limiter", "gateway." + policy.name(), tag, value).increment();
        }
    }

    /** The buckets; a Redis failure answers allowed with {@code -1} left. */
    @FunctionalInterface
    public interface Limiter {
        Mono<RateLimiter.Response> isAllowed(Policy policy, String key);
    }

    public record Policy(String name, GatewayProtectionProperties.Bucket bucket, List<PathPattern> paths) {
        static Policy of(String name, GatewayProtectionProperties.Bucket bucket, String... paths) {
            PathPatternParser parser = PathPatternParser.defaultInstance;
            return new Policy(name, bucket, java.util.Arrays.stream(paths).map(parser::parse).toList());
        }

        boolean matches(PathContainer path) {
            for (PathPattern pattern : paths) {
                if (pattern.matches(path)) {
                    return true;
                }
            }
            return false;
        }
    }
}
