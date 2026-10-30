package com.aioj.next.ai.domain.draftagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Performs the non-executing trust-boundary validation for manually uploaded
 * problem-draft testcase packages. Only bounded metadata is returned; testcase
 * inputs and expected outputs never leave this class.
 */
@Service
public class DraftTestcasePackageFormatValidator {
    static final int MAX_ENTRIES = 500;
    static final int MAX_CASES = 200;
    static final long MAX_UNCOMPRESSED_BYTES = 100L * 1024L * 1024L;
    static final long MAX_ENTRY_BYTES = 8L * 1024L * 1024L;
    static final long MAX_MANIFEST_BYTES = 1024L * 1024L;
    private static final String MANIFEST_PATH = "manifest.json";
    private static final int BUFFER_SIZE = 64 * 1024;

    private final ObjectMapper objectMapper;

    public DraftTestcasePackageFormatValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(Path archive) {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw invalid("Testcase package archive is missing");
        }
        Map<String, EntryMetadata> entries = new HashMap<>();
        Set<String> foldedPaths = new HashSet<>();
        byte[] manifestBytes = null;
        int entryCount = 0;
        long totalUncompressed = 0L;
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            Enumeration<ZipArchiveEntry> enumeration = zip.getEntries();
            while (enumeration.hasMoreElements()) {
                ZipArchiveEntry entry = enumeration.nextElement();
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw invalid("Testcase package contains more than 500 entries");
                }
                if (entry.isUnixSymlink()) {
                    throw invalid("Testcase package must not contain symbolic links");
                }
                if (!zip.canReadEntryData(entry)) {
                    throw invalid("Testcase package contains an unsupported or encrypted entry");
                }
                String path = normalizeEntryPath(entry.getName(), entry.isDirectory());
                String folded = path.toLowerCase(Locale.ROOT);
                if (!foldedPaths.add(folded)) {
                    throw invalid("Testcase package contains duplicate entry names ignoring case");
                }
                assertNoFileDirectoryConflict(entries, path, entry.isDirectory());
                if (entry.isDirectory()) {
                    entries.put(path, new EntryMetadata(true, 0L));
                    continue;
                }
                long perEntryLimit = MANIFEST_PATH.equals(path) ? MAX_MANIFEST_BYTES : MAX_ENTRY_BYTES;
                ByteArrayOutputStream manifest = MANIFEST_PATH.equals(path) ? new ByteArrayOutputStream() : null;
                long entryBytes;
                // The entry and total limits count the bytes actually read, which is what stops a
                // decompression bomb; a compression-ratio check would also refuse real repetitive max tests.
                try (InputStream input = zip.getInputStream(entry)) {
                    entryBytes = consumeBounded(input, manifest, perEntryLimit,
                            MAX_UNCOMPRESSED_BYTES - totalUncompressed);
                }
                totalUncompressed = safeAdd(totalUncompressed, entryBytes);
                if (totalUncompressed > MAX_UNCOMPRESSED_BYTES) {
                    throw invalid("Testcase package exceeds the 100 MiB decompressed size limit");
                }
                entries.put(path, new EntryMetadata(false, entryBytes));
                if (manifest != null) {
                    manifestBytes = manifest.toByteArray();
                }
            }
        } catch (ValidationException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw invalid("Testcase package is not a valid readable ZIP archive");
        }

        ManifestPayload manifest = parseManifest(manifestBytes);
        List<ManifestCase> cases = manifest.cases();
        if (cases == null || cases.isEmpty()) {
            throw invalid("manifest.json must contain at least one testcase");
        }
        if (cases.size() > MAX_CASES) {
            throw invalid("manifest.json contains more than 200 testcases");
        }

        Set<String> inputPaths = new HashSet<>();
        Set<String> caseNames = new HashSet<>();
        long totalInputBytes = 0L;
        long totalOutputBytes = 0L;
        long largestCaseBytes = 0L;
        for (int index = 0; index < cases.size(); index++) {
            ManifestCase testcase = cases.get(index);
            if (testcase == null) {
                throw invalid("manifest.json contains an empty testcase entry");
            }
            String inputPath = normalizeManifestPath(testcase.input(), "input");
            String outputPath = normalizeManifestPath(testcase.output(), "output");
            EntryMetadata input = requireRegularEntry(entries, inputPath, "input");
            EntryMetadata output = requireRegularEntry(entries, outputPath, "output");
            if (!inputPaths.add(inputPath.toLowerCase(Locale.ROOT))) {
                throw invalid("manifest.json reuses a testcase input file");
            }
            if (StringUtils.hasText(testcase.name())
                    && !caseNames.add(testcase.name().trim().toLowerCase(Locale.ROOT))) {
                throw invalid("manifest.json contains duplicate testcase names");
            }
            totalInputBytes = safeAdd(totalInputBytes, input.sizeBytes());
            totalOutputBytes = safeAdd(totalOutputBytes, output.sizeBytes());
            largestCaseBytes = Math.max(largestCaseBytes, safeAdd(input.sizeBytes(), output.sizeBytes()));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("version", manifest.version().trim());
        summary.put("checkerType", "STANDARD");
        summary.put("entryCount", entryCount);
        summary.put("caseCount", cases.size());
        summary.put("totalUncompressedBytes", totalUncompressed);
        summary.put("totalInputBytes", totalInputBytes);
        summary.put("totalOutputBytes", totalOutputBytes);
        summary.put("largestCaseBytes", largestCaseBytes);
        return new ValidationResult(manifest.version().trim(), entryCount, cases.size(), totalUncompressed,
                totalInputBytes, totalOutputBytes, largestCaseBytes, Map.copyOf(summary));
    }

    private ManifestPayload parseManifest(byte[] bytes) {
        if (bytes == null) {
            throw invalid("Testcase package must contain manifest.json at the archive root");
        }
        try {
            ManifestPayload manifest = objectMapper.readValue(bytes, ManifestPayload.class);
            if (manifest == null || !StringUtils.hasText(manifest.version())) {
                throw invalid("manifest.json version is required");
            }
            if (manifest.checker() == null || !StringUtils.hasText(manifest.checker().type())
                    || !"STANDARD".equalsIgnoreCase(manifest.checker().type().trim())) {
                throw invalid("Only the STANDARD testcase checker is supported");
            }
            return manifest;
        } catch (ValidationException ex) {
            throw ex;
        } catch (JacksonException ex) {
            throw invalid("manifest.json is not valid JSON");
        }
    }

    private long consumeBounded(InputStream input, ByteArrayOutputStream capture, long entryLimit,
                                long remainingPackageBytes) throws IOException {
        if (remainingPackageBytes < 0) {
            throw invalid("Testcase package exceeds the decompressed size limit");
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total = safeAdd(total, read);
            if (total > entryLimit) {
                throw invalid("A testcase package entry exceeds its size limit");
            }
            if (total > remainingPackageBytes) {
                throw invalid("Testcase package exceeds the 100 MiB decompressed size limit");
            }
            if (capture != null) {
                capture.write(buffer, 0, read);
            }
        }
        return total;
    }

    private EntryMetadata requireRegularEntry(Map<String, EntryMetadata> entries, String path, String label) {
        EntryMetadata entry = entries.get(path);
        if (entry == null || entry.directory()) {
            throw invalid("manifest.json references a missing " + label + " file");
        }
        return entry;
    }

    private void assertNoFileDirectoryConflict(Map<String, EntryMetadata> entries, String path,
                                               boolean directory) {
        String foldedPath = path.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, EntryMetadata> existing : entries.entrySet()) {
            String foldedExisting = existing.getKey().toLowerCase(Locale.ROOT);
            if (foldedPath.startsWith(foldedExisting + "/") && !existing.getValue().directory()) {
                throw invalid("Testcase package contains a file/directory path conflict");
            }
            if (!directory && foldedExisting.startsWith(foldedPath + "/")) {
                throw invalid("Testcase package contains a file/directory path conflict");
            }
        }
    }

    private String normalizeManifestPath(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw invalid("manifest.json testcase " + field + " path is required");
        }
        return normalizeEntryPath(value, false);
    }

    private String normalizeEntryPath(String raw, boolean directory) {
        if (!StringUtils.hasText(raw) || raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0) {
            throw invalid("Testcase package contains an unsafe entry path");
        }
        String value = raw.trim();
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw invalid("Testcase package contains an absolute entry path");
        }
        while (directory && value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        String[] parts = value.split("/", -1);
        List<String> normalized = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw invalid("Testcase package contains path traversal or an ambiguous entry path");
            }
            if (part.contains(":") || part.chars().anyMatch(Character::isISOControl)) {
                throw invalid("Testcase package contains an unsafe entry path");
            }
            normalized.add(part);
        }
        return String.join("/", normalized);
    }

    private long safeAdd(long value, long addend) {
        return value > Long.MAX_VALUE - addend ? Long.MAX_VALUE : value + addend;
    }

    private ValidationException invalid(String message) {
        return new ValidationException(message);
    }

    public record ValidationResult(String version, int entryCount, int caseCount, long totalUncompressedBytes,
                                   long totalInputBytes, long totalOutputBytes, long largestCaseBytes,
                                   Map<String, Object> safeSummary) {
    }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }

    private record EntryMetadata(boolean directory, long sizeBytes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ManifestPayload(String version, ManifestChecker checker, List<ManifestCase> cases) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ManifestChecker(String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ManifestCase(String name, String input, String output) {
    }
}
