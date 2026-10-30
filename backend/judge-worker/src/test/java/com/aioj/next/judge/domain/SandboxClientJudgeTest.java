package com.aioj.next.judge.domain;

import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.contract.problem.TestcaseCheckerProtocol;
import com.aioj.next.contract.problem.TestcaseCheckerType;
import com.aioj.next.contract.submission.JudgePhase;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.judge.persistence.entity.SubmissionEntity;
import com.aioj.next.judge.persistence.mapper.SubmissionMapper;
import com.aioj.next.sandbox.client.DefaultOutputComparator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link SandboxClient#judge} against a local stand-in for go-judge: program
 * runs echo their stdin unless it is "tle" (time limit) or "wa" (wrong output).
 */
class SandboxClientJudgeTest {
    private static final long PROBLEM_ID = 99L;
    private static final long SUBMISSION_ID = 123L;

    private final ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();
    private final List<String> programInputs = new CopyOnWriteArrayList<>();
    private final TestcasePackageCache packageCache = mock(TestcasePackageCache.class);
    private final SubmissionMapper submissionMapper = mock(SubmissionMapper.class);
    private final JudgeWorkerProperties properties = new JudgeWorkerProperties();
    private volatile String compileStatus = "Accepted";
    private volatile String checkerCompileStatus = "Accepted";
    private volatile String checkerRunStatus = "Accepted";
    private volatile String checkerStderr = "";
    private HttpServer server;

    @TempDir
    Path dir;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run", this::handleRun);
        server.createContext("/file/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        properties.setSandboxEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/run");
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(SUBMISSION_ID);
        submission.setCode("int main() { return 0; }");
        when(submissionMapper.selectById(SUBMISSION_ID)).thenReturn(submission);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void practiceStopsAtFirstFailureButKeepsTheCasesItRan() throws IOException {
        usePackage(TestcaseCheckerType.STANDARD, testcase(1, "a", null, false), testcase(2, "tle", null, false),
                testcase(3, "c", null, false));

        JudgeResult result = client().judge(task(null));

        assertThat(result.status()).isEqualTo(SubmissionStatus.TIME_LIMIT_EXCEEDED);
        assertThat(programInputs).containsExactly("a", "tle");
        assertThat(result.caseResults()).extracting(JudgeCaseResult::status)
                .containsExactly(SubmissionStatus.ACCEPTED, SubmissionStatus.TIME_LIMIT_EXCEEDED);
        assertThat(result.caseTotal()).isEqualTo(3);
        assertThat(result.phase()).isEqualTo(JudgePhase.RUN);
        assertThat(result.sandboxStatus()).isEqualTo("Time Limit Exceeded");
        assertThat(result.score()).isNull();
        assertThat(result.maxScore()).isNull();
    }

    @Test
    void acmContestAlsoStopsAtFirstFailure() throws IOException {
        usePackage(TestcaseCheckerType.STANDARD, testcase(1, "wa", null, true), testcase(2, "b", null, false));

        JudgeResult result = client().judge(task(ContestMode.ACM));

        assertThat(result.status()).isEqualTo(SubmissionStatus.WRONG_ANSWER);
        assertThat(programInputs).containsExactly("wa");
        assertThat(result.caseResults()).singleElement().satisfies(testcase -> {
            assertThat(testcase.sample()).isTrue();
            assertThat(testcase.phase()).isEqualTo(JudgePhase.RUN);
            assertThat(testcase.sandboxStatus()).isEqualTo("Accepted");
        });
    }

    @Test
    void ioiRunsEveryCaseAndWeighsUnscoredLegacyPackagesEqually() throws IOException {
        usePackage(TestcaseCheckerType.STANDARD, testcase(1, "a", null, false), testcase(2, "tle", null, false),
                testcase(3, "c", null, false));

        JudgeResult result = client().judge(task(ContestMode.IOI));

        assertThat(programInputs).containsExactly("a", "tle", "c");
        assertThat(result.status()).isEqualTo(SubmissionStatus.TIME_LIMIT_EXCEEDED);
        assertThat(result.score()).isEqualByComparingTo("2");
        assertThat(result.maxScore()).isEqualByComparingTo("3");
        assertThat(result.caseResults()).hasSize(3);
    }

