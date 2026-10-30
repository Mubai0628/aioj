package com.aioj.next.gateway;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5 log redaction at the entry (T2.5): when a route's service is down, the gateway logs the
 * failure without the caller's token or what the query string carried.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayLogRedactionTest {
    private static final int CLOSED_PORT = closedPort();

    @DynamicPropertySource
    static void problemServiceIsDown(DynamicPropertyRegistry registry) {
        registry.add("PROBLEM_SERVICE_URI", () -> "http://127.0.0.1:" + CLOSED_PORT);
    }

    @Value("${local.server.port}")
    private int port;

    @Test
    void aFailedForwardKeepsTheTokenAndTheQueryOutOfTheLog() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        HttpResponse<String> response;
        try {
            response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/problems?keyword=CANARY-QUERY-3c"))
                    .header("Authorization", "Bearer CANARY-JWT-eyJ0eXAi")
                    .header("X-Trace-Id", "redaction-1").GET().build(), HttpResponse.BodyHandlers.ofString());
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            if (event.getLevel().isGreaterOrEqual(Level.INFO)) {
                text.append(event.getFormattedMessage()).append('\n');
                if (event.getThrowableProxy() != null) {
                    text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
                }
            }
        }
        assertThat(text.toString()).doesNotContain("CANARY-JWT", "CANARY-QUERY")
                .contains("[redaction-1] 500 Server Error for HTTP GET \"/api/v1/problems\" error=");
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
