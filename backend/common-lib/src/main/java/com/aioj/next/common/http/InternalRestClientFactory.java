package com.aioj.next.common.http;

import com.aioj.next.common.security.InternalServiceProperties;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.web.client.RestClient;

/** Service-scoped factory. Only internal requests receive this direction's credential. */
public class InternalRestClientFactory {
    public static final String TOKEN_HEADER = "X-Internal-Token";
    private final InternalServiceProperties properties;

    public InternalRestClientFactory(InternalServiceProperties properties) {
        this.properties = properties;
    }

    public RestClient create(String target, String baseUrl) {
        return builder(target, baseUrl).build();
    }

    public RestClient create(String target, String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(readTimeout);
        return create(target, baseUrl, requests);
    }

    public RestClient create(String target, String baseUrl, ClientHttpRequestFactory requestFactory) {
        return builder(target, baseUrl).requestFactory(requestFactory).build();
    }

    private RestClient.Builder builder(String target, String baseUrl) {
        URI origin = URI.create(baseUrl);
        return RestClient.builder().baseUrl(baseUrl).requestInterceptor((request, body, execution) -> {
            request.getHeaders().remove(TOKEN_HEADER);
            if (origin.getScheme().equalsIgnoreCase(request.getURI().getScheme())
                    && origin.getAuthority().equalsIgnoreCase(request.getURI().getAuthority())
                    && request.getURI().getPath().startsWith("/api/v1/internal/")) {
                request.getHeaders().set(TOKEN_HEADER, properties.outboundToken(target));
            }
            return execution.execute(request, body);
        });
    }
}