    @Test
    void explicitZeroPointCasesStayWorthZero() throws IOException {
        usePackage(TestcaseCheckerType.STANDARD, testcase(1, "a", 0, true), testcase(2, "b", 50, false),
                testcase(3, "c", 50, false));

        JudgeResult result = client().judge(task(ContestMode.IOI));

        assertThat(result.status()).isEqualTo(SubmissionStatus.ACCEPTED);
        assertThat(result.score()).isEqualByComparingTo("100");
        assertThat(result.maxScore()).isEqualByComparingTo("100");
        assertThat(result.caseResults().get(0).maxScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.phase()).isEqualTo(JudgePhase.RUN);
        assertThat(result.sandboxStatus()).isEqualTo("Accepted");
    }

    @Test
    void compilerTimeLimitIsStillReportedAsACompileError() throws IOException {
        usePackage(TestcaseCheckerType.STANDARD, testcase(1, "a", null, false));
        compileStatus = "Time Limit Exceeded";

        JudgeResult result = client().judge(task(null));

        assertThat(result.status()).isEqualTo(SubmissionStatus.COMPILE_ERROR);
        assertThat(result.message()).isEqualTo("Compilation exceeded the compile time limit");
        assertThat(result.phase()).isEqualTo(JudgePhase.COMPILE);
        assertThat(result.sandboxStatus()).isEqualTo("Time Limit Exceeded");
        assertThat(programInputs).isEmpty();
    }

