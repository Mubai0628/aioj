package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseUploadChunkEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseUploadSessionEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseArtifactMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseUploadChunkMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseUploadSessionMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.ai.ProblemDraftTestcaseUploadInitRequest;
import com.aioj.next.contract.ai.ProblemDraftTestcaseUploadInitResponse;
import com.aioj.next.contract.ai.ProblemDraftTestcaseUploadCompleteRequest;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProblemDraftTestcaseUploadServiceTest {
    private static final long USER_ID = 7L;
    private static final String WORKSPACE_ID = "workspace01";
    private static final String FINGERPRINT = "a".repeat(64);

    @TempDir
    Path tempDir;

    private ProblemDraftWorkspaceService workspaceService;
    private ProblemDraftTestcaseUploadSessionMapper sessionMapper;
    private ProblemDraftTestcaseUploadChunkMapper chunkMapper;
    private ProblemDraftTestcaseArtifactMapper artifactMapper;
    private AtomicReference<ProblemDraftTestcaseUploadSessionEntity> storedSession;
    private List<ProblemDraftTestcaseUploadChunkEntity> storedChunks;
    private ProblemDraftWorkspaceEntity workspace;
    private ProblemDraftTestcaseUploadService service;

    @BeforeEach
    void setUp() {
        workspaceService = mock(ProblemDraftWorkspaceService.class);
        sessionMapper = mock(ProblemDraftTestcaseUploadSessionMapper.class);
        chunkMapper = mock(ProblemDraftTestcaseUploadChunkMapper.class);
        artifactMapper = mock(ProblemDraftTestcaseArtifactMapper.class);
        storedSession = new AtomicReference<>();
        storedChunks = new ArrayList<>();
        workspace = workspace(FINGERPRINT);
        when(workspaceService.requireOwned(WORKSPACE_ID, USER_ID)).thenAnswer(ignored -> workspace);
        when(sessionMapper.insert(any(ProblemDraftTestcaseUploadSessionEntity.class))).thenAnswer(invocation -> {
            storedSession.set(invocation.getArgument(0));
            return 1;
        });
        when(sessionMapper.selectById(any())).thenAnswer(ignored -> storedSession.get());
        when(chunkMapper.insert(any(ProblemDraftTestcaseUploadChunkEntity.class))).thenAnswer(invocation -> {
            storedChunks.add(invocation.getArgument(0));
            return 1;
        });
        when(chunkMapper.selectOne(any())).thenAnswer(invocation -> storedChunks.isEmpty() ? null : storedChunks.get(0));
        when(chunkMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(storedChunks));
        when(sessionMapper.update(
                org.mockito.ArgumentMatchers.<ProblemDraftTestcaseUploadSessionEntity>isNull(),
                org.mockito.ArgumentMatchers.<Wrapper<ProblemDraftTestcaseUploadSessionEntity>>any()))
                .thenReturn(1);

        AiProperties properties = new AiProperties();
        properties.getProblemDraft().setTestcaseArtifactStorageRoot(tempDir.toString());
        service = new ProblemDraftTestcaseUploadService(
                workspaceService, sessionMapper, chunkMapper, artifactMapper,
                new DraftTestcasePackageFormatValidator(new ObjectMapper()), new ObjectMapper(), properties);
    }

    @Test
    void initPinsCurrentDraftAndFingerprintUnderConfiguredRoot() throws Exception {
        byte[] content = new byte[ProblemDraftTestcaseUploadService.CHUNK_SIZE_BYTES + 3];
        String sha = sha256(content);

        ProblemDraftTestcaseUploadInitResponse response = service.init(WORKSPACE_ID, USER_ID,
                new ProblemDraftTestcaseUploadInitRequest("manual.zip", (long) content.length, sha));

        assertThat(response.chunkSizeBytes()).isEqualTo(ProblemDraftTestcaseUploadService.CHUNK_SIZE_BYTES);
        assertThat(response.chunkCount()).isEqualTo(2);
        ProblemDraftTestcaseUploadSessionEntity session = storedSession.get();
        assertThat(session.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(session.getDraftId()).isEqualTo(42L);
        assertThat(session.getTempStoragePath()).contains(WORKSPACE_ID).contains(FINGERPRINT).contains(response.uploadId());
        assertThat(Files.isDirectory(tempDir.resolve(session.getTempStoragePath()).resolve("chunks"))).isTrue();
    }

    @Test
    void initRejectsOversizedPackageBeforeCreatingSession() {
        assertThatThrownBy(() -> service.init(WORKSPACE_ID, USER_ID,
                new ProblemDraftTestcaseUploadInitRequest(
                        "manual.zip", ProblemDraftTestcaseUploadService.MAX_PACKAGE_BYTES + 1, "b".repeat(64))))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("50 MiB");

        verify(sessionMapper, never()).insert(any(ProblemDraftTestcaseUploadSessionEntity.class));
    }

    @Test
    void uploadChunkIsIdempotentForSameIndexAndHash() throws Exception {
        byte[] content = "zip".getBytes();
        String sha = sha256(content);
        ProblemDraftTestcaseUploadInitResponse init = service.init(WORKSPACE_ID, USER_ID,
                new ProblemDraftTestcaseUploadInitRequest("manual.zip", (long) content.length, sha));

        service.uploadChunk(WORKSPACE_ID, init.uploadId(), 0, USER_ID, sha,
                new ByteArrayInputStream(content));
        service.uploadChunk(WORKSPACE_ID, init.uploadId(), 0, USER_ID, sha,
                new ByteArrayInputStream(content));

        ArgumentCaptor<ProblemDraftTestcaseUploadChunkEntity> chunk =
                ArgumentCaptor.forClass(ProblemDraftTestcaseUploadChunkEntity.class);
        verify(chunkMapper).insert(chunk.capture());
        assertThat(chunk.getValue().getSizeBytes()).isEqualTo(content.length);
        assertThat(chunk.getValue().getSha256()).isEqualTo(sha);
        Path persisted = tempDir.resolve(storedSession.get().getTempStoragePath())
                .resolve("chunks").resolve("00000.part");
        assertThat(Files.readAllBytes(persisted)).isEqualTo(content);
    }

    @Test
    void uploadChunkRejectsWorkspaceFingerprintChange() throws Exception {
        byte[] content = "zip".getBytes();
        String sha = sha256(content);
        ProblemDraftTestcaseUploadInitResponse init = service.init(WORKSPACE_ID, USER_ID,
                new ProblemDraftTestcaseUploadInitRequest("manual.zip", (long) content.length, sha));
        workspace.setVerificationFingerprint("c".repeat(64));

        assertThatThrownBy(() -> service.uploadChunk(WORKSPACE_ID, init.uploadId(), 0, USER_ID, sha,
                new ByteArrayInputStream(content)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("content changed");
        assertThat(storedChunks).isEmpty();
    }

    @Test
    void completeCreatesReadyManualArtifactAfterFormatValidation() throws Exception {
        byte[] content = packageZip(Map.of(
                "manifest.json", """
                        {"version":"draft-v1","checker":{"type":"STANDARD"},"cases":[
                          {"name":"hidden-1","input":"tests/001.in","output":"tests/001.out"}
                        ]}
                        """,
                "tests/001.in", "2\n",
                "tests/001.out", "4\n"
        ));
        String sha = sha256(content);
        ProblemDraftTestcaseUploadInitResponse init = service.init(WORKSPACE_ID, USER_ID,
                new ProblemDraftTestcaseUploadInitRequest("manual.zip", (long) content.length, sha));
        service.uploadChunk(WORKSPACE_ID, init.uploadId(), 0, USER_ID, sha,
                new ByteArrayInputStream(content));

        service.complete(WORKSPACE_ID, init.uploadId(), USER_ID,
                new ProblemDraftTestcaseUploadCompleteRequest(sha));

        ArgumentCaptor<com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity> artifact =
                ArgumentCaptor.forClass(com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity.class);
        verify(artifactMapper).insert(artifact.capture());
        assertThat(artifact.getValue().getSource()).isEqualTo("MANUAL_UPLOAD");
        assertThat(artifact.getValue().getStatus()).isEqualTo("READY");
        assertThat(artifact.getValue().getFormatStatus()).isEqualTo("READY");
        assertThat(artifact.getValue().getExecutionStatus()).isEqualTo("NOT_RUN");
        assertThat(artifact.getValue().getVerificationFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(artifact.getValue().getCaseCount()).isEqualTo(1);
        assertThat(Files.isRegularFile(tempDir.resolve(artifact.getValue().getStoragePath()))).isTrue();
        verify(workspaceService).attachArtifact(WORKSPACE_ID, USER_ID, artifact.getValue());
    }

    private ProblemDraftWorkspaceEntity workspace(String fingerprint) {
        ProblemDraftWorkspaceEntity entity = new ProblemDraftWorkspaceEntity();
        entity.setId(WORKSPACE_ID);
        entity.setCreatorUserId(USER_ID);
        entity.setCurrentDraftId(42L);
        entity.setVerificationFingerprint(fingerprint);
        return entity;
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private byte[] packageZip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
