package com.aioj.next.ai.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.ProblemDraftTestcaseArtifactPreviewResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the head of a testcase zip for review. The central directory gives every
 * name and size, so each entry is opened only for its first bytes and the rest
 * of the package is never inflated.
 */
final class TestcaseArchivePreview {
    static final int MAX_ENTRIES = 100;
    static final int MAX_PREVIEW_BYTES = 8 * 1024;

    private TestcaseArchivePreview() {
    }

    static ProblemDraftTestcaseArtifactPreviewResponse read(Long artifactId, String fileName, Path zip) {
        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            List<ProblemDraftTestcaseArtifactPreviewResponse.Entry> entries = new ArrayList<>();
            int total = 0;
            Enumeration<? extends ZipEntry> all = archive.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                total++;
                if (entries.size() < MAX_ENTRIES) {
                    entries.add(entry(archive, entry));
                }
            }
            return new ProblemDraftTestcaseArtifactPreviewResponse(artifactId, fileName, total, entries);
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Testcase package could not be read");
        }
    }

    private static ProblemDraftTestcaseArtifactPreviewResponse.Entry entry(ZipFile archive, ZipEntry entry) throws IOException {
        byte[] head;
        try (InputStream input = archive.getInputStream(entry)) {
            head = input.readNBytes(MAX_PREVIEW_BYTES + 1);
        }
        boolean truncated = head.length > MAX_PREVIEW_BYTES;
        byte[] shown = truncated ? Arrays.copyOf(head, MAX_PREVIEW_BYTES) : head;
        String text = text(shown, truncated);
        return new ProblemDraftTestcaseArtifactPreviewResponse.Entry(entry.getName(), Math.max(0, entry.getSize()),
                text, truncated, text == null);
    }

    /** The bytes as UTF-8 text, or null when they are not text (a NUL byte or an invalid sequence). */
    private static String text(byte[] bytes, boolean truncated) {
        for (byte value : bytes) {
            if (value == 0) {
                return null;
            }
        }
        int end = truncated ? completeUtf8Prefix(bytes) : bytes.length;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, end))
                    .toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    /** Length of the longest prefix that does not end inside a multi-byte character cut by the preview limit. */
    private static int completeUtf8Prefix(byte[] bytes) {
        int lead = bytes.length - 1;
        while (lead >= 0 && bytes.length - lead <= 3 && (bytes[lead] & 0xC0) == 0x80) {
            lead--;
        }
        if (lead < 0) {
            return bytes.length;
        }
        int first = bytes[lead] & 0xFF;
        int length = first >= 0xF0 ? 4 : first >= 0xE0 ? 3 : first >= 0xC0 ? 2 : 1;
        return bytes.length - lead < length ? lead : bytes.length;
    }
}
