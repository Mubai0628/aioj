package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.config.InternalApiProperties;
import com.aioj.next.ai.domain.problem.OfficialTestcasePackageReport;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseArtifactMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ProblemDraftTestcaseArtifactService {
    public static final String STATUS_READY = "READY";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_WRITING = "WRITING";

    private final ProblemDraftTestcaseArtifactMapper mapper;
    private final ObjectMapper objectMapper;
    private final RestClient judgeWorkerClient;
    private final Path storageRoot;

    public ProblemDraftTestcaseArtifactService(ProblemDraftTestcaseArtifactMapper mapper,
                                               ObjectMapper objectMapper,
                                               AiProperties properties,
                                               InternalApiProperties internalApiProperties) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.storageRoot = Path.of(properties.getProblemDraft().getTestcaseArtifactStorageRoot()).toAbsolutePath().normalize();
        this.judgeWorkerClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getJudgeWorkerUri()))
                .defaultHeader("X-Internal-Token", internalApiProperties.getApiToken())
                .build();
    }

    public ProblemDraftTestcaseArtifactEntity storeFromOfficialPackage(Long draftId, Long creatorUserId,
                                                                       OfficialTestcasePackageReport report) {
        if (draftId == null || report == null || !report.passed()) {
            return null;
        }
        if (!StringUtils.hasText(report.packageFileId())) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Official testcase package file id is missing");
        }
        Path temp = null;
        ProblemDraftTestcaseArtifactEntity entity = null;
        try {
            Files.createDirectories(storageRoot);
            Path draftDir = storageRoot.resolve(String.valueOf(draftId)).normalize();
            ensureInsideStorage(draftDir);
            Files.createDirectories(draftDir);
            temp = Files.createTempFile(draftDir, "official-hidden-", ".zip.tmp");
            downloadPackage(report.packageFileId(), temp);
            long size = Files.size(temp);
            String sha256 = sha256(temp);
            if (report.packageFileSizeBytes() != null && report.packageFileSizeBytes() > 0
                    && report.packageFileSizeBytes() != size) {
                Files.deleteIfExists(temp);
                throw new DomainException(ErrorCode.INTERNAL_ERROR,
                        "Official testcase package size mismatch after download");
            }
            if (StringUtils.hasText(report.packageSha256())
                    && !report.packageSha256().equalsIgnoreCase(sha256)) {
                Files.deleteIfExists(temp);
                throw new DomainException(ErrorCode.INTERNAL_ERROR,
                        "Official testcase package SHA-256 mismatch after download");
            }
            LocalDateTime now = LocalDateTime.now();
            entity = new ProblemDraftTestcaseArtifactEntity();
            entity.setDraftId(draftId);
            entity.setCreatorUserId(creatorUserId);
            entity.setSource("AGENT_GENERATED");
            entity.setStatus(STATUS_WRITING);
            entity.setFormatStatus("PROCESSING");
            entity.setExecutionStatus("NOT_RUN");
            entity.setFileName(safeFileName(report.packageFileName()));
            entity.setStoragePath("");
            entity.setFileSizeBytes(size);
            entity.setSha256(sha256);
            entity.setCaseCount(report.caseCount() == null ? 0 : report.caseCount());
            entity.setTotalInputBytes(report.totalInputBytes() == null ? 0L : report.totalInputBytes());
            entity.setTotalOutputBytes(report.totalOutputBytes() == null ? 0L : report.totalOutputBytes());
            entity.setLargestCaseBytes(report.largestCaseBytes() == null ? 0L : report.largestCaseBytes());
            entity.setPackageSummaryJson(objectMapper.writeValueAsString(report));
            entity.setGeneratedAt(now);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            mapper.insert(entity);

            Path finalPath = draftDir.resolve(entity.getId() + ".zip").normalize();
            ensureInsideStorage(finalPath);
            moveAtomically(temp, finalPath);
            temp = null;
            entity.setStoragePath(storageRoot.relativize(finalPath).toString().replace('\\', '/'));
            entity.setStatus(STATUS_READY);
            entity.setFormatStatus("READY");
            mapper.updateById(entity);
            supersedeOlder(draftId, entity.getId());
            return entity;
        } catch (DomainException ex) {
            markFailed(entity, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            markFailed(entity, "Official testcase package could not be persisted");
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "Failed to persist official testcase package artifact");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best-effort local cleanup.
                }
            }
            deleteRemoteFile(report.packageFileId());
        }
    }

    public ProblemDraftTestcaseArtifactEntity bindAgentArtifact(Long artifactId, String workspaceId,
                                                                Long creatorUserId,
                                                                String verificationFingerprint,
                                                                Long agentRunId) {
        ProblemDraftTestcaseArtifactEntity entity = mapper.selectById(artifactId);
        if (entity == null || !STATUS_READY.equals(entity.getStatus())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase artifact not found");
        }
        entity.setWorkspaceId(workspaceId);
        entity.setCreatorUserId(creatorUserId);
        entity.setSource("AGENT_GENERATED");
        entity.setFormatStatus("READY");
        entity.setExecutionStatus("NOT_RUN");
        entity.setVerificationMode(null);
        entity.setVerificationFingerprint(verificationFingerprint);
        entity.setAgentRunId(agentRunId);
        entity.setGeneratedAt(entity.getGeneratedAt() == null ? LocalDateTime.now() : entity.getGeneratedAt());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return entity;
    }

    public ProblemDraftTestcaseArtifactEntity latestReadyForDraft(Long draftId) {
        return latestReady(draftId);
    }

    public ProblemDraftTestcaseArtifactEntity requireWorkspaceArtifact(Long artifactId, String workspaceId,
                                                                       Long creatorUserId) {
        ProblemDraftTestcaseArtifactEntity entity = mapper.selectOne(new QueryWrapper<ProblemDraftTestcaseArtifactEntity>()
                .eq("id", artifactId)
                .eq("workspace_id", workspaceId)
                .eq("creator_user_id", creatorUserId)
                .notIn("status", java.util.List.of(STATUS_SUPERSEDED, "FAILED"))
                .last("LIMIT 1"));
        if (entity == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase artifact not found");
        }
        return entity;
    }

    public ProblemDraftTestcaseArtifactEntity latestReady(Long draftId) {
        if (draftId == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<ProblemDraftTestcaseArtifactEntity>()
                .eq(ProblemDraftTestcaseArtifactEntity::getDraftId, draftId)
                .eq(ProblemDraftTestcaseArtifactEntity::getStatus, STATUS_READY)
                .orderByDesc(ProblemDraftTestcaseArtifactEntity::getCreatedAt)
                .last("LIMIT 1"));
    }

    public Path resolvePath(ProblemDraftTestcaseArtifactEntity entity) {
        if (entity == null || !StringUtils.hasText(entity.getStoragePath())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase artifact is missing");
        }
        Path path = storageRoot.resolve(entity.getStoragePath()).normalize();
        ensureInsideStorage(path);
        if (!Files.isRegularFile(path)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase artifact file is missing");
        }
        return path;
    }

    public List<Map<String, Object>> previewEntries(Long draftId) {
        ProblemDraftTestcaseArtifactEntity artifact = latestReady(draftId);
        if (artifact == null) {
            return List.of();
        }
        Path path = resolvePath(artifact);
        List<Map<String, Object>> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && entries.size() < 100) {
                if (entry.isDirectory()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getName());
                item.put("sizeBytes", entry.getSize());
                item.put("preview", readPreview(zip, 8192));
                item.put("truncated", entry.getSize() < 0 || entry.getSize() > 8192);
                entries.add(item);
            }
            return entries;
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to preview testcase package");
        }
    }

    private String readPreview(InputStream input, int maxBytes) throws IOException {
        byte[] buffer = input.readNBytes(maxBytes + 1);
        return new String(buffer, 0, Math.min(buffer.length, maxBytes), java.nio.charset.StandardCharsets.UTF_8);
    }

    public void markImported(Long artifactId, Long problemId, Long testcasePackageId) {
        if (artifactId == null) {
            return;
        }
        ProblemDraftTestcaseArtifactEntity entity = mapper.selectById(artifactId);
        if (entity == null) {
            return;
        }
        entity.setImportedProblemId(problemId);
        entity.setProblemTestcasePackageId(testcasePackageId);
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
    }

    private void downloadPackage(String fileId, Path temp) {
        judgeWorkerClient.get()
                .uri("/api/v1/internal/sandbox/files/{fileId}", fileId)
                .header(HttpHeaders.ACCEPT, "application/octet-stream")
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                                "Failed to download official testcase package from judge-worker");
                    }
                    try (InputStream input = response.getBody()) {
                        if (input == null) {
                            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                                    "Official testcase package download returned empty body");
                        }
                        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ex) {
                        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                                "Failed to store official testcase package download");
                    }
                    return null;
                });
    }

    private void deleteRemoteFile(String fileId) {
        if (!StringUtils.hasText(fileId)) {
            return;
        }
        try {
            judgeWorkerClient.post()
                    .uri("/api/v1/internal/sandbox/delete-file")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("fileId", fileId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ignored) {
            // Cache TTL remains the final cleanup backstop.
        }
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void markFailed(ProblemDraftTestcaseArtifactEntity entity, String message) {
        if (entity == null || entity.getId() == null) {
            return;
        }
        try {
            entity.setStatus("FAILED");
            entity.setFormatStatus("FAILED");
            entity.setFailureSummary(safeFailure(message));
            entity.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(entity);
        } catch (RuntimeException ignored) {
            // The original persistence failure remains authoritative.
        }
    }

    private String safeFailure(String message) {
        String value = StringUtils.hasText(message) ? message.trim() : "Testcase artifact persistence failed";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void supersedeOlder(Long draftId, Long keepId) {
        mapper.update(new ProblemDraftTestcaseArtifactEntity(),
                new LambdaUpdateWrapper<ProblemDraftTestcaseArtifactEntity>()
                        .eq(ProblemDraftTestcaseArtifactEntity::getDraftId, draftId)
                        .eq(ProblemDraftTestcaseArtifactEntity::getStatus, STATUS_READY)
                        .ne(ProblemDraftTestcaseArtifactEntity::getId, keepId)
                        .set(ProblemDraftTestcaseArtifactEntity::getStatus, STATUS_SUPERSEDED)
                        .set(ProblemDraftTestcaseArtifactEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void ensureInsideStorage(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(storageRoot)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invalid testcase artifact storage path");
        }
    }

    private String safeFileName(String value) {
        String name = StringUtils.hasText(value) ? value.trim() : "official-hidden-testcases.zip";
        name = name.replace('\r', '_').replace('\n', '_').replace('/', '_').replace('\\', '_');
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name += ".zip";
        }
        return name;
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8203";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
