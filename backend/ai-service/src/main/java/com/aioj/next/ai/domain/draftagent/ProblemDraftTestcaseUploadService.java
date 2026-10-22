package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseUploadChunkEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseUploadSessionEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseArtifactMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseUploadChunkMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseUploadSessionMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.ProblemDraftTestcaseUploadCompleteRequest;
import com.aioj.next.contract.ai.ProblemDraftTestcaseUploadInitRequest;
import com.aioj.next.contract.ai.ProblemDraftTestcaseUploadInitResponse;
import com.aioj.next.contract.ai.ProblemDraftTestcaseUploadStatusResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Owns the resumable, workspace-bound upload lifecycle for manually supplied
 * problem-draft testcase packages. Files are never addressed by client paths;
 * all filesystem locations are derived from server-owned workspace state.
 */
@Service
public class ProblemDraftTestcaseUploadService {
    public static final int CHUNK_SIZE_BYTES = 1024 * 1024;
    public static final long MAX_PACKAGE_BYTES = 50L * 1024L * 1024L;
    public static final int UPLOAD_EXPIRY_HOURS = 24;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String STATUS_UPLOADING = "UPLOADING";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final ProblemDraftWorkspaceService workspaceService;
    private final ProblemDraftTestcaseUploadSessionMapper sessionMapper;
    private final ProblemDraftTestcaseUploadChunkMapper chunkMapper;
    private final ProblemDraftTestcaseArtifactMapper artifactMapper;
    private final DraftTestcasePackageFormatValidator packageValidator;
    private final ObjectMapper objectMapper;
    private final Path storageRoot;
    private final Path uploadRoot;
    private final ConcurrentHashMap<String, Object> uploadLocks = new ConcurrentHashMap<>();

    public ProblemDraftTestcaseUploadService(
            ProblemDraftWorkspaceService workspaceService,
            ProblemDraftTestcaseUploadSessionMapper sessionMapper,
            ProblemDraftTestcaseUploadChunkMapper chunkMapper,
            ProblemDraftTestcaseArtifactMapper artifactMapper,
            DraftTestcasePackageFormatValidator packageValidator,
            ObjectMapper objectMapper,
            AiProperties properties
    ) {
        this.workspaceService = workspaceService;
        this.sessionMapper = sessionMapper;
        this.chunkMapper = chunkMapper;
        this.artifactMapper = artifactMapper;
        this.packageValidator = packageValidator;
        this.objectMapper = objectMapper;
        this.storageRoot = Path.of(properties.getProblemDraft().getTestcaseArtifactStorageRoot())
                .toAbsolutePath().normalize();
        this.uploadRoot = storageRoot.resolve("_manual-uploads").normalize();
        ensureInside(storageRoot, uploadRoot);
    }

