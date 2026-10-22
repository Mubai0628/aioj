package com.aioj.next.ai.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TestcaseArchivePreviewTest {
    @TempDir
    Path temp;

    @Test
    void showsTextHeadsAndMarksBinaryAndCutFiles() throws IOException {
        // "测" is three bytes; 8191 ASCII bytes put it across the 8 KB cut.
        String longText = "a".repeat(TestcaseArchivePreview.MAX_PREVIEW_BYTES - 1) + "测试";
        Path zip = zip(temp.resolve("package.zip"), zipOut -> {
            zipOut.putNextEntry(new ZipEntry("testcases/"));
            put(zipOut, "testcases/001.in", "1 2\n".getBytes(StandardCharsets.UTF_8));
            put(zipOut, "testcases/001.out", longText.getBytes(StandardCharsets.UTF_8));
            put(zipOut, "testcases/data.bin", new byte[]{1, 0, 2, 3});
        });

        var preview = TestcaseArchivePreview.read(7L, "package.zip", zip);

        assertThat(preview.artifactId()).isEqualTo(7L);
        assertThat(preview.totalEntries()).isEqualTo(3);
        var input = preview.entries().get(0);
        assertThat(input.name()).isEqualTo("testcases/001.in");
        assertThat(input.preview()).isEqualTo("1 2\n");
        assertThat(input.sizeBytes()).isEqualTo(4);
        assertThat(input.truncated()).isFalse();
        var output = preview.entries().get(1);
        assertThat(output.truncated()).isTrue();
        assertThat(output.binary()).isFalse();
        assertThat(output.preview()).isEqualTo("a".repeat(TestcaseArchivePreview.MAX_PREVIEW_BYTES - 1));
        var binary = preview.entries().get(2);
        assertThat(binary.binary()).isTrue();
        assertThat(binary.preview()).isNull();
    }

    @Test
    void listsAtMostAHundredFilesButCountsThemAll() throws IOException {
        Path zip = zip(temp.resolve("many.zip"), zipOut -> {
            for (int i = 1; i <= 130; i++) {
                put(zipOut, "%03d.in".formatted(i), ("case " + i).getBytes(StandardCharsets.UTF_8));
            }
        });

        var preview = TestcaseArchivePreview.read(8L, "many.zip", zip);

        assertThat(preview.totalEntries()).isEqualTo(130);
        assertThat(preview.entries()).hasSize(TestcaseArchivePreview.MAX_ENTRIES);
        assertThat(preview.entries().get(99).name()).isEqualTo("100.in");
    }

    private interface ZipWriter {
        void write(ZipOutputStream zipOut) throws IOException;
    }

    private static Path zip(Path path, ZipWriter writer) throws IOException {
        try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zipOut = new ZipOutputStream(out)) {
            writer.write(zipOut);
        }
        return path;
    }

    private static void put(ZipOutputStream zipOut, String name, byte[] content) throws IOException {
        zipOut.putNextEntry(new ZipEntry(name));
        zipOut.write(content);
        zipOut.closeEntry();
    }
}
