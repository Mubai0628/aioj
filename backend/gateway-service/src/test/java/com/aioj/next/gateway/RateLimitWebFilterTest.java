package com.aioj.next.gateway;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-address limits in front of the five write paths (T2.2), with the buckets stubbed. */
class RateLimitWebFilterTest {
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void eachLimitedPathCountsUnderItsPolicyAndTheClientsAddress() {
        RateLimitWebFilter filter = filter(allowing(), List.of());
        for (String path : List.of("/api/v1/auth/login", "/api/v1/submissions", "/api/v1/ai/chat/stream",
                "/api/v1/ai/code-runs", "/api/v1/admin/problem-drafts/42/regeneration-job")) {
            assertThat(run(filter, post(path, "203.0.113.7"))).as(path).isTrue();
        }

        assertThat(keys).containsExactly("auth:203.0.113.7", "submit:203.0.113.7", "ai-chat:203.0.113.7",
                "code-run:203.0.113.7", "draft:203.0.113.7");
    }

    @Test
    void readsAndOtherPathsAreNotLimited() {
        RateLimitWebFilter filter = filter(allowing(), List.of());

        assertThat(run(filter, MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/submissions")
                .remoteAddress(new InetSocketAddress("203.0.113.7", 1)))) ).isTrue();
        assertThat(run(filter, post("/api/v1/problems", "203.0.113.7"))).isTrue();
        assertThat(run(filter, post("/api/v1/auth/logout", "203.0.113.7"))).isTrue();
        assertThat(keys).isEmpty();
    }

    @Test
    void aFullBucketAnswers429WithTheServicesErrorBodyAndRetryAfter() {
        RateLimitWebFilter filter = filter((policy, key) -> Mono.just(new RateLimiter.Response(false, Map.of())), List.of());
        MockServerWebExchange exchange = post("/api/v1/auth/login", "203.0.113.7");
        exchange.getResponse().getHeaders().set(TraceWebFilter.TRACE_HEADER, "trace-429");

        assertThat(run(filter, exchange)).as("the request does not go on").isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":42900", "\"errorKey\":\"request.tooManyFromNetwork\"",
                        "\"retryAfterSeconds\":\"2\"", "\"traceId\":\"trace-429\"");
        assertThat(meters.get(RateLimitWebFilter.REJECTED).tags("limiter", "gateway.auth", "dimension", "ip")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void aSharedNetworkSkipsThePerAddressLimit() {
        RateLimitWebFilter filter = filter(allowing(), List.of("198.51.100.0/24"));

        assertThat(run(filter, post("/api/v1/submissions", "198.51.100.20"))).isTrue();
        assertThat(keys).isEmpty();
    }

    @Test
    void redisTroubleLetsTheRequestThroughAndIsCounted() {
        RateLimitWebFilter unavailable = filter((policy, key) -> Mono.just(new RateLimiter.Response(true,
                Map.of("X-RateLimit-Remaining", "-1"))), List.of());
        RateLimitWebFilter failing = filter((policy, key) -> Mono.error(new IllegalStateException("redis down")),
                List.of());

        assertThat(run(unavailable, post("/api/v1/submissions", "203.0.113.7"))).isTrue();
        assertThat(run(failing, post("/api/v1/submissions", "203.0.113.7"))).isTrue();
        assertThat(meters.get(RateLimitWebFilter.BYPASSED).tags("limiter", "gateway.submit", "reason", "redis_unavailable")
                .counter().count()).isEqualTo(1.0);
        assertThat(meters.get(RateLimitWebFilter.BYPASSED).tags("limiter", "gateway.submit", "reason", "error")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void anIpv6ClientIsCountedByItsSlash64() {
        RateLimitWebFilter filter = filter(allowing(), List.of());

        run(filter, post("/api/v1/auth/login", "2001:db8:1:2:aaaa::1"));
        run(filter, post("/api/v1/auth/login", "2001:db8:1:2:bbbb::2"));

        assertThat(keys).containsExactly("auth:2001:db8:1:2::/64", "auth:2001:db8:1:2::/64");
    }

    @Test
    void bucketsChargeSeveralTokensForSlowRefills() {
        var properties = new GatewayProtectionProperties.RateLimit();

        assertThat(properties.getAuth().tokensPerRequest()).isEqualTo(2);
        assertThat(properties.getAuth().burstTokens()).isEqualTo(60);
        assertThat(properties.getAuth().retryAfterSeconds()).isEqualTo(2);
        assertThat(properties.getSubmit().tokensPerRequest()).isEqualTo(1);
        assertThat(properties.getDraft().tokensPerRequest()).isEqualTo(10);
        assertThat(properties.getDraft().retryAfterSeconds()).isEqualTo(10);
    }

    private RateLimitWebFilter.Limiter allowing() {
        return (policy, key) -> {
            keys.add(key);
            return Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "5")));
        };
    }

    private RateLimitWebFilter filter(RateLimitWebFilter.Limiter limiter, List<String> shared) {
        var settings = new GatewayProtectionProperties.RateLimit();
        settings.setSharedNetworks(shared);
        return new RateLimitWebFilter(RateLimitWebFilter.policies(settings), limiter, settings, meters);
    }

    private static MockServerWebExchange post(String path, String client) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .remoteAddress(new InetSocketAddress("172.18.0.5", 40000)));
        exchange.getAttributes().put(ClientIpWebFilter.ATTRIBUTE, client);
        return exchange;
    }

    private static boolean run(RateLimitWebFilter filter, ServerWebExchange exchange) {
        AtomicBoolean passed = new AtomicBoolean();
        filter.filter(exchange, next -> {
            passed.set(true);
            return Mono.empty();
        }).block();
        return passed.get();
    }
}
