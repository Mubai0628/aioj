package com.aioj.next.gateway;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class TraceWebFilter implements WebFilter, Ordered {
    public static final String TRACE_HEADER = "X-Trace-Id";
    /** The rule common-lib's TraceIds applies downstream: nothing that can split a log line or a header. */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        String traceId = incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate().header(TRACE_HEADER, traceId).build();
        exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);
        // WebFlux and the gateway prefix their own log lines with the exchange's log id.
        exchange.getAttributes().put(ServerWebExchange.LOG_ID_ATTRIBUTE, traceId);
        String finalTraceId = traceId;
        return chain.filter(exchange.mutate().request(request).build())
                .contextWrite(context -> context.put("traceId", finalTraceId))
                .doFinally(signalType -> MDC.remove("traceId"));
    }
}