    @Transactional
    public ProblemDraftTestcaseUploadInitResponse init(String workspaceId, long userId,
                                                       ProblemDraftTestcaseUploadInitRequest request) {
        ProblemDraftWorkspaceEntity workspace = requireUploadableWorkspace(workspaceId, userId);
        if (request == null) {
            throw badRequest("Testcase package upload request is required");
        }
        String fileName = normalizeFileName(request.fileName());
        long fileSize = request.fileSizeBytes() == null ? 0L : request.fileSizeBytes();
        if (fileSize <= 0L || fileSize > MAX_PACKAGE_BYTES) {
            throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Testcase package must be between 1 byte and 50 MiB");
        }
        String expectedSha = normalizeSha256(request.sha256(), "sha256");
        String workspaceSegment = safeSegment(workspace.getId(), "workspace");
        String fingerprint = normalizeSha256(workspace.getVerificationFingerprint(), "workspace fingerprint");
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        int chunkCount = Math.toIntExact((fileSize + CHUNK_SIZE_BYTES - 1L) / CHUNK_SIZE_BYTES);
        Path directory = uploadRoot.resolve(workspaceSegment)
                .resolve(String.valueOf(workspace.getCurrentDraftId()))
                .resolve(fingerprint)
                .resolve(uploadId)
                .normalize();
        ensureInside(uploadRoot, directory);
        try {
            Files.createDirectories(directory.resolve("chunks"));
        } catch (IOException ex) {
            throw internal("Unable to initialize testcase package upload storage");
        }

        LocalDateTime now = LocalDateTime.now();
        ProblemDraftTestcaseUploadSessionEntity session = new ProblemDraftTestcaseUploadSessionEntity();
        session.setId(uploadId);
        session.setWorkspaceId(workspace.getId());
        session.setDraftId(workspace.getCurrentDraftId());
        session.setCreatorUserId(userId);
        session.setStatus(STATUS_UPLOADING);
        session.setFileName(fileName);
        session.setTempStoragePath(relativeStoragePath(directory));
        session.setExpectedSizeBytes(fileSize);
        session.setExpectedSha256(expectedSha);
        session.setUploadedSizeBytes(0L);
        session.setChunkSizeBytes(CHUNK_SIZE_BYTES);
        session.setChunkCount(chunkCount);
        session.setReceivedChunkCount(0);
        session.setExpiresAt(now.plusHours(UPLOAD_EXPIRY_HOURS));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        try {
            sessionMapper.insert(session);
        } catch (RuntimeException ex) {
            deleteRecursively(directory);
            throw ex;
        }
        return new ProblemDraftTestcaseUploadInitResponse(uploadId, workspace.getId(), STATUS_UPLOADING,
                CHUNK_SIZE_BYTES, chunkCount, toInstant(session.getExpiresAt()));
    }

    @Transactional
    public ProblemDraftTestcaseUploadStatusResponse uploadChunk(String workspaceId, String uploadId, int chunkIndex,
                                                                 long userId, String chunkSha256, InputStream content) {
        Object lock = uploadLocks.computeIfAbsent(normalizeUploadId(uploadId), ignored -> new Object());
        synchronized (lock) {
            ProblemDraftTestcaseUploadSessionEntity session = requireActiveSession(workspaceId, uploadId, userId);
            if (STATUS_READY.equals(session.getStatus())) {
                return statusResponse(session);
            }
            assertWorkspaceUnchanged(session, userId);
            if (chunkIndex < 0 || chunkIndex >= session.getChunkCount()) {
                throw badRequest("Testcase upload chunk index is outside the expected range");
            }
            if (content == null) {
                throw badRequest("Testcase upload chunk body is required");
            }
            String expectedChunkSha = normalizeSha256(chunkSha256, "X-Chunk-Sha256");
            long expectedChunkSize = expectedChunkSize(session, chunkIndex);
            ProblemDraftTestcaseUploadChunkEntity existing = findChunk(session.getId(), chunkIndex);
            Path destination = chunkPath(session, chunkIndex);
            if (existing != null) {
                assertIdempotentChunk(existing, expectedChunkSha, expectedChunkSize, destination);
                return statusResponse(refreshSession(session.getId()));
            }

            Path temporary = null;
            try {
                Files.createDirectories(destination.getParent());
                temporary = Files.createTempFile(destination.getParent(), "incoming-", ".tmp");
                DigestResult received = storeBounded(content, temporary, expectedChunkSize);
                if (received.sizeBytes() != expectedChunkSize) {
                    throw badRequest("Testcase upload chunk size does not match its expected byte range");
                }
                if (!expectedChunkSha.equals(received.sha256())) {
                    throw badRequest("X-Chunk-Sha256 does not match the uploaded chunk");
                }
                moveAtomically(temporary, destination, false);
                temporary = null;

                ProblemDraftTestcaseUploadChunkEntity chunk = new ProblemDraftTestcaseUploadChunkEntity();
                chunk.setUploadId(session.getId());
                chunk.setChunkIndex(chunkIndex);
                chunk.setSizeBytes(Math.toIntExact(received.sizeBytes()));
                chunk.setSha256(received.sha256());
                chunk.setCreatedAt(LocalDateTime.now());
                chunkMapper.insert(chunk);
                updateProgress(session);
                return statusResponse(refreshSession(session.getId()));
            } catch (DomainException ex) {
                deleteIfExists(temporary);
                throw ex;
            } catch (IOException ex) {
                deleteIfExists(temporary);
                throw internal("Unable to store testcase package upload chunk");
            } catch (RuntimeException ex) {
                deleteIfExists(temporary);
                ProblemDraftTestcaseUploadChunkEntity raced = findChunk(session.getId(), chunkIndex);
                if (raced != null) {
                    assertIdempotentChunk(raced, expectedChunkSha, expectedChunkSize, destination);
                    updateProgress(session);
                    return statusResponse(refreshSession(session.getId()));
                }
                deleteIfExists(destination);
                throw ex;
            }
        }
    }

