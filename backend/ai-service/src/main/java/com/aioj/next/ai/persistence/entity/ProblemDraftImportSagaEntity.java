package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_problem_draft_import_sagas")
public class ProblemDraftImportSagaEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String workspaceId;
    private Long draftId;
    private Long artifactId;
    private Long creatorUserId;
    private String idempotencyKey;
    private String state;
    private String importMode;
    private String targetVisibility;
    private Boolean acknowledgeUnverified;
    private Long requestedProblemId;
    private Long problemId;
    private Long testcasePackageId;
    private String artifactSha256;
    private String verificationFingerprint;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public Long getDraftId() { return draftId; }
    public void setDraftId(Long draftId) { this.draftId = draftId; }
    public Long getArtifactId() { return artifactId; }
    public void setArtifactId(Long artifactId) { this.artifactId = artifactId; }
    public Long getCreatorUserId() { return creatorUserId; }
    public void setCreatorUserId(Long creatorUserId) { this.creatorUserId = creatorUserId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getImportMode() { return importMode; }
    public void setImportMode(String importMode) { this.importMode = importMode; }
    public String getTargetVisibility() { return targetVisibility; }
    public void setTargetVisibility(String targetVisibility) { this.targetVisibility = targetVisibility; }
    public Boolean getAcknowledgeUnverified() { return acknowledgeUnverified; }
    public void setAcknowledgeUnverified(Boolean acknowledgeUnverified) { this.acknowledgeUnverified = acknowledgeUnverified; }
    public Long getRequestedProblemId() { return requestedProblemId; }
    public void setRequestedProblemId(Long requestedProblemId) { this.requestedProblemId = requestedProblemId; }
    public Long getProblemId() { return problemId; }
    public void setProblemId(Long problemId) { this.problemId = problemId; }
    public Long getTestcasePackageId() { return testcasePackageId; }
    public void setTestcasePackageId(Long testcasePackageId) { this.testcasePackageId = testcasePackageId; }
    public String getArtifactSha256() { return artifactSha256; }
    public void setArtifactSha256(String artifactSha256) { this.artifactSha256 = artifactSha256; }
    public String getVerificationFingerprint() { return verificationFingerprint; }
    public void setVerificationFingerprint(String verificationFingerprint) { this.verificationFingerprint = verificationFingerprint; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