    @Test
    void brokenCustomCheckerIsASystemErrorNotTheStudentsCompileError() throws IOException {
        usePackage(TestcaseCheckerType.CUSTOM, testcase(1, "a", null, false));
        checkerCompileStatus = "Nonzero Exit Status";
        checkerStderr = "checker.cpp:7: error: canary-checker-ab12";

        JudgeResult result = client().judge(task(null));

        assertThat(result.status()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
        assertThat(result.phase()).isEqualTo(JudgePhase.CHECKER);
        assertThat(result.sandboxStatus()).isEqualTo("Nonzero Exit Status");
        assertThat(programInputs).isEmpty();
        // Plan S7: the checker's compile output is the setter's source; the student sees the key only.
        assertThat(result.message()).isEqualTo(SandboxClient.CHECKER_UNAVAILABLE);
        assertThat(result.stderr()).isNull();
        assertThat(result.caseResults()).allSatisfy(testcase -> assertThat(testcase.message()).doesNotContain("canary"));
    }

    @Test
    void customCheckerThatTimesOutFailsTheCaseAsSystemError() throws IOException {
        usePackage(TestcaseCheckerType.CUSTOM, testcase(1, "a", null, false));
        checkerRunStatus = "Time Limit Exceeded";
        checkerStderr = "canary-checker-cd34 at line 9";

        JudgeResult result = client().judge(task(null));

        assertThat(result.status()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
        assertThat(result.phase()).isEqualTo(JudgePhase.CHECKER);
        assertThat(result.caseResults()).singleElement().satisfies(testcase -> {
            assertThat(testcase.status()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
            assertThat(testcase.sandboxStatus()).isEqualTo("Time Limit Exceeded");
            assertThat(testcase.message()).isEqualTo(SandboxClient.CHECKER_UNAVAILABLE);
        });
        assertThat(String.valueOf(result.message()) + result.stderr()).doesNotContain("canary-checker-cd34");
    }

    @Test
    void customCheckerVerdictIsAttributedToTheCheckerPhase() throws IOException {
        usePackage(TestcaseCheckerType.CUSTOM, testcase(1, "a", 10, false));

        JudgeResult result = client().judge(task(ContestMode.IOI));

        assertThat(result.status()).isEqualTo(SubmissionStatus.ACCEPTED);
        assertThat(result.caseResults()).singleElement()
                .satisfies(testcase -> assertThat(testcase.phase()).isEqualTo(JudgePhase.CHECKER));
        assertThat(result.score()).isEqualByComparingTo("10");
    }

    @Test
    void compileLimitsUseMultipliersLanguageFloorsAndCaps() {
        SandboxClient client = client();
        long mb = 1024L * 1024L;
        // 64 MB problem: 2 x 64 MB is below the 256 MB C++ floor.
        assertThat(client.compileMemoryLimitBytes(64 * mb, 262_144L)).isEqualTo(256 * mb);
        // T2.7: capped at 768 MB by default, the sandbox's 1 GiB less room for go-judge.
        assertThat(client.compileMemoryLimitBytes(512 * mb, 262_144L)).isEqualTo(768 * mb);
        properties.setMaxMemoryLimitKb(524_288);
        assertThat(client.compileMemoryLimitBytes(512 * mb, 262_144L)).isEqualTo(512 * mb);

        long second = 1_000_000_000L;
        assertThat(client.compileCpuLimitNs(2 * second)).isEqualTo(20 * second);
        properties.setCompileMaxTimeMillis(5_000L);
        assertThat(client.compileCpuLimitNs(2 * second)).isEqualTo(5 * second);
        properties.setCompileTimeMultiplier(2L);
        assertThat(client.compileCpuLimitNs(second)).isEqualTo(2 * second);
    }

    private SandboxClient client() {
        return new SandboxClient(properties, packageCache, submissionMapper, new DefaultOutputComparator(), mapper,
                new SandboxExecutionClient(properties));
    }

    private JudgeTaskMessage task(ContestMode mode) {
        return new JudgeTaskMessage(SUBMISSION_ID, PROBLEM_ID, 7L, null, null, null, null, mode, "cpp", "trace",
                1000, 262144L);
    }

    private PreparedTestcaseCase testcase(long id, String input, Integer score, boolean sample) throws IOException {
        Path in = Files.writeString(dir.resolve(id + ".in"), input);
        Path out = Files.writeString(dir.resolve(id + ".out"), input);
        return new PreparedTestcaseCase(id, String.valueOf(id), in, out, sample, null, score, (int) id);
    }

    private void usePackage(TestcaseCheckerType checkerType, PreparedTestcaseCase... cases) throws IOException {
        Path checker = checkerType == TestcaseCheckerType.CUSTOM
                ? Files.writeString(dir.resolve("checker.cpp"), "int main() { return 0; }")
                : null;
        PreparedTestcasePackage testcasePackage = new PreparedTestcasePackage(5L, PROBLEM_ID, "sha", dir, checkerType,
                checkerType == TestcaseCheckerType.CUSTOM ? "cpp" : null, checker,
                checkerType == TestcaseCheckerType.CUSTOM ? TestcaseCheckerProtocol.AIOJ_JSON : null, List.of(cases));
        when(packageCache.prepareActivePackage(PROBLEM_ID)).thenReturn(Optional.of(testcasePackage));
    }

    private void handleRun(HttpExchange exchange) throws IOException {
        JsonNode command = mapper.readTree(exchange.getRequestBody()).path("cmd").path(0);
        List<String> args = new ArrayList<>();
        command.path("args").forEach(arg -> args.add(arg.asString("")));
        Map<String, Object> result;
        if (args.contains("main.cpp")) {
            result = sandboxResult(compileStatus, "", "Accepted".equals(compileStatus) ? Map.of("main", "exe-1") : null);
        } else if (args.contains("checker.cpp")) {
            result = sandboxResult(checkerCompileStatus, "",
                    "Accepted".equals(checkerCompileStatus) ? Map.of("checker", "chk-1") : null, checkerStderr);
        } else if (!args.isEmpty() && "./checker".equals(args.get(0))) {
            String verdict = "{\"status\":\"ACCEPTED\",\"score\":" + args.get(4) + "}";
            result = sandboxResult(checkerRunStatus, "Accepted".equals(checkerRunStatus) ? verdict : "", null, checkerStderr);
        } else {
            String stdin = command.path("files").path(0).path("content").asString("");
            programInputs.add(stdin);
            if ("tle".equals(stdin)) {
                result = sandboxResult("Time Limit Exceeded", "", null);
            } else {
                result = sandboxResult("Accepted", "wa".equals(stdin) ? "something else" : stdin, null);
            }
        }
        byte[] body = mapper.writeValueAsBytes(List.of(result));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Map<String, Object> sandboxResult(String status, String stdout, Map<String, String> fileIds) {
        return sandboxResult(status, stdout, fileIds, "");
    }

    private static Map<String, Object> sandboxResult(String status, String stdout, Map<String, String> fileIds,
                                                     String stderr) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("exitStatus", "Accepted".equals(status) ? 0 : 1);
        result.put("time", 3_000_000L);
        result.put("memory", 1_048_576L);
        result.put("runTime", 3_000_000L);
        result.put("files", Map.of("stdout", stdout, "stderr", stderr));
        if (fileIds != null) {
            result.put("fileIds", fileIds);
        }
        return result;
    }
}
