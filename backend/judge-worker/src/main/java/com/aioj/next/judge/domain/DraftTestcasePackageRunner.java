package com.aioj.next.judge.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.judge.DraftTestcasePackageRunRequest;
import com.aioj.next.contract.judge.DraftTestcasePackageRunResponse;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Executes an unimported testcase package using the same STANDARD comparison
 * semantics as normal judging. The package is streamed to an isolated temporary
 * directory, validated again at this trust boundary, then deleted in a finally
 * block. No sandbox file id or hidden testcase content crosses the API boundary.
 */
@Service
public class DraftTestcasePackageRunner {
    private static final Logger log = LoggerFactory.getLogger(DraftTestcasePackageRunner.class);
    private static final String MANIFEST_PATH = "manifest.json";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MANIFEST_MAX_BYTES = 1024L * 1024L;
    private static final long MAX_COMPRESSION_RATIO = 200L;

    private final JudgeWorkerProperties properties;
    private final SandboxExecutionClient sandboxExecutionClient;
    private final OutputComparator outputComparator;
    private final ObjectMapper objectMapper;
    private final Path runRoot;

    public DraftTestcasePackageRunner(JudgeWorkerProperties properties,
                                      SandboxExecutionClient sandboxExecutionClient,
                                      OutputComparator outputComparator,
                                      ObjectMapper objectMapper) {
        this.properties = properties;
        this.sandboxExecutionClient = sandboxExecutionClient;
        this.outputComparator = outputComparator;
        this.objectMapper = objectMapper;
        this.runRoot = Path.of(properties.getCacheRoot(), "draft-package-runs").toAbsolutePath().normalize();
    }

    public DraftTestcasePackageRunResponse run(MultipartFile packageFile, DraftTestcasePackageRunRequest request) {
        validateRequest(request);
        if (packageFile == null || packageFile.isEmpty()) {
            throw badRequest("Draft testcase package is required");
        }
        Path workDir = runRoot.resolve(UUID.randomUUID().toString()).normalize();
        ensureUnder(runRoot, workDir);
        String primaryFileId = null;
        String referenceFileId = null;
        try {
            Files.createDirectories(workDir);
            StoredPackage stored = storePackage(packageFile, request, workDir.resolve("artifact.zip"));
            ValidatedPackage testcasePackage = validateAndExtract(stored.path(), workDir.resolve("contents"), request);

            SandboxExecutionClient.CompileOutcome primaryCompile = compileIfRequired(
                    request.language(), request.sourceCode(), request.timeLimitMillis(), request.memoryLimitKb());
            if (primaryCompile.failed()) {
                return failed(stored, testcasePackage.cases().size(), "COMPILE_ERROR",
                        "Standard solution compilation failed", Map.of("COMPILE_ERROR", testcasePackage.cases().size()),
                        primaryCompile.timeMillis(), primaryCompile.memoryKb(), primaryCompile.runTimeMillis(),
                        primaryCompile.procPeak(), false, 0);
            }
            primaryFileId = primaryCompile.fileId();

            boolean referenceRequested = StringUtils.hasText(request.referenceSourceCode());
            if (referenceRequested) {
                SandboxExecutionClient.CompileOutcome referenceCompile = compileIfRequired(
                        request.referenceLanguage(), request.referenceSourceCode(), request.timeLimitMillis(),
                        request.memoryLimitKb());
                if (referenceCompile.failed()) {
                    return failed(stored, testcasePackage.cases().size(), "REFERENCE_COMPILE_ERROR",
                            "Reference solution compilation failed",
                            Map.of("REFERENCE_COMPILE_ERROR", testcasePackage.cases().size()),
                            referenceCompile.timeMillis(), referenceCompile.memoryKb(),
                            referenceCompile.runTimeMillis(), referenceCompile.procPeak(), true, 0);
                }
                referenceFileId = referenceCompile.fileId();
            }

            RunAggregate aggregate = executeAll(testcasePackage, request, primaryFileId, referenceFileId,
                    referenceRequested);
            boolean passed = aggregate.failedCases() == 0
                    && (!referenceRequested || aggregate.referencePassedCases() == testcasePackage.cases().size());
            String errorCode = passed ? null : aggregate.wallTimeBudgetExceeded()
                    ? "PACKAGE_WALL_TIME_BUDGET_EXCEEDED" : "PACKAGE_EXECUTION_FAILED";
            String message = passed ? "All testcase package cases passed" : aggregate.wallTimeBudgetExceeded()
                    ? "Testcase package exceeded the cumulative execution wall-time budget"
                    : "One or more testcase package cases failed";
            return new DraftTestcasePackageRunResponse(
                    passed ? "PASSED" : "FAILED",
                    errorCode,
                    message,
                    stored.sha256(), stored.sizeBytes(), testcasePackage.cases().size(),
                    aggregate.executedCases(), aggregate.passedCases(), aggregate.failedCases(),
                    Map.copyOf(aggregate.failureCounts()), aggregate.maxTimeMillis(), aggregate.maxMemoryKb(),
                    aggregate.maxRunTimeMillis(), aggregate.maxProcPeak(), referenceRequested,
                    aggregate.referencePassedCases()
            );
        } catch (DomainException ex) {
            throw ex;
        } catch (IOException ex) {
            throw badRequest("Failed to process draft testcase package");
        } finally {
            cleanupSandboxFile(primaryFileId);
            cleanupSandboxFile(referenceFileId);
            deleteRecursively(workDir);
        }
    }

