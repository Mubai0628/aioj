package com.aioj.next.sandbox.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoJudgeClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BODY_SENTINEL = "sandbox-body-91d0";

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "[]";
    private volatile long delayMillis;
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void runPostsOneCommandWithTheTokenAndParsesTheFirstResult() throws IOException {
        responseBody = """
                [{"status":"Accepted","exitStatus":0,"time":1500000,"memory":2048,"runTime":1600000,"procPeak":2,
                  "files":{"stdout":"3","stderr":""},"fileIds":{"main":"exe-1"},"somethingNew":true}]""";

        RunResult result = client("secret-token", Duration.ofSeconds(5)).run(Command.builder(List.of("./main"))
                .env(List.of("PATH=/usr/bin:/bin"))
                .standardFiles("1 2", 1024, 512)
                .limits(1_000_000_000L, 3_000_000_000L, 268_435_456L, 50)
                .copyInCached("main", "exe-1")
                .build());

        assertThat(result.accepted()).isTrue();
        assertThat(result.file("stdout")).isEqualTo("3");
        assertThat(result.cachedFileId("main")).isEqualTo("exe-1");
        assertThat(Limits.nanosToMillis(result.time())).isEqualTo(2L);
        assertThat(Limits.bytesToKb(result.memory())).isEqualTo(2L);
        assertThat(requests).singleElement().asString().startsWith("POST /run Bearer secret-token application/json ");
        JsonNode command = JSON.readTree(requests.get(0).substring(requests.get(0).indexOf('{'))).path("cmd").path(0);
        assertThat(command).isEqualTo(JSON.readTree("""
                {"args":["./main"],"env":["PATH=/usr/bin:/bin"],
                 "files":[{"content":"1 2"},{"name":"stdout","max":1024},{"name":"stderr","max":512}],
                 "cpuLimit":1000000000,"clockLimit":3000000000,"memoryLimit":268435456,"procLimit":50,
                 "copyIn":{"main":{"fileId":"exe-1"}}}"""));
    }

    @Test
    void withoutATokenNoAuthorizationHeaderIsSent() {
        responseBody = "[{\"status\":\"Accepted\"}]";

        client(null, Duration.ofSeconds(5)).run(Command.builder(List.of("/bin/true")).build());

        assertThat(requests).singleElement().asString().startsWith("POST /run - ");
    }

    @Test
    void errorsNameMethodPathAndStatusButNeitherHostNorBody() {
        status = 500;
        responseBody = "{\"error\":\"" + BODY_SENTINEL + "\"}";

        assertThatThrownBy(() -> client("t", Duration.ofSeconds(5)).run(Command.builder(List.of("/bin/true")).build()))
                .isInstanceOf(SandboxException.class)
                .hasMessage("Sandbox HTTP 500 on POST /run");
        status = 404;
        assertThatThrownBy(() -> client("t", Duration.ofSeconds(5)).downloadFile("gone", new ByteArrayOutputStream()))
                .hasMessage("Sandbox HTTP 404 on GET /file/{id}")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("127.0.0.1", BODY_SENTINEL));
    }

    @Test
    void anEmptyAnswerOrBrokenJsonIsAnError() {
        responseBody = "[]";
        assertThatThrownBy(() -> client("t", Duration.ofSeconds(5)).run(Command.builder(List.of("/bin/true")).build()))
                .hasMessage("Sandbox returned empty /run response");
        responseBody = "[{\"status\":";
        assertThatThrownBy(() -> client("t", Duration.ofSeconds(5)).run(Command.builder(List.of("/bin/true")).build()))
                .hasMessage("Sandbox returned an unreadable /run response");
    }

    @Test
    void theReadTimeoutCoversTheWholeRequest() {
        delayMillis = 2_000;
        responseBody = "[{\"status\":\"Accepted\"}]";

        long started = System.nanoTime();
        assertThatThrownBy(() -> client("t", Duration.ofMillis(300)).run(Command.builder(List.of("/bin/true")).build()))
                .hasMessage("Sandbox request timed out on POST /run");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_500);
    }

    @Test
    void aRefusedConnectionIsReportedWithoutTheAddress() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        GoJudgeClient client = new GoJudgeClient(new SandboxConfig("http://127.0.0.1:" + closedPort + "/run", "t",
                Duration.ofSeconds(2), Duration.ofSeconds(2)));

        assertThatThrownBy(() -> client.run(Command.builder(List.of("/bin/true")).build()))
                .isInstanceOf(SandboxException.class)
                .hasMessageStartingWith("Sandbox request failed on POST /run: ")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(String.valueOf(closedPort)));
    }

    @Test
    void cachedFilesAreDownloadedAndDeletedByEncodedId() {
        responseBody = "zip-bytes";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GoJudgeClient client = client("t", Duration.ofSeconds(5));

        client.downloadFile("a b/c", out);
        client.deleteFile("a b/c");

        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("zip-bytes");
        assertThat(requests).containsExactly("GET /file/a%20b%2Fc Bearer t - ", "DELETE /file/a%20b%2Fc Bearer t - ");
    }

    @Test
    void theListenerHearsEveryRequestWithItsOperationAndOutcome() {
        List<String> heard = new CopyOnWriteArrayList<>();
        GoJudgeClient client = new GoJudgeClient(new SandboxConfig("http://127.0.0.1:" + server.getAddress().getPort()
                + "/run", "t", Duration.ofSeconds(2), Duration.ofSeconds(5)),
                (operation, outcome, elapsedNanos) -> heard.add(operation + ":" + outcome + ":" + (elapsedNanos >= 0)));

        responseBody = "[{\"status\":\"Time Limit Exceeded\",\"exitStatus\":0,\"time\":1,\"memory\":1,\"runTime\":1}]";
        client.run(Command.builder(List.of("./main")).build());
        status = 500;
        assertThatThrownBy(() -> client.run(Command.builder(List.of("./main")).build()))
                .isInstanceOf(SandboxException.class);
        status = 200;
        responseBody = "zip-bytes";
        client.downloadFile("f1", new ByteArrayOutputStream());
        client.deleteFile("f1");

        assertThat(heard).containsExactly("run:TIME_LIMIT_EXCEEDED:true", "run:FAILED:true",
                "download_file:OK:true", "delete_file:OK:true");
    }

    @Test
    void aListenerThatThrowsDoesNotFailTheRequest() {
        responseBody = "[{\"status\":\"Accepted\",\"exitStatus\":0,\"time\":1,\"memory\":1,\"runTime\":1}]";
        GoJudgeClient client = new GoJudgeClient(new SandboxConfig("http://127.0.0.1:" + server.getAddress().getPort()
                + "/run", "t", Duration.ofSeconds(2), Duration.ofSeconds(5)),
                (operation, outcome, elapsedNanos) -> {
                    throw new IllegalStateException("meter registry broke");
                });

        assertThat(client.run(Command.builder(List.of("./main")).build()).accepted()).isTrue();
    }

    private GoJudgeClient client(String token, Duration readTimeout) {
        return new GoJudgeClient(new SandboxConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/run", token,
                Duration.ofSeconds(2), readTimeout));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath() + " "
                + (auth == null ? "-" : auth) + " " + (type == null ? "-" : type) + " " + body);
        try {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The client gave up first.
        } finally {
            exchange.close();
        }
    }
}
