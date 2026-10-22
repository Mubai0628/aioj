package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_problem_draft_testcase_upload_sessions")
public class ProblemDraftTestcaseUploadSessionEntity {
    @TableId
    private String id;
    private String workspaceId;
    private Long draftId;
    private Long creatorUserId;
    private String status;
    private String fileName;
    private String tempStoragePath;
    private Long expectedSizeBytes;
    private String expectedSha256;
    private Long uploadedSizeBytes;
    private Integer chunkSizeBytes;
    private Integer chunkCount;
    private Integer receivedChunkCount;
    private Long artifactId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public Long getDraftId() { return draftId; }
    public void setDraftId(Long draftId) { this.draftId = draftId; }
    public Long getCreatorUserId() { return creatorUserId; }
    public void setCreatorUserId(Long creatorUserId) { this.creatorUserId = creatorUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getTempStoragePath() { return tempStoragePath; }
    public void setTempStoragePath(String tempStoragePath) { this.tempStoragePath = tempStoragePath; }
    public Long getExpectedSizeBytes() { return expectedSizeBytes; }
    public void setExpectedSizeBytes(Long expectedSizeBytes) { this.expectedSizeBytes = expectedSizeBytes; }
    public String getExpectedSha256() { return expectedSha256; }
    public void setExpectedSha256(String expectedSha256) { this.expectedSha256 = expectedSha256; }
    public Long getUploadedSizeBytes() { return uploadedSizeBytes; }
    public void setUploadedSizeBytes(Long uploadedSizeBytes) { this.uploadedSizeBytes = uploadedSizeBytes; }
    public Integer getChunkSizeBytes() { return chunkSizeBytes; }
    public void setChunkSizeBytes(Integer chunkSizeBytes) { this.chunkSizeBytes = chunkSizeBytes; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public Integer getReceivedChunkCount() { return receivedChunkCount; }
    public void setReceivedChunkCount(Integer receivedChunkCount) { this.receivedChunkCount = receivedChunkCount; }
    public Long getArtifactId() { return artifactId; }
    public void setArtifactId(Long artifactId) { this.artifactId = artifactId; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
