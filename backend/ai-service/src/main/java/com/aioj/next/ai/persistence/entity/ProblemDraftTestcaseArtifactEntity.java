package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_problem_draft_testcase_artifacts")
public class ProblemDraftTestcaseArtifactEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long draftId;
    private String workspaceId;
    private Long creatorUserId;
    private String source;
    private String status;
    private String formatStatus;
    private String executionStatus;
    private String verificationMode;
    private String verificationFingerprint;
    private Long agentRunId;
    private String fileName;
    private String storagePath;
    private Long fileSizeBytes;
    private String sha256;
    private Integer caseCount;
    private Long totalInputBytes;
    private Long totalOutputBytes;
    private Long largestCaseBytes;
    private String packageSummaryJson;
    private String failureSummary;
    private LocalDateTime generatedAt;
    private LocalDateTime validatedAt;
    private Long importedProblemId;
    private Long problemTestcasePackageId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDraftId() { return draftId; }
    public void setDraftId(Long draftId) { this.draftId = draftId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public Long getCreatorUserId() { return creatorUserId; }
    public void setCreatorUserId(Long creatorUserId) { this.creatorUserId = creatorUserId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFormatStatus() { return formatStatus; }
    public void setFormatStatus(String formatStatus) { this.formatStatus = formatStatus; }
    public String getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(String executionStatus) { this.executionStatus = executionStatus; }
    public String getVerificationMode() { return verificationMode; }
    public void setVerificationMode(String verificationMode) { this.verificationMode = verificationMode; }
    public String getVerificationFingerprint() { return verificationFingerprint; }
    public void setVerificationFingerprint(String verificationFingerprint) { this.verificationFingerprint = verificationFingerprint; }
    public Long getAgentRunId() { return agentRunId; }
    public void setAgentRunId(Long agentRunId) { this.agentRunId = agentRunId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Integer getCaseCount() { return caseCount; }
    public void setCaseCount(Integer caseCount) { this.caseCount = caseCount; }
    public Long getTotalInputBytes() { return totalInputBytes; }
    public void setTotalInputBytes(Long totalInputBytes) { this.totalInputBytes = totalInputBytes; }
    public Long getTotalOutputBytes() { return totalOutputBytes; }
    public void setTotalOutputBytes(Long totalOutputBytes) { this.totalOutputBytes = totalOutputBytes; }
    public Long getLargestCaseBytes() { return largestCaseBytes; }
    public void setLargestCaseBytes(Long largestCaseBytes) { this.largestCaseBytes = largestCaseBytes; }
    public String getPackageSummaryJson() { return packageSummaryJson; }
    public void setPackageSummaryJson(String packageSummaryJson) { this.packageSummaryJson = packageSummaryJson; }
    public String getFailureSummary() { return failureSummary; }
    public void setFailureSummary(String failureSummary) { this.failureSummary = failureSummary; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public LocalDateTime getValidatedAt() { return validatedAt; }
    public void setValidatedAt(LocalDateTime validatedAt) { this.validatedAt = validatedAt; }
    public Long getImportedProblemId() { return importedProblemId; }
    public void setImportedProblemId(Long importedProblemId) { this.importedProblemId = importedProblemId; }
    public Long getProblemTestcasePackageId() { return problemTestcasePackageId; }
    public void setProblemTestcasePackageId(Long problemTestcasePackageId) { this.problemTestcasePackageId = problemTestcasePackageId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
