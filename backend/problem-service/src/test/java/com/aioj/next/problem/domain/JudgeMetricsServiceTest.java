package com.aioj.next.problem.domain;

import com.aioj.next.contract.submission.JudgeMetricsResponse;
import com.aioj.next.problem.config.InternalApiProperties;
import com.aioj.next.problem.config.JudgeQueueConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JudgeMetricsServiceTest {
    private final AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void combinesWorkerCountersWithQueueDepth() throws IOException {
        AtomicReference<String> token = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/judge/metrics", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] body = """
                    {"code":0,"message":"ok","data":{"started":12,"finished":10,"failed":1,"systemErrors":2,
                    "inFlight":1,"lastFinishedAt":"2026-10-22T10:00:00Z","lastFailureType":"IllegalStateException",
                    "averageQueueWaitMillis":35,"averageJudgeMillis":1280}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        when(amqpAdmin.getQueueInfo(JudgeQueueConfig.JUDGE_QUEUE))
                .thenReturn(new QueueInformation(JudgeQueueConfig.JUDGE_QUEUE, 3, 1));
        when(amqpAdmin.getQueueInfo(JudgeQueueConfig.JUDGE_DEAD_LETTER_QUEUE))
                .thenReturn(new QueueInformation(JudgeQueueConfig.JUDGE_DEAD_LETTER_QUEUE, 2, 0));

        JudgeMetricsResponse metrics = service("http://127.0.0.1:" + server.getAddress().getPort()).metrics();

        assertThat(token.get()).isEqualTo("internal-token");
        assertThat(metrics.workerReachable()).isTrue();
        assertThat(metrics.finished()).isEqualTo(10L);
        assertThat(metrics.systemErrors()).isEqualTo(2L);
        assertThat(metrics.averageJudgeMillis()).isEqualTo(1280L);
        assertThat(metrics.lastFailureType()).isEqualTo("IllegalStateException");
        assertThat(metrics.queueReady()).isEqualTo(3L);
        assertThat(metrics.queueConsumers()).isEqualTo(1);
        assertThat(metrics.deadLetterReady()).isEqualTo(2L);
    }

    @Test
    void reportsWhatIsKnownWhenWorkerAndBrokerAreDown() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int closedPort = server.getAddress().getPort();
        server.stop(0);
        server = null;
        when(amqpAdmin.getQueueInfo(JudgeQueueConfig.JUDGE_QUEUE))
                .thenThrow(new AmqpConnectException(new IOException("broker down")));

        JudgeMetricsResponse metrics = service("http://127.0.0.1:" + closedPort).metrics();

        assertThat(metrics.workerReachable()).isFalse();
        assertThat(metrics.finished()).isNull();
        assertThat(metrics.queueReady()).isNull();
        assertThat(metrics.collectedAt()).isNotNull();
    }

    private JudgeMetricsService service(String baseUrl) {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setApiToken("internal-token");
        properties.setJudgeWorkerBaseUrl(baseUrl);
        return new JudgeMetricsService(properties, JsonMapper.builderWithJackson2Defaults().build(), amqpAdmin);
    }
}
