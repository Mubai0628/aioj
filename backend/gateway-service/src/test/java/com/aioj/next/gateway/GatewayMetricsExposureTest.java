package com.aioj.next.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5: the gateway's own meters are on its port, which production binds to 127.0.0.1 behind nginx
 * (nginx forwards /api/ only); every other actuator endpoint stays off (T2.5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayMetricsExposureTest {
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Test
    void prometheusIsExposedAndNothingElseBesideHealth() throws Exception {
        HttpResponse<String> metrics = get("/actuator/prometheus");

        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("jvm_memory_used_bytes");
        for (String hidden : List.of("/actuator/env", "/actuator/gateway/routes", "/actuator/beans", "/actuator/heapdump")) {
            assertThat(get(hidden).statusCode()).as(hidden).isEqualTo(404);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
