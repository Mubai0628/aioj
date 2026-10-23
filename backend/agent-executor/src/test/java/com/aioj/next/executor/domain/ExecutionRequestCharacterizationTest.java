package com.aioj.next.executor.domain;

import com.aioj.next.executor.config.AgentExecutorProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan T1.8: agent-executor's copy of the execution client must send go-judge
 * exactly what judge-worker's sends. The snapshot holds the "exec-" scenarios
 * of judge-worker's SandboxRequestCharacterizationTest snapshot, unchanged;
 * if judge-worker's is re-recorded, derive this one from it again.
 */
class ExecutionRequestCharacterizationTest {
    private static final String SNAPSHOT = "characterization/execution-requests.json";
    private static final Map<String, String> SOURCES = Map.of(
            "cpp", "#include <iostream>\nint main(){std::string s;std::cin>>s;std::cout<<s;}\n",
            "java", "public class Main { public static void main(String[] a) { System.out.print(new java.util.Scanner(System.in).next()); } }\n",
            "python", "print(input(), end='')\n");

    private final ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();
    private final List<JsonNode> recorded = new CopyOnWriteArrayList<>();
    private final AgentExecutorProperties properties = new AgentExecutorProperties();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        properties.setSandboxEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/run");
        properties.setSandboxToken("characterization-token");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void executionRequestsMatchJudgeWorkersSnapshot() throws IOException {
        SandboxExecutionClient execution = new SandboxExecutionClient(properties);
        Map<String, List<JsonNode>> scenarios = new LinkedHashMap<>();
        scenarios.put("exec-compile-cpp", capture(() -> execution.compileSource("cpp", SOURCES.get("cpp"),
                2_000_000_000L, 268_435_456L)));
        scenarios.put("exec-run-cpp-with-temporary-compile", capture(() -> execution.runSource("cpp", SOURCES.get("cpp"),
                null, "1 2", 1_000_000_000L, 268_435_456L, 0)));
        scenarios.put("exec-run-java-precompiled", capture(() -> execution.runSource("java", SOURCES.get("java"),
                "exe-java", "x", 1_000_000_000L, 268_435_456L, 4096)));
        scenarios.put("exec-run-python", capture(() -> execution.runSource("python", SOURCES.get("python"),
                null, "x", 1_000_000_000L, 268_435_456L, 0)));
        scenarios.put("exec-python-script-paired", capture(() -> execution.runPythonScript("print('gen')",
                5_000_000_000L, 536_870_912L, 0, 5, "PAIRED")));
        scenarios.put("exec-python-script-official-inputs", capture(() -> execution.runPythonScript("print('gen')",
                5_000_000_000L, 536_870_912L, 0, 4, "OFFICIAL_INPUTS")));
        scenarios.put("exec-official-package", capture(() -> execution.runOfficialPackageScript("print('gen')", "cpp",
                SOURCES.get("cpp"), 8_000_000_000L, 536_870_912L, 0, 6, 1_000_000_000L, 268_435_456L,
                1_048_576L, 16_777_216L)));
        scenarios.put("exec-download-and-delete", capture(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            execution.downloadCachedFile("zip-1", out);
            assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("cached-zip-bytes");
            execution.deleteCachedFile("zip-1");
        }));

        JsonNode actual = mapper.readTree(mapper.writeValueAsString(scenarios));
        try (InputStream expected = getClass().getClassLoader().getResourceAsStream(SNAPSHOT)) {
            assertThat(expected).as(SNAPSHOT).isNotNull();
            JsonNode expectedTree = mapper.readTree(expected);
            assertThat(new TreeSet<>(actual.propertyNames())).isEqualTo(new TreeSet<>(expectedTree.propertyNames()));
            for (String scenario : expectedTree.propertyNames()) {
                assertThat(actual.get(scenario)).as(scenario).isEqualTo(expectedTree.get(scenario));
            }
        }
    }

    private List<JsonNode> capture(Runnable action) {
        recorded.clear();
        action.run();
        return List.copyOf(recorded);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        ObjectNode request = mapper.createObjectNode();
        request.put("method", exchange.getRequestMethod());
        request.put("path", exchange.getRequestURI().getPath());
        request.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
        JsonNode body = requestBody.length == 0 ? null : mapper.readTree(requestBody);
        if (body != null) {
            request.set("body", sorted(body));
        }
        recorded.add(request);
        if (exchange.getRequestURI().getPath().startsWith("/file/")) {
            byte[] bytes = "GET".equals(exchange.getRequestMethod())
                    ? "cached-zip-bytes".getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
            return;
        }
        byte[] response = mapper.writeValueAsBytes(List.of(answer(body.path("cmd").path(0))));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static Map<String, Object> answer(JsonNode command) {
        List<String> args = new ArrayList<>();
        command.path("args").forEach(arg -> args.add(arg.asString("")));
        if (args.contains("main.cpp")) {
            return result("", Map.of(), Map.of("main", "exe-cpp"));
        }
        if (args.contains("Main.java")) {
            return result("", Map.of(), Map.of("Main.class", "exe-java"));
        }
        if (!args.isEmpty() && "/bin/bash".equals(args.get(0))) {
            return result("", Map.of(".aioj_generated_files.json", "{\"files\":[]}",
                    ".aioj_official_package.json", "{\"cases\":[]}"), Map.of(".aioj_official_hidden.zip", "zip-1"));
        }
        return result(command.path("files").path(0).path("content").asString(""), Map.of(), null);
    }

    private static Map<String, Object> result(String stdout, Map<String, String> extraFiles, Map<String, String> fileIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "Accepted");
        result.put("exitStatus", 0);
        result.put("time", 3_000_000L);
        result.put("memory", 1_048_576L);
        result.put("runTime", 3_000_000L);
        result.put("procPeak", 1);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("stdout", stdout);
        files.put("stderr", "");
        files.putAll(extraFiles);
        result.put("files", files);
        if (fileIds != null) {
            result.put("fileIds", fileIds);
        }
        return result;
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            new TreeSet<>(node.propertyNames()).forEach(name -> copy.set(name, sorted(node.get(name))));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            node.forEach(element -> copy.add(sorted(element)));
            return copy;
        }
        return node;
    }
}
