package com.aioj.next.judge.domain;

import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.contract.problem.TestcaseCheckerProtocol;
import com.aioj.next.contract.problem.TestcaseCheckerType;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.judge.persistence.entity.SubmissionEntity;
import com.aioj.next.judge.persistence.mapper.SubmissionMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan T1.8: pins every request judge-worker sends to go-judge (method, path,
 * bearer token and body) for each language and step: compile, run, custom
 * checker, the execution endpoints' scripts, cached-file download and delete.
 * The sandbox-client extraction must leave all of them unchanged.
 *
 * <p>Bodies are compared as JSON with object keys sorted, because several
 * commands are built from {@code Map.of}, whose iteration order changes from
 * one JVM start to the next. Arrays (commands, files, arguments) keep their
 * order. To re-record after an intended change, run with
 * {@code CHARACTERIZATION_RECORD=true} and review the snapshot diff.</p>
 */
class SandboxRequestCharacterizationTest {
    private static final String SNAPSHOT = "characterization/sandbox-requests.json";
    private static final long PROBLEM_ID = 99L;
    private static final long SUBMISSION_ID = 123L;
    private static final Map<String, String> SOURCES = Map.of(
            "cpp", "#include <iostream>\nint main(){std::string s;std::cin>>s;std::cout<<s;}\n",
            "java", "public class Main { public static void main(String[] a) { System.out.print(new java.util.Scanner(System.in).next()); } }\n",
            "python", "print(input(), end='')\n");

    private final ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();
    private final ObjectMapper canonical = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final List<JsonNode> recorded = new CopyOnWriteArrayList<>();
    private final TestcasePackageCache packageCache = mock(TestcasePackageCache.class);
    private final SubmissionMapper submissionMapper = mock(SubmissionMapper.class);
    private final JudgeWorkerProperties properties = new JudgeWorkerProperties();
    private HttpServer server;

    @TempDir
    Path dir;

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
    void sandboxRequestsMatchTheRecordedSnapshot() throws IOException {
        Map<String, List<JsonNode>> scenarios = new LinkedHashMap<>();
        for (String language : List.of("cpp", "java", "python")) {
            scenarios.put("judge-" + language + "-standard", judge(language, null, TestcaseCheckerType.STANDARD,
                    testcase(1, "a", null), testcase(2, "b", null)));
        }
        scenarios.put("judge-cpp-wrong-answer-stops", judge("cpp", null, TestcaseCheckerType.STANDARD,
                testcase(1, "wa", null), testcase(2, "b", null)));
        scenarios.put("judge-cpp-custom-checker", judge("cpp", null, TestcaseCheckerType.CUSTOM,
                testcase(1, "a", null)));
        scenarios.put("judge-cpp-ioi-custom-checker-scores", judge("cpp", ContestMode.IOI, TestcaseCheckerType.CUSTOM,
                testcase(1, "a", 30), testcase(2, "b", 70)));

        SandboxExecutionClient execution = new SandboxExecutionClient(properties);
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

        String actual = canonical.writeValueAsString(scenarios) + "\n";
        if ("true".equals(System.getenv("CHARACTERIZATION_RECORD"))) {
            Path target = Path.of("src/test/resources", SNAPSHOT);
            Files.createDirectories(target.getParent());
            Files.writeString(target, actual, StandardCharsets.UTF_8);
            return;
        }
        try (InputStream expected = getClass().getClassLoader().getResourceAsStream(SNAPSHOT)) {
            assertThat(expected).as("snapshot " + SNAPSHOT + " (record it with CHARACTERIZATION_RECORD=true)").isNotNull();
            JsonNode expectedTree = mapper.readTree(expected);
            JsonNode actualTree = mapper.readTree(actual);
            assertThat(new TreeSet<>(actualTree.propertyNames())).isEqualTo(new TreeSet<>(expectedTree.propertyNames()));
            for (String scenario : expectedTree.propertyNames()) {
                assertThat(actualTree.get(scenario)).as(scenario).isEqualTo(expectedTree.get(scenario));
            }
        }
    }

    private List<JsonNode> judge(String language, ContestMode mode, TestcaseCheckerType checkerType,
                                 PreparedTestcaseCase... cases) throws IOException {
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(SUBMISSION_ID);
        submission.setCode(SOURCES.get(language));
        when(submissionMapper.selectById(SUBMISSION_ID)).thenReturn(submission);
        Path checker = checkerType == TestcaseCheckerType.CUSTOM
                ? Files.writeString(dir.resolve("checker.cpp"), "int main() { return 0; }\n")
                : null;
        PreparedTestcasePackage testcasePackage = new PreparedTestcasePackage(5L, PROBLEM_ID, "sha", dir, checkerType,
                checker == null ? null : "cpp", checker, checker == null ? null : TestcaseCheckerProtocol.AIOJ_JSON,
                List.of(cases));
        when(packageCache.prepareActivePackage(PROBLEM_ID)).thenReturn(Optional.of(testcasePackage));
        SandboxClient client = new SandboxClient(properties, packageCache, submissionMapper,
                new DefaultOutputComparator(), mapper, new SandboxExecutionClient(properties));
        return capture(() -> client.judge(new JudgeTaskMessage(SUBMISSION_ID, PROBLEM_ID, 7L, null, null, null, null,
                mode, language, "trace", 1000, 262144L)));
    }

    private List<JsonNode> capture(Runnable action) {
        recorded.clear();
        action.run();
        return List.copyOf(recorded);
    }

    private PreparedTestcaseCase testcase(long id, String input, Integer score) throws IOException {
        Path in = Files.writeString(dir.resolve(id + ".in"), input);
        Path out = Files.writeString(dir.resolve(id + ".out"), input);
        return new PreparedTestcaseCase(id, String.valueOf(id), in, out, false, null, score, (int) id);
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

        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/file/")) {
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

    /** go-judge's answer, chosen from what the command runs. */
    private Map<String, Object> answer(JsonNode command) {
        List<String> args = new ArrayList<>();
        command.path("args").forEach(arg -> args.add(arg.asString("")));
        if (args.contains("main.cpp")) {
            return result("", Map.of(), Map.of("main", "exe-cpp"));
        }
        if (args.contains("Main.java")) {
            return result("", Map.of(), Map.of("Main.class", "exe-java"));
        }
        if (args.contains("checker.cpp")) {
            return result("", Map.of(), Map.of("checker", "chk-1"));
        }
        if (!args.isEmpty() && "./checker".equals(args.get(0))) {
            return result("{\"status\":\"ACCEPTED\",\"score\":" + args.get(4) + "}", Map.of(), null);
        }
        if (!args.isEmpty() && "/bin/bash".equals(args.get(0))) {
            return result("", Map.of(".aioj_generated_files.json", "{\"files\":[]}",
                    ".aioj_official_package.json", "{\"cases\":[]}"), Map.of(".aioj_official_hidden.zip", "zip-1"));
        }
        String stdin = command.path("files").path(0).path("content").asString("");
        return result("wa".equals(stdin) ? "something else" : stdin, Map.of(), null);
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
