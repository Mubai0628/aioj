package com.aioj.next.judge.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.judge.DraftTestcasePackageRunRequest;
import com.aioj.next.contract.judge.DraftTestcasePackageRunResponse;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import tools.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DraftTestcasePackageRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void validStandardPackageRunsEveryCaseAndReturnsOnlySafeAggregate() throws Exception {
        SandboxExecutionClient sandbox = mock(SandboxExecutionClient.class);
        when(sandbox.compileSource(eq("python"), any(), anyLong(), anyLong()))
                .thenReturn(new SandboxExecutionClient.CompileOutcome(
                        false, null, "Accepted", 0L, 0L, "", 0, 0L, 0));
        when(sandbox.runSource(eq("python"), any(), any(), any(), anyLong(), anyLong(), anyInt()))
                .thenReturn(new SandboxExecutionClient.RunOutcome(
                        "Accepted", "Accepted", 2L, 512L, "2\n", "", 0, 3L,
                        null, null, Map.of(), "ACCEPTED", 1));
        byte[] zip = packageZip(Map.of(
                "manifest.json", manifest("tests/001.in", "tests/001.out"),
                "tests/001.in", "1\n",
                "tests/001.out", "2\n"
        ));

        DraftTestcasePackageRunResponse response = runner(sandbox).run(file(zip), request(zip));

        assertThat(response.status()).isEqualTo("PASSED");
        assertThat(response.caseCount()).isEqualTo(1);
        assertThat(response.executedCaseCount()).isEqualTo(1);
        assertThat(response.passedCaseCount()).isEqualTo(1);
        assertThat(response.failureCounts()).isEmpty();
        assertThat(response.maxTimeMillis()).isEqualTo(2L);
        assertThat(response.maxMemoryKb()).isEqualTo(512L);
        assertThat(response.maxProcPeak()).isEqualTo(1);
        assertThat(response.toString()).doesNotContain("tests/001.in", "2\\n", "fileId");
    }

    @Test
    void rejectsTraversalBeforeSandboxExecution() throws Exception {
        SandboxExecutionClient sandbox = mock(SandboxExecutionClient.class);
        byte[] zip = packageZip(Map.of(
                "manifest.json", manifest("../escape.in", "tests/001.out"),
                "../escape.in", "1\n",
                "tests/001.out", "2\n"
        ));

        assertThatThrownBy(() -> runner(sandbox).run(file(zip), request(zip)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Unsafe draft testcase zip path");
    }

    @Test
    void rejectsCaseInsensitiveDuplicateEntries() throws Exception {
        SandboxExecutionClient sandbox = mock(SandboxExecutionClient.class);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("manifest.json", manifest("tests/A.in", "tests/001.out"));
        entries.put("tests/A.in", "1\n");
        entries.put("tests/a.in", "1\n");
        entries.put("tests/001.out", "2\n");
        byte[] zip = packageZip(entries);

        assertThatThrownBy(() -> runner(sandbox).run(file(zip), request(zip)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Duplicate testcase zip entry");
    }

    @Test
    void rejectsSymbolicLinkEntries() throws Exception {
        SandboxExecutionClient sandbox = mock(SandboxExecutionClient.class);
        byte[] zip = symlinkZip();

        assertThatThrownBy(() -> runner(sandbox).run(file(zip), request(zip)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("symbolic links");
    }

    @Test
    void rejectsZipBombCompressionRatio() throws Exception {
        SandboxExecutionClient sandbox = mock(SandboxExecutionClient.class);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("manifest.json", manifest("tests/001.in", "tests/001.out"));
        entries.put("tests/001.in", "0".repeat(1_000_000));
        entries.put("tests/001.out", "2\n");
        byte[] zip = packageZip(entries);

        assertThatThrownBy(() -> runner(sandbox).run(file(zip), request(zip)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("compression ratio");
    }

    @Test
    void rejectsPackageShaMismatch() throws Exception {
        SandboxExecutionClient sandbox = mock(SandboxExecutionClient.class);
        byte[] zip = packageZip(Map.of(
                "manifest.json", manifest("tests/001.in", "tests/001.out"),
                "tests/001.in", "1\n",
                "tests/001.out", "2\n"
        ));
        DraftTestcasePackageRunRequest badSha = new DraftTestcasePackageRunRequest(
                "0".repeat(64), (long) zip.length, "python", "print(2)", null, null,
                1000, 262_144, 10);

        assertThatThrownBy(() -> runner(sandbox).run(file(zip), badSha))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void stopsRemainingCasesWhenCumulativeWallTimeBudgetIsExceeded() throws Exception {
        SandboxExecutionClient sandbox = mock(SandboxExecutionClient.class);
        when(sandbox.compileSource(eq("python"), any(), anyLong(), anyLong()))
                .thenReturn(new SandboxExecutionClient.CompileOutcome(
                        false, null, "Accepted", 0L, 0L, "", 0, 0L, 0));
        when(sandbox.runSource(eq("python"), any(), any(), any(), anyLong(), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    Thread.sleep(10L);
                    return new SandboxExecutionClient.RunOutcome(
                            "Accepted", "Accepted", 2L, 512L, "2\n", "", 0, 3L,
                            null, null, Map.of(), "ACCEPTED", 1);
                });
        byte[] zip = packageZip(Map.of(
                "manifest.json", """
                        {"version":"draft-v1","checker":{"type":"STANDARD"},"cases":[
                          {"name":"a","input":"tests/001.in","output":"tests/001.out"},
                          {"name":"b","input":"tests/002.in","output":"tests/002.out"}
                        ]}
                        """,
                "tests/001.in", "1\n", "tests/001.out", "2\n",
                "tests/002.in", "1\n", "tests/002.out", "2\n"
        ));
        JudgeWorkerProperties properties = new JudgeWorkerProperties();
        properties.setCacheRoot(tempDir.toString());
        properties.setMaxDraftPackageRunMillis(1L);
        DraftTestcasePackageRunner runner = new DraftTestcasePackageRunner(
                properties, sandbox, new DefaultOutputComparator(), new ObjectMapper());

        DraftTestcasePackageRunResponse response = runner.run(file(zip), request(zip));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorCode()).isEqualTo("PACKAGE_WALL_TIME_BUDGET_EXCEEDED");
        assertThat(response.executedCaseCount()).isEqualTo(1);
        assertThat(response.failureCounts()).containsEntry("WALL_TIME_BUDGET_EXCEEDED", 1);
    }

    private DraftTestcasePackageRunner runner(SandboxExecutionClient sandbox) {
        JudgeWorkerProperties properties = new JudgeWorkerProperties();
        properties.setCacheRoot(tempDir.toString());
        return new DraftTestcasePackageRunner(properties, sandbox, new DefaultOutputComparator(), new ObjectMapper());
    }

    private DraftTestcasePackageRunRequest request(byte[] zip) throws Exception {
        return new DraftTestcasePackageRunRequest(
                sha256(zip), (long) zip.length, "python", "print(2)", null, null,
                1000, 262_144, 10);
    }

    private MockMultipartFile file(byte[] zip) {
        return new MockMultipartFile("package", "draft.zip", "application/zip", zip);
    }

    private String manifest(String input, String output) {
        return """
                {"version":"draft-v1","checker":{"type":"STANDARD"},"cases":[
                  {"name":"hidden-1","input":"%s","output":"%s","sample":false}
                ]}
                """.formatted(input, output);
    }

    private byte[] packageZip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private byte[] symlinkZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
            add(zip, "manifest.json", manifest("tests/001.in", "tests/001.out"));
            add(zip, "tests/001.out", "2\n");
            ZipArchiveEntry link = new ZipArchiveEntry("tests/001.in");
            link.setUnixMode(UnixStat.LINK_FLAG | 0777);
            byte[] target = "../outside".getBytes(StandardCharsets.UTF_8);
            link.setSize(target.length);
            zip.putArchiveEntry(link);
            zip.write(target);
            zip.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    private void add(ZipArchiveOutputStream zip, String path, String value) throws Exception {
        byte[] content = value.getBytes(StandardCharsets.UTF_8);
        ZipArchiveEntry entry = new ZipArchiveEntry(path);
        entry.setSize(content.length);
        zip.putArchiveEntry(entry);
        zip.write(content);
        zip.closeArchiveEntry();
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
