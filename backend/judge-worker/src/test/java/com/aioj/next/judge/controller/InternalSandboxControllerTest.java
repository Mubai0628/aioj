package com.aioj.next.judge.controller;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.judge.domain.SandboxExecutionClient;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalSandboxControllerTest {
    private static final String TOKEN = "dev-token";

    @Test
    void compileNormalizesCppAliasesBeforeSandboxExecution() {
        SandboxExecutionClient client = mock(SandboxExecutionClient.class);
        when(client.compileSource(eq("cpp"), eq("int main(){return 0;}"), anyLong(), anyLong()))
                .thenReturn(new SandboxExecutionClient.CompileOutcome(
                        false,
                        "compiled-main",
                        "Accepted",
                        1L,
                        1L,
                        "",
                        0,
                        1L
                ));
        InternalSandboxController controller = controller(client);

        InternalSandboxController.CompileResponse response = controller.compile(
                TOKEN,
                new InternalSandboxController.CompileRequest(
                        "C++",
                        "int main(){return 0;}",
                        1000,
                        262144
                )
        ).data();

        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(client).compileSource(eq("cpp"), eq("int main(){return 0;}"), anyLong(), anyLong());
    }

    @Test
    void unsupportedLanguageFailsBeforeSandboxExecution() {
        SandboxExecutionClient client = mock(SandboxExecutionClient.class);
        InternalSandboxController controller = controller(client);

        assertThatThrownBy(() -> controller.compile(
                TOKEN,
                new InternalSandboxController.CompileRequest(
                        "go",
                        "package main",
                        1000,
                        262144
                )
        ))
                .isInstanceOfSatisfying(DomainException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(client, never()).compileSource(eq("go"), eq("package main"), anyLong(), anyLong());
    }

    @Test
    void normalizeLanguageAcceptsPythonAliases() {
        assertThat(InternalSandboxController.normalizeLanguage("py")).isEqualTo("python");
        assertThat(InternalSandboxController.normalizeLanguage("python3")).isEqualTo("python");
    }

    @Test
    void runOneClampsTimeMemoryAndStdoutBudgets() {
        SandboxExecutionClient client = mock(SandboxExecutionClient.class);
        when(client.runSource(eq("python"), eq("print(1)"), eq(null), eq(""), anyLong(), anyLong(), anyInt()))
                .thenReturn(new SandboxExecutionClient.RunOutcome(
                        "Accepted", "Accepted", 1L, 1L, "1\n", "", 0, 1L,
                        null, null, Map.of(), "ACCEPTED", 1));
        InternalSandboxController controller = controller(client);

        InternalSandboxController.RunResponse response = controller.runOne(TOKEN,
                new InternalSandboxController.RunRequest("python", "print(1)", null, "",
                        Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)).data();

        ArgumentCaptor<Long> cpu = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> memory = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> stdout = ArgumentCaptor.forClass(Integer.class);
        verify(client).runSource(eq("python"), eq("print(1)"), eq(null), eq(""), cpu.capture(), memory.capture(),
                stdout.capture());
        assertThat(cpu.getValue()).isEqualTo(SandboxExecutionClient.millisToNanos(30_000));
        assertThat(memory.getValue()).isEqualTo(SandboxExecutionClient.kbToBytes(1_048_576));
        assertThat(stdout.getValue()).isEqualTo(4 * 1024 * 1024);
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.procPeak()).isEqualTo(1);
    }

    @Test
    void runBatchRejectsTooManyCasesBeforeExecution() {
        SandboxExecutionClient client = mock(SandboxExecutionClient.class);
        InternalSandboxController controller = controller(client);
        List<String> inputs = java.util.Collections.nCopies(201, "1\n");

        assertThatThrownBy(() -> controller.runBatch(TOKEN,
                new InternalSandboxController.RunBatchRequest("python", "print(1)", null, inputs,
                        1000, 262_144, 65_536)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("too many inputs");
        verify(client, never()).runSource(eq("python"), eq("print(1)"), eq(null), eq("1\n"),
                anyLong(), anyLong(), anyInt());
    }

    @Test
    void runOnePreservesDetailedSandboxFailureCode() {
        SandboxExecutionClient client = mock(SandboxExecutionClient.class);
        when(client.runSource(eq("python"), eq("while True: pass"), eq(null), eq(""), anyLong(), anyLong(), anyInt()))
                .thenReturn(new SandboxExecutionClient.RunOutcome(
                        "Time Limit Exceeded", "Time Limit Exceeded", 1000L, 1L, "", "", null, 1100L,
                        null, null, Map.of(), "TIME_LIMIT_EXCEEDED", 1));
        InternalSandboxController controller = controller(client);

        InternalSandboxController.RunResponse response = controller.runOne(TOKEN,
                new InternalSandboxController.RunRequest("python", "while True: pass", null, "",
                        1000, 262_144, 65_536)).data();

        assertThat(response.status()).isEqualTo("TIME_LIMIT_EXCEEDED");
    }

    @Test
    void runPythonScriptParsesPureCollectorJson() {
        InternalSandboxController.PythonScriptResponse response = runPythonScriptWithOutcome("""
                {"files":{"001.in":"1 2\\n","001.out":"3\\n"}}
                """, null, null);

        assertThat(response.generatedFiles()).containsEntry("001.in", "1 2\n");
        assertThat(response.generatedFiles()).containsEntry("001.out", "3\n");
    }

    @Test
    void runPythonScriptUsesRequestBudgetDirectly() {
        SandboxExecutionClient client = mock(SandboxExecutionClient.class);
        when(client.runPythonScript(eq("script"), anyLong(), anyLong(), anyInt(), eq(3), eq("OFFICIAL_INPUTS")))
                .thenReturn(new SandboxExecutionClient.RunOutcome(
                        "Accepted",
                        "Accepted",
                        1L,
                        1L,
                        "",
                        "",
                        0,
                        1L,
                        "{\"files\":{}}"
                ));
        InternalSandboxController controller = controller(client);

        controller.runPythonScript(
                TOKEN,
                new InternalSandboxController.PythonScriptRequest("script", 30_000, 524_288, 3,
                        "OFFICIAL_INPUTS")
        );

        ArgumentCaptor<Long> cpuLimitCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> memoryLimitCaptor = ArgumentCaptor.forClass(Long.class);
        verify(client).runPythonScript(eq("script"), cpuLimitCaptor.capture(), memoryLimitCaptor.capture(), anyInt(),
                eq(3), eq("OFFICIAL_INPUTS"));
        assertThat(cpuLimitCaptor.getValue()).isEqualTo(SandboxExecutionClient.millisToNanos(30_000L));
        assertThat(memoryLimitCaptor.getValue()).isEqualTo(SandboxExecutionClient.kbToBytes(524_288L));
    }

    @Test
    void officialPackageWithoutCachedFileIdBecomesStructuredFailure() {
        SandboxExecutionClient client = mock(SandboxExecutionClient.class);
        when(client.runOfficialPackageScript(eq("script"), eq("cpp"), eq("int main(){}"),
                anyLong(), anyLong(), anyInt(), eq(3), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(new SandboxExecutionClient.RunOutcome(
                        "Accepted", "Accepted", 1L, 1L, "", "", 0, 1L,
                        null,
                        """
                                {"status":"PASSED","packageFileName":".aioj_official_hidden.zip",\
                                "packageFileSizeBytes":40000000,"caseCount":3}
                                """,
                        Map.of(), "ACCEPTED", 1));
        InternalSandboxController controller = controller(client);

        InternalSandboxController.PythonScriptResponse response = controller.runPythonScript(
                TOKEN,
                new InternalSandboxController.PythonScriptRequest(
                        "script", 30_000, 524_288, 3, "OFFICIAL_PACKAGE",
                        "cpp", "int main(){}", 1000, 262_144,
                        8L * 1024L * 1024L, 50L * 1024L * 1024L
                )
        ).data();

        assertThat(response.officialPackage()).isNotNull();
        assertThat(response.officialPackage().status()).isEqualTo("FAILED");
        assertThat(response.officialPackage().errorCode()).isEqualTo("PACKAGE_CACHE_FILE_MISSING");
        assertThat(response.officialPackage().errorMessage()).contains("copy-out capacity");
        assertThat(response.officialPackage().packageFileId()).isNull();
        assertThat(response.officialPackage().packageFileSizeBytes()).isEqualTo(40_000_000L);
    }

    @Test
    void runPythonScriptParsesCollectorJsonAfterScriptLogs() {
        InternalSandboxController.PythonScriptResponse response = runPythonScriptWithOutcome("""
                Generated 3 test cases in /box/testcases
                {"files":{"001.in":"1 2\\n","001.out":"3\\n","002.in":"2 3\\n","002.out":"5\\n"}}
                """, null, null);

        assertThat(response.stdout()).contains("Generated 3 test cases");
        assertThat(response.generatedFiles()).containsEntry("001.in", "1 2\n");
        assertThat(response.generatedFiles()).containsEntry("002.out", "5\n");
    }

    @Test
    void runPythonScriptParsesCopyOutCollectorJsonBeforeStdoutLogs() {
        InternalSandboxController.PythonScriptResponse response = runPythonScriptWithOutcome(
                "Generated 15 test cases\n",
                """
                        {"files":{"001.in":"1 2\\n","001.out":"3\\n","002.in":"2 3\\n","002.out":"5\\n"},"generatedPairCount":15}
                        """,
                7
        );

        assertThat(response.stdout()).contains("Generated 15 test cases");
        assertThat(response.generatedFiles()).containsEntry("001.in", "1 2\n");
        assertThat(response.generatedFiles()).containsEntry("002.out", "5\n");
        assertThat(response.generatedPairCount()).isEqualTo(15);
    }

    @Test
    void runPythonScriptParsesCopyOutCollectorJsonFromCustomSubdirectory() {
        InternalSandboxController.PythonScriptResponse response = runPythonScriptWithOutcome(
                "Generated custom cases\n",
                """
                        {"files":{"hsr_cases_large/case01.in":"2 1 1\\n1 2\\n","hsr_cases_large/case01.out":"1\\n"},"crossCheckInputs":{"testcases/stress_small_001.in":"3\\n1 2 3\\n"},"generatedPairCount":1,"generatedFileCount":2,"generatedInputCount":1,"generatedOutputCount":1}
                        """,
                1
        );

        assertThat(response.generatedFiles()).containsEntry("hsr_cases_large/case01.in", "2 1 1\n1 2\n");
        assertThat(response.generatedFiles()).containsEntry("hsr_cases_large/case01.out", "1\n");
        assertThat(response.generatedPairCount()).isEqualTo(1);
        assertThat(response.generatedFileCount()).isEqualTo(2);
        assertThat(response.generatedInputCount()).isEqualTo(1);
        assertThat(response.generatedOutputCount()).isEqualTo(1);
        assertThat(response.crossCheckInputs()).containsEntry("testcases/stress_small_001.in", "3\n1 2 3\n");
    }

    @Test
    void runPythonScriptKeepsUnpairedGeneratedFileCounts() {
        InternalSandboxController.PythonScriptResponse response = runPythonScriptWithOutcome(
                "",
                """
                        {"files":{},"generatedPairCount":0,"generatedFileCount":2,"generatedInputCount":2,"generatedOutputCount":0,"scanRoot":"testcases"}
                        """,
                3
        );

        assertThat(response.generatedFiles()).isEmpty();
        assertThat(response.generatedPairCount()).isZero();
        assertThat(response.generatedInputCount()).isEqualTo(2);
        assertThat(response.generatedOutputCount()).isZero();
        assertThat(response.scanRoot()).isEqualTo("testcases");
    }

    @Test
    void runPythonScriptParsesOfficialInputCollectorJson() {
        InternalSandboxController.PythonScriptResponse response = runPythonScriptWithOutcome(
                "",
                """
                        {"files":{"001.in":"1 2\\n","002.in":"2 3\\n"},"generatedPairCount":0,"generatedFileCount":2,"generatedInputCount":2,"generatedOutputCount":0,"manifestJson":"{\\"cases\\":2}","scanRoot":"testcases"}
                        """,
                2,
                "OFFICIAL_INPUTS"
        );

        assertThat(response.generatedFiles()).containsEntry("001.in", "1 2\n");
        assertThat(response.generatedFiles()).containsEntry("002.in", "2 3\n");
        assertThat(response.generatedPairCount()).isZero();
        assertThat(response.generatedInputCount()).isEqualTo(2);
        assertThat(response.generatedOutputCount()).isZero();
        assertThat(response.manifestJson()).isEqualTo("{\"cases\":2}");
        assertThat(response.scanRoot()).isEqualTo("testcases");
    }

    @Test
    void runPythonScriptReturnsEmptyFilesWhenCollectorJsonIsMissing() {
        InternalSandboxController.PythonScriptResponse response = runPythonScriptWithOutcome("""
                Generated 3 test cases
                done
                """, null, null);

        assertThat(response.generatedFiles()).isEmpty();
        assertThat(response.generatedInputCount()).isZero();
        assertThat(response.generatedOutputCount()).isZero();
    }

    private InternalSandboxController controller(SandboxExecutionClient client) {
        JudgeWorkerProperties properties = new JudgeWorkerProperties();
        properties.setInternalApiToken(TOKEN);
        return new InternalSandboxController(client, properties, new ObjectMapper());
    }

    private InternalSandboxController.PythonScriptResponse runPythonScriptWithOutcome(
            String stdout,
            String generatedFilesJson,
            Integer targetCaseCount
    ) {
        return runPythonScriptWithOutcome(stdout, generatedFilesJson, targetCaseCount, null);
    }

    private InternalSandboxController.PythonScriptResponse runPythonScriptWithOutcome(
            String stdout,
            String generatedFilesJson,
            Integer targetCaseCount,
            String collectMode
    ) {
        int expectedTargetCaseCount = targetCaseCount == null ? 3 : targetCaseCount;
        SandboxExecutionClient client = mock(SandboxExecutionClient.class);
        when(client.runPythonScript(eq("script"), anyLong(), anyLong(), anyInt(), eq(expectedTargetCaseCount),
                eq(collectMode)))
                .thenReturn(new SandboxExecutionClient.RunOutcome(
                        "Accepted",
                        "Accepted",
                        1L,
                        1L,
                        stdout,
                        "",
                        0,
                        1L,
                        generatedFilesJson
                ));
        InternalSandboxController controller = controller(client);
        return controller.runPythonScript(
                TOKEN,
                new InternalSandboxController.PythonScriptRequest("script", 1000, 262144, targetCaseCount,
                        collectMode)
        ).data();
    }
}
