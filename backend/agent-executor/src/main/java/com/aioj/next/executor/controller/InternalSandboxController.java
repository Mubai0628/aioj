package com.aioj.next.executor.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.executor.config.AgentExecutorProperties;
import com.aioj.next.executor.domain.CompileCache;
import com.aioj.next.executor.domain.SandboxExecutionClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/internal/sandbox")
public class InternalSandboxController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final int SCRIPT_STDOUT_LIMIT_BYTES = 1024 * 1024;
    private static final int DEFAULT_SCRIPT_TARGET_CASE_COUNT = 3;
    private static final int MAX_SCRIPT_TARGET_CASE_COUNT = 20;
    private static final String COLLECT_MODE_OFFICIAL_PACKAGE = "OFFICIAL_PACKAGE";
    private static final long DEFAULT_MAX_OFFICIAL_CASE_BYTES = 8L * 1024L * 1024L;
    private static final long DEFAULT_MAX_OFFICIAL_PACKAGE_BYTES = 50L * 1024L * 1024L;
    private static final long DEFAULT_RUN_MEMORY_LIMIT_KB = 262_144L;
    private static final long DEFAULT_COMPILE_MEMORY_LIMIT_KB = 393_216L;

    private final SandboxExecutionClient sandboxExecutionClient;
    private final CompileCache compileCache;
    private final AgentExecutorProperties properties;
    private final ObjectMapper objectMapper;

    public InternalSandboxController(SandboxExecutionClient sandboxExecutionClient,
                                     CompileCache compileCache,
                                     AgentExecutorProperties properties,
                                     ObjectMapper objectMapper) {
        this.sandboxExecutionClient = sandboxExecutionClient;
        this.compileCache = compileCache;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/compile")
    public ApiResponse<CompileResponse> compile(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody CompileRequest request
    ) {
        requireInternalToken(token);
        String language = normalizeLanguage(request.language());
        requireLanguageEnabled(language);
        requireBoundedText(request.sourceCode(), properties.getMaxSourceBytes(), "sourceCode");
        long runTimeLimitMillis = runTimeLimitMillis(request.timeLimitMillis(), 1000L);
        long compileTimeLimitMillis = Math.min(positive(properties.getMaxScriptTimeLimitMillis(), 180_000),
                safeMultiply(runTimeLimitMillis, 10L));
        // A5.3: with a cache scope the executable stays the cache's, so no fileId goes back.
        String scope = cacheScope(request.cacheScope());
        CompileCache.Lookup lookup = compileCache.compile(
                scope,
                language,
                request.sourceCode(),
                SandboxExecutionClient.millisToNanos(compileTimeLimitMillis),
                SandboxExecutionClient.kbToBytes(compileMemoryLimitKb())
        );
        SandboxExecutionClient.CompileOutcome outcome = lookup.outcome();
        return ApiResponse.ok(new CompileResponse(
                outcome.failed() ? "COMPILE_ERROR" : "ACCEPTED",
                outcome.message(),
                scope == null ? outcome.fileId() : null,
                outcome.stderr(),
                outcome.timeMillis(),
                outcome.memoryKb(),
                outcome.exitStatus(),
                outcome.runTimeMillis(),
                outcome.procPeak(),
                lookup.hit()
        ));
    }

    @PostMapping("/run-one")
    public ApiResponse<RunResponse> runOne(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody RunRequest request
    ) {
        requireInternalToken(token);
        return ApiResponse.ok(run(request));
    }

    @PostMapping("/run-batch")
    public ApiResponse<RunBatchResponse> runBatch(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody RunBatchRequest request
    ) {
        requireInternalToken(token);
        List<String> inputs = request.inputs() == null ? List.of() : request.inputs();
        if (inputs.size() > positive(properties.getMaxBatchSize(), 200)) {
            throw badRequest("Sandbox batch contains too many inputs");
        }
        long totalInputBytes = 0L;
        for (String stdin : inputs) {
            long bytes = utf8Length(stdin);
            if (bytes > positive(properties.getMaxInputBytes(), 8 * 1024 * 1024)) {
                throw badRequest("Sandbox stdin exceeds the configured limit");
            }
            totalInputBytes = safeAdd(totalInputBytes, bytes);
            if (totalInputBytes > positive(properties.getMaxBatchInputBytes(), 32L * 1024L * 1024L)) {
                throw badRequest("Sandbox batch input exceeds the configured limit");
            }
        }
        List<RunResponse> results = new ArrayList<>();
        for (String stdin : inputs) {
            results.add(run(new RunRequest(
                    normalizeLanguage(request.language()),
                    request.sourceCode(),
                    request.compiledFileId(),
                    stdin,
                    request.timeLimitMillis(),
                    request.memoryLimitKb(),
                    request.stdoutLimitBytes(),
                    request.cacheScope()
            )));
        }
        return ApiResponse.ok(new RunBatchResponse(results));
    }

    @PostMapping("/run-python-script")
    public ApiResponse<PythonScriptResponse> runPythonScript(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody PythonScriptRequest request
    ) {
        requireInternalToken(token);
        requireBoundedText(request.script(), properties.getMaxSourceBytes(), "script");
        int targetCaseCount = targetCaseCount(request.targetCaseCount());
        SandboxExecutionClient.RunOutcome outcome = COLLECT_MODE_OFFICIAL_PACKAGE.equals(request.collectMode())
                ? sandboxExecutionClient.runOfficialPackageScript(
                request.script(),
                enabledLanguage(request.standardSolutionLanguage()),
                boundedText(request.standardSolutionCode(), properties.getMaxSourceBytes(), "standardSolutionCode"),
                SandboxExecutionClient.millisToNanos(scriptTimeLimitMillis(request.timeLimitMillis(), 30_000L)),
                SandboxExecutionClient.kbToBytes(scriptMemoryLimitKb(request.memoryLimitKb())),
                Math.min(SCRIPT_STDOUT_LIMIT_BYTES,
                        positive(properties.getStdoutMaxCollectLimitBytes(), 4 * 1024 * 1024)),
                targetCaseCount,
                SandboxExecutionClient.millisToNanos(runTimeLimitMillis(request.standardTimeLimitMillis(), 1000L)),
                SandboxExecutionClient.kbToBytes(runMemoryLimitKb(request.standardMemoryLimitKb(),
                        "standardMemoryLimitKb")),
                Math.min(limitOrDefault(request.maxCaseBytes(), DEFAULT_MAX_OFFICIAL_CASE_BYTES),
                        positive(properties.getMaxDraftPackageEntryBytes(), DEFAULT_MAX_OFFICIAL_CASE_BYTES)),
                Math.min(limitOrDefault(request.maxPackageBytes(), DEFAULT_MAX_OFFICIAL_PACKAGE_BYTES),
                        positive(properties.getMaxDraftPackageBytes(), DEFAULT_MAX_OFFICIAL_PACKAGE_BYTES))
        )
                : sandboxExecutionClient.runPythonScript(
                request.script(),
                SandboxExecutionClient.millisToNanos(scriptTimeLimitMillis(request.timeLimitMillis(), 30_000L)),
                SandboxExecutionClient.kbToBytes(scriptMemoryLimitKb(request.memoryLimitKb())),
                Math.min(SCRIPT_STDOUT_LIMIT_BYTES,
                        positive(properties.getStdoutMaxCollectLimitBytes(), 4 * 1024 * 1024)),
                targetCaseCount,
                request.collectMode()
        );
        GeneratedFilesPayload generatedFiles = parseGeneratedFiles(outcome.generatedFilesJson(), outcome.stdout());
        OfficialPackagePayload officialPackage = parseOfficialPackage(outcome.officialPackageJson(), outcome.fileIds());
        return ApiResponse.ok(new PythonScriptResponse(
                outcome.resultCode(),
                outcome.message(),
                outcome.stdout(),
                outcome.stderr(),
                outcome.timeMillis(),
                outcome.memoryKb(),
                outcome.exitStatus(),
                outcome.runTimeMillis(),
                outcome.procPeak(),
                generatedFiles.files(),
                generatedFiles.generatedPairCount(),
                generatedFiles.generatedFileCount(),
                generatedFiles.generatedInputCount(),
                generatedFiles.generatedOutputCount(),
                generatedFiles.crossCheckInputs(),
                generatedFiles.manifestJson(),
                generatedFiles.scanRoot(),
                officialPackage
        ));
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable String fileId
    ) {
        requireInternalToken(token);
        requireSafeFileId(fileId);
        StreamingResponseBody body = outputStream -> sandboxExecutionClient.downloadCachedFile(fileId, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    @PostMapping("/delete-file")
    public ApiResponse<Boolean> deleteFile(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody DeleteFileRequest request
    ) {
        requireInternalToken(token);
        requireSafeFileId(request.fileId());
        sandboxExecutionClient.deleteCachedFile(request.fileId());
        return ApiResponse.ok(Boolean.TRUE);
    }

    private RunResponse run(RunRequest request) {
        String language = normalizeLanguage(request.language());
        requireLanguageEnabled(language);
        if (request.compiledFileId() == null || request.compiledFileId().isBlank() || "python".equals(language)) {
            requireBoundedText(request.sourceCode(), properties.getMaxSourceBytes(), "sourceCode");
        }
        requireSafeFileId(request.compiledFileId());
        requireBoundedText(request.stdin(), properties.getMaxInputBytes(), "stdin");
        long cpuLimitNs = SandboxExecutionClient.millisToNanos(runTimeLimitMillis(request.timeLimitMillis(), 1000L));
        long memoryLimitBytes = SandboxExecutionClient.kbToBytes(runMemoryLimitKb(request.memoryLimitKb(), "memoryLimitKb"));
        int stdoutLimitBytes = Math.min(limitOrDefault(request.stdoutLimitBytes(), 65_536L).intValue(),
                positive(properties.getStdoutMaxCollectLimitBytes(), 4 * 1024 * 1024));
        String scope = cacheScope(request.cacheScope());
        boolean fromSource = request.compiledFileId() == null || request.compiledFileId().isBlank();
        SandboxExecutionClient.RunOutcome outcome = scope != null && fromSource
                ? compileCache.run(scope, language, request.sourceCode(), request.stdin(), cpuLimitNs,
                        memoryLimitBytes, stdoutLimitBytes)
                : sandboxExecutionClient.runSource(language, request.sourceCode(), request.compiledFileId(),
                        request.stdin(), cpuLimitNs, memoryLimitBytes, stdoutLimitBytes);
        return new RunResponse(
                outcome.resultCode(),
                outcome.message(),
                outcome.stdout(),
                outcome.stderr(),
                outcome.timeMillis(),
                outcome.memoryKb(),
                outcome.exitStatus(),
                outcome.runTimeMillis(),
                outcome.procPeak()
        );
    }

    private String enabledLanguage(String language) {
        String normalized = normalizeLanguage(language);
        requireLanguageEnabled(normalized);
        return normalized;
    }

    private void requireLanguageEnabled(String language) {
        boolean enabled = properties.getLanguageWhitelist() != null && properties.getLanguageWhitelist().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(InternalSandboxController::normalizeLanguage)
                .anyMatch(language::equals);
        if (!enabled) {
            throw badRequest("Sandbox language is not enabled: " + language);
        }
    }

    private long runTimeLimitMillis(Integer value, long fallback) {
        return Math.min(limitOrDefault(value, fallback), positive(properties.getMaxRunTimeLimitMillis(), 30_000));
    }

    private long scriptTimeLimitMillis(Integer value, long fallback) {
        return Math.min(limitOrDefault(value, fallback), positive(properties.getMaxScriptTimeLimitMillis(), 180_000));
    }

    /** A program's memory: 256 MiB unless the caller asks for less; asking for more than the cap is rejected. */
    private long runMemoryLimitKb(Integer value, String field) {
        long cap = positive(properties.getMaxMemoryLimitKb(), DEFAULT_RUN_MEMORY_LIMIT_KB);
        return withinCap(value, Math.min(DEFAULT_RUN_MEMORY_LIMIT_KB, cap), cap, field);
    }

    /**
     * Compilers need about the same memory whatever the program's limit (g++ with bits/stdc++.h peaked at
     * 171-215 MiB in agent-sandbox, plan T1.10), so a compile always gets the compile cap.
     */
    private long compileMemoryLimitKb() {
        return positive(properties.getMaxCompileMemoryLimitKb(), DEFAULT_COMPILE_MEMORY_LIMIT_KB);
    }

    /** A generator script compiles and runs the standard solution in its own run, so it shares the compile cap. */
    private long scriptMemoryLimitKb(Integer value) {
        long cap = compileMemoryLimitKb();
        return withinCap(value, cap, cap, "memoryLimitKb");
    }

    private long withinCap(Integer value, long fallback, long cap, String field) {
        if (value == null || value <= 0) {
            return fallback;
        }
        if (value > cap) {
            throw badRequest(field + " " + value + " KB is above this executor's limit of " + cap + " KB");
        }
        return value;
    }

    private String boundedText(String value, long maxBytes, String field) {
        requireBoundedText(value, maxBytes, field);
        return value;
    }

    private void requireBoundedText(String value, long maxBytes, String field) {
        if (utf8Length(value) > positive(maxBytes, 1L)) {
            throw badRequest(field + " exceeds the configured limit");
        }
    }

    /** A5.3 cache scope: the caller's own key (for example {@code user:42}); blank means no caching. */
    private String cacheScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        String value = scope.strip();
        if (value.length() > 128 || !value.matches("[A-Za-z0-9:._-]+")) {
            throw badRequest("Invalid compile cache scope");
        }
        return value;
    }

    private void requireSafeFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return;
        }
        if (fileId.length() > 256 || fileId.indexOf('/') >= 0 || fileId.indexOf('\\') >= 0
                || fileId.chars().anyMatch(Character::isISOControl)) {
            throw badRequest("Invalid sandbox file id");
        }
    }

    private long utf8Length(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private DomainException badRequest(String message) {
        return new DomainException(ErrorCode.BAD_REQUEST, message);
    }

    private long safeAdd(long value, long addend) {
        return value > Long.MAX_VALUE - addend ? Long.MAX_VALUE : value + addend;
    }

    private long safeMultiply(long value, long factor) {
        return value > Long.MAX_VALUE / factor ? Long.MAX_VALUE : value * factor;
    }

    private int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private void requireInternalToken(String token) {
        String expected = properties.getInternalApiToken();
        if (expected == null || expected.isBlank() || token == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal token missing or invalid");
        }
    }

    static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Sandbox language is required");
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "cpp", "c++", "c++17", "cpp17" -> "cpp";
            case "java" -> "java";
            case "python", "py", "python3" -> "python";
            default -> throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported sandbox language: " + language);
        };
    }

    private GeneratedFilesPayload parseGeneratedFiles(String generatedFilesJson, String stdout) {
        if (generatedFilesJson != null && !generatedFilesJson.isBlank()) {
            Optional<GeneratedFilesPayload> parsed = parseGeneratedFilesJson(generatedFilesJson);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        if (stdout == null || stdout.isBlank()) {
            return GeneratedFilesPayload.empty();
        }
        Optional<GeneratedFilesPayload> direct = parseGeneratedFilesJson(stdout);
        if (direct.isPresent()) {
            return direct.get();
        }
        String[] lines = stdout.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index] == null ? "" : lines[index].trim();
            if (line.isEmpty()) {
                continue;
            }
            Optional<GeneratedFilesPayload> parsed = parseGeneratedFilesJson(line);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        return GeneratedFilesPayload.empty();
    }

    private Optional<GeneratedFilesPayload> parseGeneratedFilesJson(String value) {
        try {
            JsonNode rootNode = objectMapper.readTree(value);
            JsonNode filesNode = rootNode.path("files");
            if (!filesNode.isObject()) {
                return Optional.empty();
            }
            Map<String, String> files = new LinkedHashMap<>();
            filesNode.properties().forEach(entry -> {
                if (entry.getValue().isString()) {
                    files.put(entry.getKey(), entry.getValue().asString(""));
                }
            });
            JsonNode generatedPairCountNode = rootNode.path("generatedPairCount");
            Integer generatedPairCount = generatedPairCountNode.canConvertToInt()
                    ? generatedPairCountNode.asInt(0)
                    : null;
            return Optional.of(new GeneratedFilesPayload(
                    files,
                    textMap(rootNode.path("crossCheckInputs")),
                    generatedPairCount,
                    integerOrNull(rootNode.path("generatedFileCount")),
                    integerOrNull(rootNode.path("generatedInputCount")),
                    integerOrNull(rootNode.path("generatedOutputCount")),
                    textOrNull(rootNode.path("manifestJson")),
                    textOrNull(rootNode.path("scanRoot"))
            ));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private OfficialPackagePayload parseOfficialPackage(String value, Map<String, String> fileIds) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(value);
            String packageFileId = fileIds == null ? null : fileIds.get(".aioj_official_hidden.zip");
            String status = textOrNull(rootNode.path("status"));
            String errorCode = textOrNull(rootNode.path("errorCode"));
            String errorMessage = textOrNull(rootNode.path("errorMessage"));
            if ("PASSED".equals(status) && (packageFileId == null || packageFileId.isBlank())) {
                status = "FAILED";
                errorCode = "PACKAGE_CACHE_FILE_MISSING";
                errorMessage = "Generated testcase package could not be retained by the Sandbox cached-file channel; "
                        + "verify the configured copy-out capacity and package size";
            }
            List<OfficialCasePayload> cases = new ArrayList<>();
            JsonNode casesNode = rootNode.path("cases");
            if (casesNode.isArray()) {
                for (JsonNode node : casesNode) {
                    cases.add(new OfficialCasePayload(
                            textOrNull(node.path("name")),
                            textOrNull(node.path("sourceInputPath")),
                            textOrNull(node.path("inputPath")),
                            textOrNull(node.path("outputPath")),
                            longOrNull(node.path("inputBytes")),
                            longOrNull(node.path("outputBytes")),
                            textOrNull(node.path("inputSha256")),
                            textOrNull(node.path("outputSha256")),
                            textOrNull(node.path("status")),
                            longOrNull(node.path("timeMillis")),
                            longOrNull(node.path("memoryKb")),
                            textOrNull(node.path("message"))
                    ));
                }
            }
            return new OfficialPackagePayload(
                    status,
                    errorCode,
                    errorMessage,
                    packageFileId,
                    textOrNull(rootNode.path("packageFileName")),
                    longOrNull(rootNode.path("packageFileSizeBytes")),
                    textOrNull(rootNode.path("packageSha256")),
                    integerOrNull(rootNode.path("caseCount")),
                    integerOrNull(rootNode.path("generatedInputCount")),
                    integerOrNull(rootNode.path("generatedOutputCount")),
                    longOrNull(rootNode.path("totalInputBytes")),
                    longOrNull(rootNode.path("totalOutputBytes")),
                    longOrNull(rootNode.path("totalBytes")),
                    longOrNull(rootNode.path("largestCaseBytes")),
                    textOrNull(rootNode.path("manifestJson")),
                    textOrNull(rootNode.path("scanRoot")),
                    cases
            );
        } catch (Exception ex) {
            return new OfficialPackagePayload("FAILED", "GENERATOR_PACKAGE_METADATA_INVALID",
                    "official testcase package metadata could not be parsed", null, null, null,
                    null, 0, 0, 0, 0L, 0L, 0L, 0L, null, null, List.of());
        }
    }

    private Integer integerOrNull(JsonNode node) {
        return node != null && node.canConvertToInt() ? node.asInt(0) : null;
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isString() ? node.asString("") : null;
    }

    private Long longOrNull(JsonNode node) {
        return node != null && node.canConvertToLong() ? node.asLong(0L) : null;
    }

    private Map<String, String> textMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            if (entry.getValue().isString()) {
                values.put(entry.getKey(), entry.getValue().asString(""));
            }
        });
        return values;
    }

    private int targetCaseCount(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_SCRIPT_TARGET_CASE_COUNT;
        }
        return Math.min(MAX_SCRIPT_TARGET_CASE_COUNT, value);
    }

    private Long limitOrDefault(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private Long limitOrDefault(Integer value, long fallback) {
        return value == null || value <= 0 ? fallback : value.longValue();
    }

    public record CompileRequest(String language, String sourceCode, Integer timeLimitMillis, Integer memoryLimitKb,
                                 String cacheScope) {
        public CompileRequest(String language, String sourceCode, Integer timeLimitMillis, Integer memoryLimitKb) {
            this(language, sourceCode, timeLimitMillis, memoryLimitKb, null);
        }
    }

    public record CompileResponse(String status, String message, String fileId, String stderr, Long timeMillis,
                                  Long memoryKb, Integer exitStatus, Long runTimeMillis, Integer procPeak,
                                  Boolean cacheHit) {
    }

    public record DeleteFileRequest(String fileId) {
    }

    public record RunRequest(String language, String sourceCode, String compiledFileId, String stdin,
                             Integer timeLimitMillis, Integer memoryLimitKb, Integer stdoutLimitBytes,
                             String cacheScope) {
        public RunRequest(String language, String sourceCode, String compiledFileId, String stdin,
                          Integer timeLimitMillis, Integer memoryLimitKb, Integer stdoutLimitBytes) {
            this(language, sourceCode, compiledFileId, stdin, timeLimitMillis, memoryLimitKb, stdoutLimitBytes, null);
        }
    }

    public record RunBatchRequest(String language, String sourceCode, String compiledFileId, List<String> inputs,
                                  Integer timeLimitMillis, Integer memoryLimitKb, Integer stdoutLimitBytes,
                                  String cacheScope) {
        public RunBatchRequest(String language, String sourceCode, String compiledFileId, List<String> inputs,
                               Integer timeLimitMillis, Integer memoryLimitKb, Integer stdoutLimitBytes) {
            this(language, sourceCode, compiledFileId, inputs, timeLimitMillis, memoryLimitKb, stdoutLimitBytes, null);
        }
    }

    public record RunResponse(String status, String message, String stdout, String stderr, Long timeMillis,
                              Long memoryKb, Integer exitStatus, Long runTimeMillis, Integer procPeak) {
    }

    public record RunBatchResponse(List<RunResponse> results) {
    }

    public record PythonScriptRequest(String script, Integer timeLimitMillis, Integer memoryLimitKb,
                                      Integer targetCaseCount, String collectMode,
                                      String standardSolutionLanguage, String standardSolutionCode,
                                      Integer standardTimeLimitMillis, Integer standardMemoryLimitKb,
                                      Long maxCaseBytes, Long maxPackageBytes) {
        public PythonScriptRequest(String script, Integer timeLimitMillis, Integer memoryLimitKb,
                                   Integer targetCaseCount) {
            this(script, timeLimitMillis, memoryLimitKb, targetCaseCount, null,
                    null, null, null, null, null, null);
        }

        public PythonScriptRequest(String script, Integer timeLimitMillis, Integer memoryLimitKb,
                                   Integer targetCaseCount, String collectMode) {
            this(script, timeLimitMillis, memoryLimitKb, targetCaseCount, collectMode,
                    null, null, null, null, null, null);
        }
    }

    public record PythonScriptResponse(String status, String message, String stdout, String stderr, Long timeMillis,
                                       Long memoryKb, Integer exitStatus, Long runTimeMillis, Integer procPeak,
                                       Map<String, String> generatedFiles, Integer generatedPairCount,
                                       Integer generatedFileCount, Integer generatedInputCount,
                                       Integer generatedOutputCount,
                                       Map<String, String> crossCheckInputs, String manifestJson, String scanRoot,
                                       OfficialPackagePayload officialPackage) {
    }

    public record OfficialPackagePayload(String status, String errorCode, String errorMessage,
                                         String packageFileId, String packageFileName,
                                         Long packageFileSizeBytes, String packageSha256,
                                         Integer caseCount, Integer generatedInputCount,
                                         Integer generatedOutputCount, Long totalInputBytes,
                                         Long totalOutputBytes, Long totalBytes, Long largestCaseBytes,
                                         String manifestJson, String scanRoot,
                                         List<OfficialCasePayload> cases) {
    }

    public record OfficialCasePayload(String name, String sourceInputPath, String inputPath, String outputPath,
                                      Long inputBytes, Long outputBytes, String inputSha256, String outputSha256,
                                      String status, Long timeMillis, Long memoryKb, String message) {
    }

    private record GeneratedFilesPayload(Map<String, String> files, Map<String, String> crossCheckInputs,
                                         Integer generatedPairCount,
                                         Integer generatedFileCount, Integer generatedInputCount,
                                         Integer generatedOutputCount, String manifestJson, String scanRoot) {
        private static GeneratedFilesPayload empty() {
            return new GeneratedFilesPayload(Map.of(), Map.of(), null, 0, 0, 0, null, null);
        }
    }
}
