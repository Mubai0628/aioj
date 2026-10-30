package com.aioj.next.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The services read the client address from one header, which only the gateway writes (T2.2). */
class ClientIpWebFilterTest {
    private final ClientIpWebFilter filter = new ClientIpWebFilter(new ClientAddress(List.of("172.16.0.0/12"), 1));

    @Test
    void theResolvedAddressReplacesWhatTheClientSentUnderTheSameName() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("172.18.0.5", 40000))
                .header("X-Forwarded-For", "6.6.6.6, 203.0.113.7")
                .header(ClientIpWebFilter.HEADER, "10.0.0.1"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, next -> {
            forwarded.set(next);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getRequest().getHeaders().get(ClientIpWebFilter.HEADER)).containsExactly("203.0.113.7");
        assertThat((String) forwarded.get().getAttribute(ClientIpWebFilter.ATTRIBUTE)).isEqualTo("203.0.113.7");
    }

    @Test
    void runsRightAfterTheTraceFilterAndBeforeTheLimits() {
        assertThat(filter.getOrder()).isGreaterThan(new TraceWebFilter().getOrder()).isLessThan(RateLimitWebFilter.ORDER);
    }
}
