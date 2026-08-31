package com.aioj.next.ai.domain.draftagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DraftTestcasePackageFormatValidatorTest {
    @TempDir
    Path tempDir;

    private final DraftTestcasePackageFormatValidator validator =
            new DraftTestcasePackageFormatValidator(new ObjectMapper());

    @Test
    void validatesStandardManifestAndReturnsOnlyBoundedMetadata() throws Exception {
        Path archive = zip(Map.of(
                "manifest.json", manifest("tests/001.in", "tests/001.out"),
                "tests/001.in", "2\n",
                "tests/001.out", "4\n"
        ));

        DraftTestcasePackageFormatValidator.ValidationResult result = validator.validate(archive);

        assertThat(result.version()).isEqualTo("draft-v1");
        assertThat(result.caseCount()).isEqualTo(1);
        assertThat(result.entryCount()).isEqualTo(3);
        assertThat(result.totalInputBytes()).isEqualTo(2);
        assertThat(result.totalOutputBytes()).isEqualTo(2);
        assertThat(result.safeSummary())
                .containsEntry("checkerType", "STANDARD")
                .doesNotContainKeys("input", "output", "paths", "contents");
    }

    @Test
    void rejectsPathTraversalFromManifestAndArchiveEntries() throws Exception {
        Path manifestTraversal = zip(Map.of(
                "manifest.json", manifest("../secret.in", "tests/001.out"),
                "tests/001.out", "4\n"
        ));
        Path entryTraversal = zip(Map.of(
                "manifest.json", manifest("tests/001.in", "tests/001.out"),
                "../001.in", "2\n",
                "tests/001.in", "2\n",
                "tests/001.out", "4\n"
        ));

        assertThatThrownBy(() -> validator.validate(manifestTraversal))
                .isInstanceOf(DraftTestcasePackageFormatValidator.ValidationException.class)
                .hasMessageContaining("path traversal");
        assertThatThrownBy(() -> validator.validate(entryTraversal))
                .isInstanceOf(DraftTestcasePackageFormatValidator.ValidationException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void rejectsCaseInsensitiveDuplicateEntries() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("manifest.json", manifest("tests/001.in", "tests/001.out"));
        entries.put("tests/001.in", "2\n");
        entries.put("TESTS/001.IN", "3\n");
        entries.put("tests/001.out", "4\n");

        assertThatThrownBy(() -> validator.validate(zip(entries)))
                .isInstanceOf(DraftTestcasePackageFormatValidator.ValidationException.class)
                .hasMessageContaining("duplicate entry names ignoring case");
    }

    @Test
    void rejectsSymlinkEntries() throws Exception {
        Path archive = tempDir.resolve("symlink.zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive)) {
            add(output, "manifest.json", manifest("tests/001.in", "tests/001.out"));
            add(output, "tests/001.out", "4\n");
            ZipArchiveEntry symlink = new ZipArchiveEntry("tests/001.in");
            symlink.setUnixMode(UnixStat.LINK_FLAG | 0777);
            byte[] target = "../secret".getBytes(StandardCharsets.UTF_8);
            symlink.setSize(target.length);
            output.putArchiveEntry(symlink);
            output.write(target);
            output.closeArchiveEntry();
        }

        assertThatThrownBy(() -> validator.validate(archive))
                .isInstanceOf(DraftTestcasePackageFormatValidator.ValidationException.class)
                .hasMessageContaining("symbolic links");
    }

    @Test
    void rejectsNonStandardCheckerAndCompressionBomb() throws Exception {
        Path customChecker = zip(Map.of(
                "manifest.json", manifest("tests/001.in", "tests/001.out")
                        .replace("STANDARD", "CUSTOM"),
                "tests/001.in", "2\n",
                "tests/001.out", "4\n"
        ));
        Map<String, byte[]> bombEntries = new LinkedHashMap<>();
        bombEntries.put("manifest.json", manifest("tests/001.in", "tests/001.out")
                .getBytes(StandardCharsets.UTF_8));
        bombEntries.put("tests/001.in", new byte[2 * 1024 * 1024]);
        bombEntries.put("tests/001.out", "4\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator.validate(customChecker))
                .isInstanceOf(DraftTestcasePackageFormatValidator.ValidationException.class)
                .hasMessageContaining("STANDARD");
        assertThatThrownBy(() -> validator.validate(zipBytes(bombEntries)))
                .isInstanceOf(DraftTestcasePackageFormatValidator.ValidationException.class)
                .hasMessageContaining("compression ratio");
    }

    @Test
    void enforcesEntryCaseAndPerEntrySizeLimits() throws Exception {
        Map<String, String> tooManyEntries = new LinkedHashMap<>();
        tooManyEntries.put("manifest.json", manifest("tests/001.in", "tests/001.out"));
        for (int index = 0; index < DraftTestcasePackageFormatValidator.MAX_ENTRIES; index++) {
            tooManyEntries.put("extras/" + index + ".txt", "x");
        }
        StringBuilder cases = new StringBuilder();
        for (int index = 0; index <= DraftTestcasePackageFormatValidator.MAX_CASES; index++) {
            if (index > 0) cases.append(',');
            cases.append("{\"name\":\"case-").append(index)
                    .append("\",\"input\":\"tests/001.in\",\"output\":\"tests/001.out\"}");
        }
        Path tooManyCases = zip(Map.of(
                "manifest.json", "{\"version\":\"draft-v1\",\"checker\":{\"type\":\"STANDARD\"},\"cases\":["
                        + cases + "]}",
                "tests/001.in", "2\n",
                "tests/001.out", "4\n"
        ));
        Map<String, byte[]> largeEntry = new LinkedHashMap<>();
        largeEntry.put("manifest.json", manifest("tests/001.in", "tests/001.out")
                .getBytes(StandardCharsets.UTF_8));
        largeEntry.put("tests/001.in", new byte[(int) DraftTestcasePackageFormatValidator.MAX_ENTRY_BYTES + 1]);
        largeEntry.put("tests/001.out", "4\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator.validate(zip(tooManyEntries)))
                .isInstanceOf(DraftTestcasePackageFormatValidator.ValidationException.class)
                .hasMessageContaining("more than 500 entries");
        assertThatThrownBy(() -> validator.validate(tooManyCases))
                .isInstanceOf(DraftTestcasePackageFormatValidator.ValidationException.class)
                .hasMessageContaining("more than 200 testcases");
        assertThatThrownBy(() -> validator.validate(zipBytes(largeEntry)))
                .isInstanceOf(DraftTestcasePackageFormatValidator.ValidationException.class)
                .hasMessageContaining("entry exceeds its size limit");
    }

    private Path zip(Map<String, String> entries) throws IOException {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        entries.forEach((name, value) -> bytes.put(name, value.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(bytes);
    }

    private Path zipBytes(Map<String, byte[]> entries) throws IOException {
        Path archive = Files.createTempFile(tempDir, "package-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return archive;
    }

    private void add(ZipArchiveOutputStream output, String path, String value) throws IOException {
        byte[] content = value.getBytes(StandardCharsets.UTF_8);
        ZipArchiveEntry entry = new ZipArchiveEntry(path);
        entry.setSize(content.length);
        output.putArchiveEntry(entry);
        output.write(content);
        output.closeArchiveEntry();
    }

    private String manifest(String input, String output) {
        return """
                {"version":"draft-v1","checker":{"type":"STANDARD"},"cases":[
                  {"name":"hidden-1","input":"%s","output":"%s","sample":false}
                ]}
                """.formatted(input, output);
    }
}