    @Transactional
    public ProblemDraftTestcaseUploadStatusResponse complete(String workspaceId, String uploadId, long userId,
                                                              ProblemDraftTestcaseUploadCompleteRequest request) {
        Object lock = uploadLocks.computeIfAbsent(normalizeUploadId(uploadId), ignored -> new Object());
        synchronized (lock) {
            ProblemDraftTestcaseUploadSessionEntity session = requireSession(workspaceId, uploadId, userId);
            if (STATUS_READY.equals(session.getStatus())) {
                return statusResponse(session);
            }
            requireUnexpired(session);
            if (!STATUS_UPLOADING.equals(session.getStatus())) {
                throw new DomainException(ErrorCode.CONFLICT,
                        "Testcase package upload is not ready to be completed");
            }
            if (request == null) {
                throw badRequest("Testcase package completion request is required");
            }
            String completionSha = normalizeSha256(request.sha256(), "sha256");
            if (!session.getExpectedSha256().equals(completionSha)) {
                throw badRequest("Completion SHA-256 does not match the initialized upload");
            }
            ProblemDraftWorkspaceEntity workspace = assertWorkspaceUnchanged(session, userId);
            List<ProblemDraftTestcaseUploadChunkEntity> chunks = allChunks(session.getId());
            verifyAllChunks(session, chunks);
            if (!markProcessing(session)) {
                ProblemDraftTestcaseUploadSessionEntity current = refreshSession(session.getId());
                if (STATUS_READY.equals(current.getStatus())) {
                    return statusResponse(current);
                }
                throw new DomainException(ErrorCode.CONFLICT,
                        "Testcase package upload is already being completed");
            }

            Path directory = resolveSessionDirectory(session);
            Path assembled = directory.resolve("assembled.zip.tmp").normalize();
            ensureInside(directory, assembled);
            deleteIfExists(assembled);
            try {
                DigestResult merged = mergeChunks(session, chunks, assembled);
                if (merged.sizeBytes() != session.getExpectedSizeBytes()) {
                    throw badRequest("Uploaded testcase chunks do not match the initialized file size");
                }
                if (!session.getExpectedSha256().equals(merged.sha256())) {
                    throw badRequest("Uploaded testcase chunks do not match the initialized SHA-256");
                }
                DraftTestcasePackageFormatValidator.ValidationResult validated = packageValidator.validate(assembled);
                long artifactId = IdWorker.getId();
                Path finalPath = storageRoot.resolve(String.valueOf(session.getDraftId()))
                        .resolve("manual-" + session.getId() + ".zip").normalize();
                ensureInside(storageRoot, finalPath);
                Files.createDirectories(finalPath.getParent());
                moveAtomically(assembled, finalPath, true);
                ProblemDraftTestcaseArtifactEntity artifact = manualArtifact(
                        artifactId, session, workspace, finalPath, validated);
                try {
                    artifactMapper.insert(artifact);
                    workspaceService.attachArtifact(workspace.getId(), userId, artifact);
                    supersedeOtherArtifacts(workspace.getId(), artifactId);
                    markReady(session, artifactId);
                } catch (RuntimeException ex) {
                    deleteIfExists(finalPath);
                    throw ex;
                }
                deleteRecursively(directory);
                return statusResponse(refreshSession(session.getId()));
            } catch (DraftTestcasePackageFormatValidator.ValidationException ex) {
                deleteIfExists(assembled);
                markFailed(session, "TESTCASE_PACKAGE_FORMAT_INVALID", ex.getMessage());
                return statusResponse(refreshSession(session.getId()));
            } catch (DomainException ex) {
                deleteIfExists(assembled);
                throw ex;
            } catch (IOException ex) {
                deleteIfExists(assembled);
                markFailed(session, "TESTCASE_PACKAGE_IO_FAILED",
                        "Testcase package could not be assembled or stored");
                return statusResponse(refreshSession(session.getId()));
            }
        }
    }