    private StoredPackage storePackage(MultipartFile source, DraftTestcasePackageRunRequest request, Path target)
            throws IOException {
        long maxBytes = positive(properties.getMaxDraftPackageBytes(), 50L * 1024L * 1024L);
        if (source.getSize() > maxBytes) {
            throw badRequest("Draft testcase package exceeds the compressed size limit");
        }
        MessageDigest digest = sha256Digest();
        long size = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = source.getInputStream();
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > maxBytes) {
                    throw badRequest("Draft testcase package exceeds the compressed size limit");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (request.packageSizeBytes() != null && request.packageSizeBytes() >= 0
                && request.packageSizeBytes() != size) {
            throw badRequest("Draft testcase package size does not match metadata");
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        String expectedSha = normalizeSha256(request.packageSha256());
        if (!expectedSha.equals(sha256)) {
            throw badRequest("Draft testcase package SHA-256 does not match metadata");
        }
        return new StoredPackage(target, size, sha256);
    }

    private ValidatedPackage validateAndExtract(Path zipPath, Path extractRoot,
                                                DraftTestcasePackageRunRequest request) throws IOException {
        Files.createDirectories(extractRoot);
        Map<String, ExtractedEntry> entries = new LinkedHashMap<>();
        Set<String> foldedPaths = new HashSet<>();
        long totalBytes = 0L;
        int entryCount = 0;
        byte[] manifestBytes = null;
        try (ZipFile zipFile = ZipFile.builder().setPath(zipPath).get()) {
            Enumeration<ZipArchiveEntry> enumeration = zipFile.getEntries();
            while (enumeration.hasMoreElements()) {
                ZipArchiveEntry entry = enumeration.nextElement();
                entryCount++;
                if (entryCount > positive(properties.getMaxDraftPackageEntries(), 500)) {
                    throw badRequest("Draft testcase package has too many entries");
                }
                if (entry.isUnixSymlink()) {
                    throw badRequest("Draft testcase package must not contain symbolic links");
                }
                if (!zipFile.canReadEntryData(entry)) {
                    throw badRequest("Draft testcase package contains an unsupported or encrypted entry");
                }
                String path = normalizeZipPath(entry.getName());
                String folded = path.toLowerCase(Locale.ROOT);
                if (entries.containsKey(path) || !foldedPaths.add(folded)) {
                    throw badRequest("Duplicate testcase zip entry: " + path);
                }
                Path target = extractRoot.resolve(path).normalize();
                ensureUnder(extractRoot, target);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    entries.put(path, new ExtractedEntry(path, target, true, 0L));
                    continue;
                }
                Files.createDirectories(target.getParent());
                long entryLimit = MANIFEST_PATH.equals(path)
                        ? MANIFEST_MAX_BYTES
                        : positive(properties.getMaxDraftPackageEntryBytes(), 8L * 1024L * 1024L);
                long entryBytes;
                try (InputStream input = zipFile.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                             StandardOpenOption.WRITE)) {
                    entryBytes = copyBounded(input, output, entryLimit,
                            positive(properties.getMaxDraftPackageUncompressedBytes(), 100L * 1024L * 1024L)
                                    - totalBytes);
                }
                rejectCompressionBomb(entry, entryBytes);
                totalBytes = safeAdd(totalBytes, entryBytes);
                if (totalBytes > positive(properties.getMaxDraftPackageUncompressedBytes(),
                        100L * 1024L * 1024L)) {
                    throw badRequest("Draft testcase package is too large after decompression");
                }
                if (Files.isSymbolicLink(target) || !Files.isRegularFile(target)) {
                    throw badRequest("Draft testcase package contains an unsafe file entry");
                }
                entries.put(path, new ExtractedEntry(path, target, false, entryBytes));
                if (MANIFEST_PATH.equals(path)) {
                    manifestBytes = Files.readAllBytes(target);
                }
            }
        } catch (DomainException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw badRequest("Invalid draft testcase zip package");
        }
        if (manifestBytes == null) {
            throw badRequest("Draft testcase package manifest.json is required");
        }
        ManifestPayload manifest;
        try {
            manifest = objectMapper.readValue(manifestBytes, ManifestPayload.class);
        } catch (IOException ex) {
            throw badRequest("Draft testcase package manifest.json is invalid");
        }
        if (!StringUtils.hasText(manifest.version())) {
            throw badRequest("Draft testcase manifest version is required");
        }
        if (manifest.checker() != null && StringUtils.hasText(manifest.checker().type())
                && !"STANDARD".equalsIgnoreCase(manifest.checker().type().trim())) {
            throw badRequest("Draft testcase package acceptance supports STANDARD checker only");
        }
        if (manifest.cases() == null || manifest.cases().isEmpty()) {
            throw badRequest("Draft testcase manifest cases are required");
        }
        int configuredMax = positive(properties.getMaxDraftPackageCases(), 200);
        int requestedMax = request.maxCases() == null || request.maxCases() <= 0
                ? configuredMax : Math.min(request.maxCases(), configuredMax);
        if (manifest.cases().size() > requestedMax) {
            throw badRequest("Draft testcase package case count exceeds the execution limit");
        }
        List<PackageCase> cases = new ArrayList<>();
        Set<String> usedInputPaths = new HashSet<>();
        for (ManifestCase manifestCase : manifest.cases()) {
            if (manifestCase == null) {
                throw badRequest("Draft testcase manifest contains an empty case");
            }
            String inputPath = normalizeZipPath(manifestCase.input());
            String outputPath = normalizeZipPath(manifestCase.output());
            ExtractedEntry input = requireFile(entries, inputPath, "input");
            ExtractedEntry output = requireFile(entries, outputPath, "output");
            if (!usedInputPaths.add(inputPath)) {
                throw badRequest("Draft testcase manifest reuses an input path: " + inputPath);
            }
            cases.add(new PackageCase(input.file(), output.file(), output.sizeBytes()));
        }
        return new ValidatedPackage(cases);
    }

    private RunAggregate executeAll(ValidatedPackage testcasePackage, DraftTestcasePackageRunRequest request,
                                    String primaryFileId, String referenceFileId, boolean referenceRequested)
            throws IOException {
        int executed = 0;
        int passed = 0;
        int failed = 0;
        int referencePassed = 0;
        Map<String, Integer> failures = new LinkedHashMap<>();
        Long maxTime = 0L;
        Long maxMemory = 0L;
        Long maxRunTime = 0L;
        Integer maxProcesses = 0;
        boolean wallTimeBudgetExceeded = false;
        long startedNanos = System.nanoTime();
        long wallTimeBudgetNanos = safeMultiply(
                positive(properties.getMaxDraftPackageRunMillis(), 540_000L), 1_000_000L);
        for (int caseIndex = 0; caseIndex < testcasePackage.cases().size(); caseIndex++) {
            PackageCase testcase = testcasePackage.cases().get(caseIndex);
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Draft testcase package execution was cancelled");
            }
            if (executed > 0 && System.nanoTime() - startedNanos >= wallTimeBudgetNanos) {
                int remainingCases = testcasePackage.cases().size() - caseIndex;
                wallTimeBudgetExceeded = true;
                failed += remainingCases;
                failures.merge("WALL_TIME_BUDGET_EXCEEDED", remainingCases, Integer::sum);
                break;
            }
            String input = Files.readString(testcase.input(), StandardCharsets.UTF_8);
            int stdoutLimit = SandboxClient.stdoutCollectLimitBytes(testcase.expectedOutputBytes(),
                    properties.getStdoutBaseCollectLimitBytes(), properties.getStdoutExtraCollectBytes(),
                    properties.getStdoutMaxCollectLimitBytes());
            SandboxExecutionClient.RunOutcome outcome = sandboxExecutionClient.runSource(
                    request.language(), request.sourceCode(), primaryFileId, input,
                    SandboxExecutionClient.millisToNanos(timeLimit(request.timeLimitMillis())),
                    SandboxExecutionClient.kbToBytes(memoryLimit(request.memoryLimitKb())), stdoutLimit);
            executed++;
            maxTime = Math.max(maxTime, zero(outcome.timeMillis()));
            maxMemory = Math.max(maxMemory, zero(outcome.memoryKb()));
            maxRunTime = Math.max(maxRunTime, zero(outcome.runTimeMillis()));
            maxProcesses = Math.max(maxProcesses, outcome.procPeak() == null ? 0 : outcome.procPeak());
            String resultCode = compare(testcase.expected(), outcome);
            if ("ACCEPTED".equals(resultCode)) {
                passed++;
            } else {
                failed++;
                failures.merge(resultCode, 1, Integer::sum);
            }

            if (referenceRequested) {
                SandboxExecutionClient.RunOutcome reference = sandboxExecutionClient.runSource(
                        request.referenceLanguage(), request.referenceSourceCode(), referenceFileId, input,
                        SandboxExecutionClient.millisToNanos(timeLimit(request.timeLimitMillis())),
                        SandboxExecutionClient.kbToBytes(memoryLimit(request.memoryLimitKb())), stdoutLimit);
                maxTime = Math.max(maxTime, zero(reference.timeMillis()));
                maxMemory = Math.max(maxMemory, zero(reference.memoryKb()));
                maxRunTime = Math.max(maxRunTime, zero(reference.runTimeMillis()));
                maxProcesses = Math.max(maxProcesses,
                        reference.procPeak() == null ? 0 : reference.procPeak());
                String referenceCode = compare(testcase.expected(), reference);
                if ("ACCEPTED".equals(referenceCode)) {
                    referencePassed++;
                } else {
                    failures.merge("REFERENCE_" + referenceCode, 1, Integer::sum);
                }
            }
        }
        return new RunAggregate(executed, passed, failed, failures, maxTime, maxMemory, maxRunTime, maxProcesses,
                referencePassed, wallTimeBudgetExceeded);
    }

    private String compare(Path expected, SandboxExecutionClient.RunOutcome outcome) throws IOException {
        if (!"ACCEPTED".equals(outcome.resultCode())) {
            return outcome.resultCode();
        }
        byte[] actualBytes = outcome.stdout() == null
                ? new byte[0]
                : outcome.stdout().getBytes(StandardCharsets.UTF_8);
        try (InputStream expectedInput = Files.newInputStream(expected);
             InputStream actualInput = new ByteArrayInputStream(actualBytes)) {
            return outputComparator.compare(expectedInput, actualInput).accepted() ? "ACCEPTED" : "WRONG_ANSWER";
        }
    }

    private SandboxExecutionClient.CompileOutcome compileIfRequired(String language, String sourceCode,
                                                                     Integer timeLimitMillis,
                                                                     Integer memoryLimitKb) {
        return sandboxExecutionClient.compileSource(language, sourceCode,
                SandboxExecutionClient.millisToNanos(Math.min(safeMultiply(timeLimit(timeLimitMillis), 10L),
                        positive(properties.getMaxScriptTimeLimitMillis(), 180_000))),
                SandboxExecutionClient.kbToBytes(Math.min(safeMultiply(memoryLimit(memoryLimitKb), 2L),
                        positive(properties.getMaxMemoryLimitKb(), 1_048_576))));
    }

    private DraftTestcasePackageRunResponse failed(StoredPackage stored, int caseCount, String errorCode,
                                                   String message, Map<String, Integer> failures,
                                                   Long time, Long memory, Long runTime, Integer processes,
                                                   boolean referenceExecuted, int referencePassed) {
        return new DraftTestcasePackageRunResponse("FAILED", errorCode, message, stored.sha256(), stored.sizeBytes(),
                caseCount, 0, 0, caseCount, failures, time, memory, runTime, processes, referenceExecuted,
                referencePassed);
    }

    private long copyBounded(InputStream input, OutputStream output, long entryLimit, long remainingPackageBytes)
            throws IOException {
        if (remainingPackageBytes < 0) {
            throw badRequest("Draft testcase package is too large after decompression");
        }
        long size = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            size += read;
            if (size > entryLimit) {
                throw badRequest("Draft testcase zip entry exceeds the size limit");
            }
            if (size > remainingPackageBytes) {
                throw badRequest("Draft testcase package is too large after decompression");
            }
            output.write(buffer, 0, read);
        }
        return size;
    }

    private void rejectCompressionBomb(ZipArchiveEntry entry, long uncompressedBytes) {
        long compressed = entry.getCompressedSize();
        if (compressed == 0L && uncompressedBytes > 0L) {
            throw badRequest("Draft testcase package contains an unsafe compression ratio");
        }
        if (compressed > 0L && uncompressedBytes / compressed > MAX_COMPRESSION_RATIO) {
            throw badRequest("Draft testcase package contains an unsafe compression ratio");
        }
    }

    private ExtractedEntry requireFile(Map<String, ExtractedEntry> entries, String path, String role) {
        ExtractedEntry entry = entries.get(path);
        if (entry == null || entry.directory() || !Files.isRegularFile(entry.file())
                || Files.isSymbolicLink(entry.file())) {
            throw badRequest("Draft testcase " + role + " file is missing or unsafe");
        }
        return entry;
    }

    private String normalizeZipPath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            throw badRequest("Draft testcase zip path is required");
        }
        String path = rawPath.trim();
        if (path.contains("\\") || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw badRequest("Unsafe draft testcase zip path");
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (!StringUtils.hasText(segment) || ".".equals(segment) || "..".equals(segment)
                    || segment.contains(":")) {
                throw badRequest("Unsafe draft testcase zip path");
            }
        }
        return String.join("/", segments);
    }

    private String normalizeSha256(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw badRequest("Draft testcase package SHA-256 is invalid");
        }
        return normalized;
    }

    private void validateRequest(DraftTestcasePackageRunRequest request) {
        if (request == null) {
            throw badRequest("Draft testcase package run metadata is required");
        }
        if (request.packageSizeBytes() != null && request.packageSizeBytes() < 0) {
            throw badRequest("Draft testcase package size is invalid");
        }
        if (!List.of("cpp", "java", "python").contains(request.language())) {
            throw badRequest("Unsupported draft solution language");
        }
        requireSource(request.sourceCode(), "sourceCode");
        boolean hasReferenceSource = StringUtils.hasText(request.referenceSourceCode());
        boolean hasReferenceLanguage = StringUtils.hasText(request.referenceLanguage());
        if (hasReferenceSource != hasReferenceLanguage) {
            throw badRequest("Reference solution language and source must be provided together");
        }
        if (hasReferenceSource) {
            if (!List.of("cpp", "java", "python").contains(request.referenceLanguage())) {
                throw badRequest("Unsupported reference solution language");
            }
            requireSource(request.referenceSourceCode(), "referenceSourceCode");
        }
    }

    private void requireSource(String source, String field) {
        if (!StringUtils.hasText(source)) {
            throw badRequest(field + " is required");
        }
        if (source.getBytes(StandardCharsets.UTF_8).length
                > positive(properties.getMaxSourceBytes(), 256 * 1024)) {
            throw badRequest(field + " exceeds the configured limit");
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private long timeLimit(Integer value) {
        long requested = value == null || value <= 0 ? 1_000L : value.longValue();
        return Math.min(requested, positive(properties.getMaxRunTimeLimitMillis(), 30_000));
    }

    private long memoryLimit(Integer value) {
        long requested = value == null || value <= 0 ? 262_144L : value.longValue();
        return Math.min(requested, positive(properties.getMaxMemoryLimitKb(), 1_048_576));
    }

    private void cleanupSandboxFile(String fileId) {
        if (!StringUtils.hasText(fileId)) {
            return;
        }
        try {
            sandboxExecutionClient.deleteCachedFile(fileId);
        } catch (RuntimeException ex) {
            log.warn("Failed to cleanup a draft package sandbox executable: {}", ex.getClass().getSimpleName());
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        ensureUnder(runRoot, path.toAbsolutePath().normalize());
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (IOException ignored) {
                    // Best-effort cleanup; the directory is isolated and contains no reusable state.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    private void ensureUnder(Path root, Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw badRequest("Unsafe draft testcase package path");
        }
    }

    private DomainException badRequest(String message) {
        return new DomainException(ErrorCode.BAD_REQUEST, message);
    }

    private long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private long zero(Long value) {
        return value == null ? 0L : value;
    }

    private long safeAdd(long value, long addend) {
        return value > Long.MAX_VALUE - addend ? Long.MAX_VALUE : value + addend;
    }

    private long safeMultiply(long value, long factor) {
        return value > Long.MAX_VALUE / factor ? Long.MAX_VALUE : value * factor;
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

    private record StoredPackage(Path path, long sizeBytes, String sha256) {
    }

    private record ExtractedEntry(String path, Path file, boolean directory, long sizeBytes) {
    }

    private record PackageCase(Path input, Path expected, long expectedOutputBytes) {
    }

    private record ValidatedPackage(List<PackageCase> cases) {
    }

    private record RunAggregate(int executedCases, int passedCases, int failedCases,
                                Map<String, Integer> failureCounts, Long maxTimeMillis, Long maxMemoryKb,
                                Long maxRunTimeMillis, Integer maxProcPeak, int referencePassedCases,
                                boolean wallTimeBudgetExceeded) {
    }
}
