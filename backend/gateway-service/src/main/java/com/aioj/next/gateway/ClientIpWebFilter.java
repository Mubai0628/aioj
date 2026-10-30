package com.aioj.next.gateway;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Works out the client's address once (T2.2), for the per-address limits and for the services
 * behind the gateway, which read only {@link #HEADER}; a value the client sent under that name
 * is dropped first.
 */
public class ClientIpWebFilter implements WebFilter, Ordered {
    public static final String HEADER = "X-Aioj-Client-Ip";
    public static final String ATTRIBUTE = ClientIpWebFilter.class.getName() + ".clientIp";
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    private final ClientAddress addresses;

    public ClientIpWebFilter(ClientAddress addresses) {
        this.addresses = addresses;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String client = addresses.resolve(remote == null ? null : remote.getAddress(),
                exchange.getRequest().getHeaders().get("X-Forwarded-For"));
        ServerHttpRequest.Builder request = exchange.getRequest().mutate()
                .headers(headers -> headers.remove(HEADER));
        if (client != null) {
            exchange.getAttributes().put(ATTRIBUTE, client);
            request.header(HEADER, client);
        }
        return chain.filter(exchange.mutate().request(request.build()).build());
    }
}
