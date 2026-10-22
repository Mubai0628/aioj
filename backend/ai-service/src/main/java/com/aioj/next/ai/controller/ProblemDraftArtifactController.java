package com.aioj.next.ai.controller;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.springframework.util.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.core.io.InputStreamResource;
import com.aioj.next.contract.ai.ProblemDraftTestcaseArtifactPreviewResponse;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.domain.ProblemDraftTestcaseArtifactService;
import com.aioj.next.ai.domain.draftagent.ProblemDraftImportService;
import com.aioj.next.ai.domain.draftagent.ProblemDraftTestcaseUploadService;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.ai.domain.ProblemDraftGenerationJobService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.ProblemDraftAgentTurnRequest;
import com.aioj.next.contract.ai.ProblemDraftImportRequest;
import com.aioj.next.contract.ai.ProblemDraftImportResponse;
import com.aioj.next.contract.ai.ProblemDraftTestcaseArtifactSummary;
import com.aioj.next.contract.ai.ProblemDraftTestcaseUploadCompleteRequest;
import com.aioj.next.contract.ai.ProblemDraftTestcaseUploadInitRequest;
import com.aioj.next.contract.ai.ProblemDraftTestcaseUploadInitResponse;
import com.aioj.next.contract.ai.ProblemDraftTestcaseUploadStatusResponse;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Staff-only artifact transfer and import commands for one isolated draft workspace. */
@RestController
@RequestMapping("/ai/admin/problem-draft-workspaces/{workspaceId}")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class ProblemDraftArtifactController {
    private final ProblemDraftTestcaseUploadService uploadService;
    private final ProblemDraftImportService importService;
    private final ProblemDraftGenerationJobService generationJobService;
    private final ProblemDraftWorkspaceService workspaceService;
    private final ProblemDraftTestcaseArtifactService artifactService;

    public ProblemDraftArtifactController(ProblemDraftTestcaseUploadService uploadService,
                                          ProblemDraftImportService importService,
                                          ProblemDraftGenerationJobService generationJobService,
                                          ProblemDraftWorkspaceService workspaceService,
                                          ProblemDraftTestcaseArtifactService artifactService) {
        this.uploadService = uploadService;
        this.importService = importService;
        this.generationJobService = generationJobService;
        this.workspaceService = workspaceService;
        this.artifactService = artifactService;
    }

    @PostMapping("/testcase-uploads")
    public ApiResponse<ProblemDraftTestcaseUploadInitResponse> initUpload(
            @PathVariable String workspaceId,
            @RequestBody @Valid ProblemDraftTestcaseUploadInitRequest request
    ) {
        return ApiResponse.ok(uploadService.init(workspaceId, SecuritySupport.currentUserId(), request));
    }

    @PutMapping(value = "/testcase-uploads/{uploadId}/chunks/{index}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<ProblemDraftTestcaseUploadStatusResponse> uploadChunk(
            @PathVariable String workspaceId,
            @PathVariable String uploadId,
            @PathVariable int index,
            @RequestHeader("X-Chunk-Sha256") String chunkSha256,
            HttpServletRequest servletRequest
    ) throws IOException {
        return ApiResponse.ok(uploadService.uploadChunk(workspaceId, uploadId, index,
                SecuritySupport.currentUserId(), chunkSha256, servletRequest.getInputStream()));
    }

    @PostMapping("/testcase-uploads/{uploadId}/complete")
    public ApiResponse<ProblemDraftTestcaseUploadStatusResponse> completeUpload(
            @PathVariable String workspaceId,
            @PathVariable String uploadId,
            @RequestBody @Valid ProblemDraftTestcaseUploadCompleteRequest request
    ) {
        return ApiResponse.ok(uploadService.complete(workspaceId, uploadId,
                SecuritySupport.currentUserId(), request));
    }

    @GetMapping("/testcase-uploads/{uploadId}/status")
    public ApiResponse<ProblemDraftTestcaseUploadStatusResponse> uploadStatus(
            @PathVariable String workspaceId,
            @PathVariable String uploadId
    ) {
        return ApiResponse.ok(uploadService.status(workspaceId, uploadId, SecuritySupport.currentUserId()));
    }

    /** Names, sizes and the first 8 KB of the first 100 files of the workspace's current package. */
    @GetMapping("/testcase-artifacts/{artifactId}/entries")
    public ApiResponse<ProblemDraftTestcaseArtifactPreviewResponse> previewArtifact(
            @PathVariable String workspaceId,
            @PathVariable Long artifactId
    ) {
        return ApiResponse.ok(artifactService.preview(currentReadyArtifact(workspaceId, artifactId)));
    }

    @GetMapping("/testcase-artifacts/{artifactId}/download")
    public ResponseEntity<InputStreamResource> downloadArtifact(
            @PathVariable String workspaceId,
            @PathVariable Long artifactId
    ) {
        ProblemDraftTestcaseArtifactEntity artifact = currentReadyArtifact(workspaceId, artifactId);
        Path path = artifactService.resolvePath(artifact);
        String fileName = StringUtils.hasText(artifact.getFileName()) ? artifact.getFileName() : "testcase-package.zip";
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(Files.size(path))
                    .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                            String.join(", ", HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_TYPE))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(fileName, StandardCharsets.UTF_8)
                            .build()
                            .toString())
                    .body(new InputStreamResource(Files.newInputStream(path)));
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to open testcase package zip");
        }
    }

    /** Only the workspace's current package, and only for its creator or an admin (workspaceService.get checks). */
    private ProblemDraftTestcaseArtifactEntity currentReadyArtifact(String workspaceId, Long artifactId) {
        ProblemDraftTestcaseArtifactSummary current = workspaceService.get(workspaceId).testcaseArtifact();
        if (current == null || !Objects.equals(current.id(), artifactId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase artifact not found");
        }
        return artifactService.requireReadyWorkspaceArtifact(artifactId, workspaceId);
    }

    @PostMapping("/testcase-artifacts/{artifactId}/agent-validation")
    public ApiResponse<ProblemDraftTestcaseArtifactSummary> validateArtifact(
            @PathVariable String workspaceId,
            @PathVariable Long artifactId
    ) {
        long userId = SecuritySupport.currentUserId();
        var workspace = workspaceService.get(workspaceId);
        ProblemDraftTestcaseArtifactSummary artifact = workspace.testcaseArtifact();
        if (artifact == null || !Objects.equals(artifact.id(), artifactId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase artifact not found");
        }
        if (artifact.stale() || !"READY".equals(artifact.formatStatus())) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Testcase artifact is not READY for the current draft content");
        }
        generationJobService.continueOptimization(userId, workspaceId, new ProblemDraftAgentTurnRequest(
                "artifact-validation-" + UUID.randomUUID(),
                workspace.currentDraftId(),
                "Run controlled full-package acceptance for the current testcase artifact. "
                        + "Use draft.state.get, then draft.testcase.run_package. Do not change draft content "
                        + "unless the package exposes a correctness defect; preserve all human review gates.",
                "VALIDATE_TESTCASE_PACKAGE"
        ));
        return ApiResponse.ok(workspaceService.get(workspaceId).testcaseArtifact());
    }

    @PostMapping("/imports")
    public ApiResponse<ProblemDraftImportResponse> importDraft(
            @PathVariable String workspaceId,
            @RequestBody @Valid ProblemDraftImportRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return ApiResponse.ok(importService.importWorkspace(workspaceId, SecuritySupport.currentUserId(),
                request, authorization));
    }
}
