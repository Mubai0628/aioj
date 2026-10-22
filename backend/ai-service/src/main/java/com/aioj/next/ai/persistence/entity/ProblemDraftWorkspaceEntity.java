package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_problem_draft_workspaces")
public class ProblemDraftWorkspaceEntity {
    @TableId
    private String id;
    private Long creatorUserId;
    private String conversationId;
    private Long candidateDraftId;
    private Long sourceDraftId;
    private Long currentDraftId;
    private Long currentAgentRunId;
    private Long currentArtifactId;
    private String activeTurnId;
    private String status;
    private String stage;
    private String agentMode;
    private Long stateVersion;
    private String workingStateJson;
    private Integer requestedCfRating;
    private String reviewRisk;
    private String contentFingerprint;
    private String verificationFingerprint;
    private String staticValidationJson;
    private String samplesValidationJson;
    private String packageValidationJson;
    private String safeSummary;
    private String resourceMetricsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getCreatorUserId() { return creatorUserId; }
    public void setCreatorUserId(Long creatorUserId) { this.creatorUserId = creatorUserId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public Long getCandidateDraftId() { return candidateDraftId; }
    public void setCandidateDraftId(Long candidateDraftId) { this.candidateDraftId = candidateDraftId; }
    public Long getSourceDraftId() { return sourceDraftId; }
    public void setSourceDraftId(Long sourceDraftId) { this.sourceDraftId = sourceDraftId; }
    public Long getCurrentDraftId() { return currentDraftId; }
    public void setCurrentDraftId(Long currentDraftId) { this.currentDraftId = currentDraftId; }
    public Long getCurrentAgentRunId() { return currentAgentRunId; }
    public void setCurrentAgentRunId(Long currentAgentRunId) { this.currentAgentRunId = currentAgentRunId; }
    public Long getCurrentArtifactId() { return currentArtifactId; }
    public void setCurrentArtifactId(Long currentArtifactId) { this.currentArtifactId = currentArtifactId; }
    public String getActiveTurnId() { return activeTurnId; }
    public void setActiveTurnId(String activeTurnId) { this.activeTurnId = activeTurnId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getAgentMode() { return agentMode; }
    public void setAgentMode(String agentMode) { this.agentMode = agentMode; }
    public Long getStateVersion() { return stateVersion; }
    public void setStateVersion(Long stateVersion) { this.stateVersion = stateVersion; }
    public String getWorkingStateJson() { return workingStateJson; }
    public void setWorkingStateJson(String workingStateJson) { this.workingStateJson = workingStateJson; }
    public Integer getRequestedCfRating() { return requestedCfRating; }
    public void setRequestedCfRating(Integer requestedCfRating) { this.requestedCfRating = requestedCfRating; }
    public String getReviewRisk() { return reviewRisk; }
    public void setReviewRisk(String reviewRisk) { this.reviewRisk = reviewRisk; }
    public String getContentFingerprint() { return contentFingerprint; }
    public void setContentFingerprint(String contentFingerprint) { this.contentFingerprint = contentFingerprint; }
    public String getVerificationFingerprint() { return verificationFingerprint; }
    public void setVerificationFingerprint(String verificationFingerprint) { this.verificationFingerprint = verificationFingerprint; }
    public String getStaticValidationJson() { return staticValidationJson; }
    public void setStaticValidationJson(String staticValidationJson) { this.staticValidationJson = staticValidationJson; }
    public String getSamplesValidationJson() { return samplesValidationJson; }
    public void setSamplesValidationJson(String samplesValidationJson) { this.samplesValidationJson = samplesValidationJson; }
    public String getPackageValidationJson() { return packageValidationJson; }
    public void setPackageValidationJson(String packageValidationJson) { this.packageValidationJson = packageValidationJson; }
    public String getSafeSummary() { return safeSummary; }
    public void setSafeSummary(String safeSummary) { this.safeSummary = safeSummary; }
    public String getResourceMetricsJson() { return resourceMetricsJson; }
    public void setResourceMetricsJson(String resourceMetricsJson) { this.resourceMetricsJson = resourceMetricsJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
