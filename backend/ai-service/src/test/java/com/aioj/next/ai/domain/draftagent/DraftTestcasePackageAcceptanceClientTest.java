package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.judge.DraftTestcasePackageRunRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.HttpServerErrorException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** agent-executor's refusal of a package reaches the draft Agent as a reason it can repair from. */
class DraftTestcasePackageAcceptanceClientTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aPackageTheExecutorRefusesFailsWithTheExecutorsReason(@TempDir Path dir) throws IOException {
        DraftTestcasePackageAcceptanceClient client = clientAnswering(400, "application/json",
                "{\"code\":40000,\"message\":\"Draft testcase package contains an unsafe compression ratio\",\"data\":null}");

        assertThatThrownBy(() -> client.run(packageIn(dir), request()))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).isEqualTo("agent-executor refused the testcase package: "
                            + "Draft testcase package contains an unsafe compression ratio");
                });
    }

    @Test
    void aRefusalWithoutAnApiBodyStillNamesItsStatus(@TempDir Path dir) throws IOException {
        DraftTestcasePackageAcceptanceClient client = clientAnswering(413, "text/plain", "too large");

        assertThatThrownBy(() -> client.run(packageIn(dir), request()))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).isEqualTo("agent-executor refused the testcase package: HTTP 413");
                });
    }

    @Test
    void aFailingExecutorIsNotTheModelsValidationFailure(@TempDir Path dir) throws IOException {
        DraftTestcasePackageAcceptanceClient client = clientAnswering(500, "application/json",
                "{\"code\":50000,\"message\":\"internal\",\"data\":null}");

        assertThatThrownBy(() -> client.run(packageIn(dir), request())).isInstanceOf(HttpServerErrorException.class);
    }

    private DraftTestcasePackageAcceptanceClient clientAnswering(int status, String contentType, String body)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/sandbox/run-package", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        AiProperties properties = new AiProperties();
        properties.setAgentExecutorUri("http://127.0.0.1:" + server.getAddress().getPort());
        return new DraftTestcasePackageAcceptanceClient(properties);
    }

    private static Path packageIn(Path dir) throws IOException {
        return Files.write(dir.resolve("package.zip"), new byte[] {1, 2, 3});
    }

    private static DraftTestcasePackageRunRequest request() {
        return new DraftTestcasePackageRunRequest("sha", 3L, "cpp", "int main(){}", null, null, 1000, 262_144, 200);
    }
}