    public ProblemDraftTestcaseUploadStatusResponse status(String workspaceId, String uploadId, long userId) {
        ProblemDraftTestcaseUploadSessionEntity session = requireSession(workspaceId, uploadId, userId);
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())
                && STATUS_UPLOADING.equals(session.getStatus())) {
            markExpired(session);
            session = refreshSession(session.getId());
        }
        return statusResponse(session);
    }

    @Scheduled(fixedDelayString = "${aioj.ai.problem-draft.testcase-upload-cleanup-ms:3600000}")
    public void cleanupExpiredUploads() {
        List<ProblemDraftTestcaseUploadSessionEntity> expired = sessionMapper.selectList(
                new QueryWrapper<ProblemDraftTestcaseUploadSessionEntity>()
                        .le("expires_at", LocalDateTime.now()));
        for (ProblemDraftTestcaseUploadSessionEntity session : expired) {
            Object lock = uploadLocks.computeIfAbsent(session.getId(), ignored -> new Object());
            synchronized (lock) {
                deleteRecursivelyQuietly(session);
                chunkMapper.delete(new QueryWrapper<ProblemDraftTestcaseUploadChunkEntity>()
                        .eq("upload_id", session.getId()));
                sessionMapper.deleteById(session.getId());
                uploadLocks.remove(session.getId(), lock);
            }
        }
    }

    private ProblemDraftWorkspaceEntity requireUploadableWorkspace(String workspaceId, long userId) {
        ProblemDraftWorkspaceEntity workspace = workspaceService.requireOwned(workspaceId, userId);
        if (workspace.getCurrentDraftId() == null) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Finalize the current problem draft before uploading a testcase package");
        }
        normalizeSha256(workspace.getVerificationFingerprint(), "workspace fingerprint");
        return workspace;
    }

    private ProblemDraftWorkspaceEntity assertWorkspaceUnchanged(ProblemDraftTestcaseUploadSessionEntity session,
                                                                  long userId) {
        ProblemDraftWorkspaceEntity workspace = requireUploadableWorkspace(session.getWorkspaceId(), userId);
        String capturedFingerprint = capturedFingerprint(session);
        if (!session.getDraftId().equals(workspace.getCurrentDraftId())
                || !capturedFingerprint.equals(workspace.getVerificationFingerprint())) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Problem draft content changed after this testcase upload was initialized; start a new upload");
        }
        return workspace;
    }

    private ProblemDraftTestcaseUploadSessionEntity requireActiveSession(String workspaceId, String uploadId,
                                                                          long userId) {
        ProblemDraftTestcaseUploadSessionEntity session = requireSession(workspaceId, uploadId, userId);
        requireUnexpired(session);
        if (STATUS_READY.equals(session.getStatus())) {
            return session;
        }
        if (!STATUS_UPLOADING.equals(session.getStatus())) {
            throw new DomainException(ErrorCode.CONFLICT, "Testcase package upload is not active");
        }
        return session;
    }

    private ProblemDraftTestcaseUploadSessionEntity requireSession(String workspaceId, String uploadId,
                                                                    long userId) {
        workspaceService.requireOwned(workspaceId, userId);
        String normalizedId = normalizeUploadId(uploadId);
        ProblemDraftTestcaseUploadSessionEntity session = sessionMapper.selectById(normalizedId);
        if (session == null || !workspaceId.equals(session.getWorkspaceId())
                || !Long.valueOf(userId).equals(session.getCreatorUserId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase upload not found");
        }
        resolveSessionDirectory(session);
        return session;
    }

    private void requireUnexpired(ProblemDraftTestcaseUploadSessionEntity session) {
        if (session.getExpiresAt() == null || !session.getExpiresAt().isAfter(LocalDateTime.now())) {
            markExpired(session);
            throw new DomainException(ErrorCode.CONFLICT, "Problem draft testcase upload has expired");
        }
    }

    private ProblemDraftTestcaseUploadChunkEntity findChunk(String uploadId, int chunkIndex) {
        return chunkMapper.selectOne(new QueryWrapper<ProblemDraftTestcaseUploadChunkEntity>()
                .eq("upload_id", uploadId)
                .eq("chunk_index", chunkIndex)
                .last("LIMIT 1"));
    }

    private List<ProblemDraftTestcaseUploadChunkEntity> allChunks(String uploadId) {
        return chunkMapper.selectList(new QueryWrapper<ProblemDraftTestcaseUploadChunkEntity>()
                .eq("upload_id", uploadId)
                .orderByAsc("chunk_index"));
    }

    private void assertIdempotentChunk(ProblemDraftTestcaseUploadChunkEntity existing, String sha,
                                       long expectedSize, Path path) {
        if (!sha.equals(existing.getSha256()) || existing.getSizeBytes() == null
                || existing.getSizeBytes() != expectedSize || !Files.isRegularFile(path)) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "A different testcase upload chunk already exists at this index");
        }
        try {
            DigestResult persisted = digest(path, expectedSize);
            if (persisted.sizeBytes() != expectedSize || !sha.equals(persisted.sha256())) {
                throw new DomainException(ErrorCode.CONFLICT,
                        "The persisted testcase upload chunk failed its integrity check");
            }
        } catch (IOException ex) {
            throw internal("Unable to verify persisted testcase upload chunk");
        }
    }

    private void verifyAllChunks(ProblemDraftTestcaseUploadSessionEntity session,
                                 List<ProblemDraftTestcaseUploadChunkEntity> chunks) {
        if (chunks.size() != session.getChunkCount()) {
            throw badRequest("Not all testcase chunks have been uploaded");
        }
        long total = 0L;
        for (int index = 0; index < session.getChunkCount(); index++) {
            ProblemDraftTestcaseUploadChunkEntity chunk = chunks.get(index);
            long expectedSize = expectedChunkSize(session, index);
            if (chunk.getChunkIndex() == null || chunk.getChunkIndex() != index
                    || chunk.getSizeBytes() == null || chunk.getSizeBytes() != expectedSize) {
                throw badRequest("Testcase upload chunk metadata is incomplete");
            }
            Path path = chunkPath(session, index);
            assertIdempotentChunk(chunk, normalizeSha256(chunk.getSha256(), "chunk sha256"), expectedSize, path);
            total = safeAdd(total, expectedSize);
            if (total > MAX_PACKAGE_BYTES || total > session.getExpectedSizeBytes()) {
                throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,
                        "Testcase upload chunks exceed the initialized package size");
            }
        }
        if (total != session.getExpectedSizeBytes()) {
            throw badRequest("Testcase upload chunk sizes do not match the initialized package size");
        }
    }

    private DigestResult mergeChunks(ProblemDraftTestcaseUploadSessionEntity session,
                                     List<ProblemDraftTestcaseUploadChunkEntity> chunks, Path target)
            throws IOException {
        MessageDigest digest = sha256Digest();
        long total = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            for (ProblemDraftTestcaseUploadChunkEntity chunk : chunks) {
                try (InputStream input = Files.newInputStream(chunkPath(session, chunk.getChunkIndex()))) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total = safeAdd(total, read);
                        if (total > MAX_PACKAGE_BYTES || total > session.getExpectedSizeBytes()) {
                            throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,
                                    "Testcase package exceeds its initialized size");
                        }
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
        return new DigestResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private ProblemDraftTestcaseArtifactEntity manualArtifact(
            long artifactId,
            ProblemDraftTestcaseUploadSessionEntity session,
            ProblemDraftWorkspaceEntity workspace,
            Path finalPath,
            DraftTestcasePackageFormatValidator.ValidationResult validated
    ) {
        LocalDateTime now = LocalDateTime.now();
        ProblemDraftTestcaseArtifactEntity artifact = new ProblemDraftTestcaseArtifactEntity();
        artifact.setId(artifactId);
        artifact.setDraftId(session.getDraftId());
        artifact.setWorkspaceId(workspace.getId());
        artifact.setCreatorUserId(session.getCreatorUserId());
        artifact.setSource("MANUAL_UPLOAD");
        artifact.setStatus(STATUS_READY);
        artifact.setFormatStatus(STATUS_READY);
        artifact.setExecutionStatus("NOT_RUN");
        artifact.setVerificationMode(null);
        artifact.setVerificationFingerprint(workspace.getVerificationFingerprint());
        artifact.setFileName(session.getFileName());
        artifact.setStoragePath(relativeStoragePath(finalPath));
        artifact.setFileSizeBytes(session.getExpectedSizeBytes());
        artifact.setSha256(session.getExpectedSha256());
        artifact.setCaseCount(validated.caseCount());
        artifact.setTotalInputBytes(validated.totalInputBytes());
        artifact.setTotalOutputBytes(validated.totalOutputBytes());
        artifact.setLargestCaseBytes(validated.largestCaseBytes());
        artifact.setPackageSummaryJson(safeJson(validated.safeSummary()));
        artifact.setGeneratedAt(now);
        artifact.setValidatedAt(now);
        artifact.setCreatedAt(now);
        artifact.setUpdatedAt(now);
        return artifact;
    }

    private void supersedeOtherArtifacts(String workspaceId, long keepId) {
        artifactMapper.update(null, new UpdateWrapper<ProblemDraftTestcaseArtifactEntity>()
                .eq("workspace_id", workspaceId)
                .eq("status", STATUS_READY)
                .ne("id", keepId)
                .set("status", "SUPERSEDED")
                .set("updated_at", LocalDateTime.now()));
    }

    private void updateProgress(ProblemDraftTestcaseUploadSessionEntity session) {
        List<ProblemDraftTestcaseUploadChunkEntity> chunks = allChunks(session.getId());
        long uploaded = 0L;
        for (ProblemDraftTestcaseUploadChunkEntity chunk : chunks) {
            uploaded = safeAdd(uploaded, chunk.getSizeBytes() == null ? 0L : chunk.getSizeBytes());
        }
        if (uploaded > session.getExpectedSizeBytes() || uploaded > MAX_PACKAGE_BYTES) {
            throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Testcase upload chunks exceed the initialized package size");
        }
        sessionMapper.update(null, new UpdateWrapper<ProblemDraftTestcaseUploadSessionEntity>()
                .eq("id", session.getId())
                .eq("status", STATUS_UPLOADING)
                .set("uploaded_size_bytes", uploaded)
                .set("received_chunk_count", chunks.size())
                .set("updated_at", LocalDateTime.now()));
    }

    private boolean markProcessing(ProblemDraftTestcaseUploadSessionEntity session) {
        int updated = sessionMapper.update(null, new UpdateWrapper<ProblemDraftTestcaseUploadSessionEntity>()
                .eq("id", session.getId())
                .eq("status", STATUS_UPLOADING)
                .set("status", STATUS_PROCESSING)
                .set("error_code", null)
                .set("error_message", null)
                .set("updated_at", LocalDateTime.now()));
        if (updated == 1) {
            session.setStatus(STATUS_PROCESSING);
            return true;
        }
        return false;
    }

    private void markReady(ProblemDraftTestcaseUploadSessionEntity session, long artifactId) {
        LocalDateTime now = LocalDateTime.now();
        sessionMapper.update(null, new UpdateWrapper<ProblemDraftTestcaseUploadSessionEntity>()
                .eq("id", session.getId())
                .eq("status", STATUS_PROCESSING)
                .set("status", STATUS_READY)
                .set("artifact_id", artifactId)
                .set("uploaded_size_bytes", session.getExpectedSizeBytes())
                .set("received_chunk_count", session.getChunkCount())
                .set("error_code", null)
                .set("error_message", null)
                .set("completed_at", now)
                .set("updated_at", now));
    }

    private void markFailed(ProblemDraftTestcaseUploadSessionEntity session, String code, String message) {
        LocalDateTime now = LocalDateTime.now();
        sessionMapper.update(null, new UpdateWrapper<ProblemDraftTestcaseUploadSessionEntity>()
                .eq("id", session.getId())
                .set("status", STATUS_FAILED)
                .set("error_code", code)
                .set("error_message", truncate(message, 1000))
                .set("completed_at", now)
                .set("updated_at", now));
    }

    private void markExpired(ProblemDraftTestcaseUploadSessionEntity session) {
        LocalDateTime now = LocalDateTime.now();
        sessionMapper.update(null, new UpdateWrapper<ProblemDraftTestcaseUploadSessionEntity>()
                .eq("id", session.getId())
                .eq("status", STATUS_UPLOADING)
                .set("status", STATUS_EXPIRED)
                .set("error_code", "UPLOAD_EXPIRED")
                .set("error_message", "Problem draft testcase upload expired before completion")
                .set("completed_at", now)
                .set("updated_at", now));
        session.setStatus(STATUS_EXPIRED);
    }

    private ProblemDraftTestcaseUploadStatusResponse statusResponse(
            ProblemDraftTestcaseUploadSessionEntity session) {
        return new ProblemDraftTestcaseUploadStatusResponse(
                session.getId(), session.getWorkspaceId(), session.getStatus(),
                session.getReceivedChunkCount(), session.getChunkCount(), session.getUploadedSizeBytes(),
                session.getExpectedSizeBytes(), session.getArtifactId(), session.getErrorCode(),
                session.getErrorMessage(), toInstant(session.getExpiresAt()), toInstant(session.getUpdatedAt()),
                toInstant(session.getCompletedAt()));
    }

    private ProblemDraftTestcaseUploadSessionEntity refreshSession(String id) {
        ProblemDraftTestcaseUploadSessionEntity session = sessionMapper.selectById(id);
        if (session == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase upload not found");
        }
        return session;
    }

    private Path chunkPath(ProblemDraftTestcaseUploadSessionEntity session, int index) {
        Path directory = resolveSessionDirectory(session);
        Path chunk = directory.resolve("chunks").resolve(String.format(Locale.ROOT, "%05d.part", index)).normalize();
        ensureInside(directory, chunk);
        return chunk;
    }

    private Path resolveSessionDirectory(ProblemDraftTestcaseUploadSessionEntity session) {
        if (!StringUtils.hasText(session.getTempStoragePath())) {
            throw badRequest("Invalid testcase upload storage path");
        }
        Path directory = storageRoot.resolve(session.getTempStoragePath()).normalize();
        ensureInside(uploadRoot, directory);
        Path uploadPart = directory.getFileName();
        Path fingerprintPart = parentName(directory, 1);
        Path draftPart = parentName(directory, 2);
        Path workspacePart = parentName(directory, 3);
        if (uploadPart == null || fingerprintPart == null || draftPart == null || workspacePart == null
                || !session.getId().equals(uploadPart.toString())
                || !String.valueOf(session.getDraftId()).equals(draftPart.toString())
                || !session.getWorkspaceId().equals(workspacePart.toString())
                || !SHA256.matcher(fingerprintPart.toString()).matches()) {
            throw badRequest("Invalid testcase upload storage path");
        }
        return directory;
    }

    private Path parentName(Path path, int levels) {
        Path current = path;
        for (int index = 0; index < levels; index++) {
            current = current == null ? null : current.getParent();
        }
        return current == null ? null : current.getFileName();
    }

    private String capturedFingerprint(ProblemDraftTestcaseUploadSessionEntity session) {
        Path name = parentName(resolveSessionDirectory(session), 1);
        return name == null ? "" : name.toString();
    }

    private long expectedChunkSize(ProblemDraftTestcaseUploadSessionEntity session, int index) {
        if (index < session.getChunkCount() - 1) {
            return session.getChunkSizeBytes();
        }
        return session.getExpectedSizeBytes()
                - (long) session.getChunkSizeBytes() * (session.getChunkCount() - 1L);
    }

    private DigestResult storeBounded(InputStream input, Path target, long limit) throws IOException {
        MessageDigest digest = sha256Digest();
        long total = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total = safeAdd(total, read);
                if (total > limit || total > MAX_PACKAGE_BYTES) {
                    throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,
                            "Testcase upload chunk exceeds its expected size");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return new DigestResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private DigestResult digest(Path path, long limit) throws IOException {
        MessageDigest digest = sha256Digest();
        long total = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total = safeAdd(total, read);
                if (total > limit || total > MAX_PACKAGE_BYTES) {
                    throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,
                            "Persisted testcase upload chunk exceeds its expected size");
                }
                digest.update(buffer, 0, read);
            }
        }
        return new DigestResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private void moveAtomically(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException ex) {
            if (replace) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    private String relativeStoragePath(Path path) {
        ensureInside(storageRoot, path);
        return storageRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private String normalizeFileName(String value) {
        String name = value == null ? "" : value.trim();
        if (!StringUtils.hasText(name) || name.length() > 255 || name.contains("/") || name.contains("\\")
                || !name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw badRequest("Only a simple .zip testcase package file name is supported");
        }
        return name.replace('\r', '_').replace('\n', '_');
    }

    private String normalizeSha256(String value, String field) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw badRequest(field + " must be a 64-character SHA-256 hexadecimal value");
        }
        return normalized;
    }

    private String normalizeUploadId(String value) {
        String id = value == null ? "" : value.trim();
        if (!SAFE_SEGMENT.matcher(id).matches()) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase upload not found");
        }
        return id;
    }

    private String safeSegment(String value, String field) {
        if (value == null || !SAFE_SEGMENT.matcher(value).matches()) {
            throw badRequest("Invalid " + field + " identifier");
        }
        return value;
    }

    private void ensureInside(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw badRequest("Invalid testcase package storage path");
        }
    }

    private void deleteRecursivelyQuietly(ProblemDraftTestcaseUploadSessionEntity session) {
        try {
            deleteRecursively(resolveSessionDirectory(session));
        } catch (RuntimeException ignored) {
            // Invalid persisted paths are never followed during best-effort cleanup.
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        ensureInside(uploadRoot, path);
        if (path.equals(uploadRoot)) {
            throw badRequest("Refusing to remove the testcase upload root");
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deleteIfExists);
        } catch (IOException ignored) {
            // Expired isolated upload data is removed on the next cleanup pass.
        }
    }

    private void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw internal("SHA-256 algorithm is unavailable");
        }
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw internal("Unable to serialize testcase package validation summary");
        }
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private long safeAdd(long value, long addend) {
        return value > Long.MAX_VALUE - addend ? Long.MAX_VALUE : value + addend;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private DomainException badRequest(String message) {
        return new DomainException(ErrorCode.BAD_REQUEST, message);
    }

    private DomainException internal(String message) {
        return new DomainException(ErrorCode.INTERNAL_ERROR, message);
    }

    private record DigestResult(long sizeBytes, String sha256) {
    }
}
