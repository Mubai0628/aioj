package com.aioj.next.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The gateway is where a caller-supplied trace id first enters the logs (T2.5). */
class TraceWebFilterTest {
    private final TraceWebFilter filter = new TraceWebFilter();

    @Test
    void aSafeIncomingIdIsForwardedEchoedAndUsedAsTheLogPrefix() {
        MockServerWebExchange exchange = exchange("abc-123_x.y");
        ServerWebExchange forwarded = run(exchange);

        assertThat(forwarded.getRequest().getHeaders().getFirst(TraceWebFilter.TRACE_HEADER)).isEqualTo("abc-123_x.y");
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceWebFilter.TRACE_HEADER)).isEqualTo("abc-123_x.y");
        assertThat(forwarded.getLogPrefix()).isEqualTo("[abc-123_x.y] ");
    }

    @Test
    void anUnsafeIncomingIdIsReplaced() {
        for (String unsafe : List.of("line\nbreak", "x".repeat(65), "a b", "<script>", "")) {
            MockServerWebExchange exchange = exchange(unsafe);
            ServerWebExchange forwarded = run(exchange);
            String sent = forwarded.getRequest().getHeaders().getFirst(TraceWebFilter.TRACE_HEADER);

            assertThat(sent).isNotEqualTo(unsafe).matches("[A-Za-z0-9._-]{1,64}");
            assertThat(exchange.getResponse().getHeaders().getFirst(TraceWebFilter.TRACE_HEADER)).isEqualTo(sent);
        }
    }

    @Test
    void runsBeforeEveryOtherGatewayFilter() {
        assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE);
    }

    private static MockServerWebExchange exchange(String traceId) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/problems")
                .header(TraceWebFilter.TRACE_HEADER, traceId));
    }

    private ServerWebExchange run(MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, next -> {
            forwarded.set(next);
            return Mono.empty();
        }).block();
        return forwarded.get();
    }
}
