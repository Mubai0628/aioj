package com.aioj.next.executor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application starts with nothing but its own token configured (no
 * database, queue or JWT settings), answers health checks, and turns away
 * internal calls without its token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aioj.executor.internal-api-token=executor-test-token",
        "aioj.executor.internal-api-next-token=executor-test-next-token",
        "aioj.internal.inbound.ai-service.token=executor-test-token",
        "aioj.internal.inbound.ai-service.next-token=executor-test-next-token",
        "aioj.executor.cache-root=${java.io.tmpdir}/aioj-agent-executor-test"
})
class AgentExecutorApplicationTest {
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Test
    void healthIsUpWithoutAnyCredential() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/actuator/health")).GET());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    void internalCallsNeedTheExecutorsOwnToken() throws Exception {
        String body = "{\"language\":\"go\",\"sourceCode\":\"x\",\"timeLimitMillis\":1000,\"memoryLimitKb\":262144}";

        HttpResponse<String> missing = send(compile(body));
        HttpResponse<String> wrong = send(compile(body).header("X-Internal-Token", "shared-internal-token"));
        HttpResponse<String> right = send(compile(body).header("X-Internal-Token", "executor-test-token"));
        HttpResponse<String> next = send(compile(body).header("X-Internal-Token", "executor-test-next-token"));

        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(right.statusCode()).as("reaches the endpoint, which rejects the language").isEqualTo(400);
        assertThat(next.statusCode()).as("NEXT passes both the filter and controller").isEqualTo(400);
    }

    private HttpRequest.Builder compile(String body) {
        return HttpRequest.newBuilder(uri("/api/v1/internal/sandbox/compile"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
