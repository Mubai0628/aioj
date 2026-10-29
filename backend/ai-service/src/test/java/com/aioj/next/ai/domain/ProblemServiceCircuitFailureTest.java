package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.config.InternalApiProperties;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.http.InternalHttpProperties;
import com.aioj.next.common.http.InternalRestClientFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemServiceCircuitFailureTest {
    @Test
    void openCircuitKeepsContestReadsFailClosedAndOptionalReadsBestEffort() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var ai = new AiProperties();
            ai.setProblemServiceUri("http://127.0.0.1:" + server.getAddress().getPort());
            var internal = new InternalApiProperties();
            var protection = new InternalHttpProperties.Target();
            protection.setConsecutiveFailures(1);
            internal.getHttp().getTargets().put("problem-service", protection);
            var factory = new InternalRestClientFactory(internal);
            var client = new ProblemServiceClient(ai, JsonMapper.builderWithJackson2Defaults().build(), internal, null, factory);
            assertThatThrownBy(() -> client.runningParticipationsStrict(7L)).isInstanceOf(DomainException.class);
            assertThat(calls.get()).isEqualTo(2);
            assertThatThrownBy(() -> client.runningContestProblemStatementsStrict(7L)).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> client.runningParticipationsFresh(7L)).isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
            assertThat(client.runningParticipations(7L)).isEmpty();
            assertThat(client.contestRunWindows(1L)).isEmpty();
            assertThat(calls.get()).as("an open shared circuit sends no request").isEqualTo(2);
        } finally { server.stop(0); }
    }
}
