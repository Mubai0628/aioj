package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_problem_draft_executions")
public class ProblemDraftExecutionEntity {
    @TableId
    private String id;
    private String workspaceId;
    private Long creatorUserId;
    private Long agentRunId;
    private String toolCallId;
    private String purpose;
    private String language;
    private String status;
    private String stdout;
    private String stderr;
    private Long stdoutBytes;
    private Long stderrBytes;
    private Boolean outputTruncated;
    private String resultHash;
    private Long timeMillis;
    private Long memoryKb;
    private Long procPeak;
    private Integer exitStatus;
    private Long runTimeMillis;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public Long getCreatorUserId() { return creatorUserId; }
    public void setCreatorUserId(Long creatorUserId) { this.creatorUserId = creatorUserId; }
    public Long getAgentRunId() { return agentRunId; }
    public void setAgentRunId(Long agentRunId) { this.agentRunId = agentRunId; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStdout() { return stdout; }
    public void setStdout(String stdout) { this.stdout = stdout; }
    public String getStderr() { return stderr; }
    public void setStderr(String stderr) { this.stderr = stderr; }
    public Long getStdoutBytes() { return stdoutBytes; }
    public void setStdoutBytes(Long stdoutBytes) { this.stdoutBytes = stdoutBytes; }
    public Long getStderrBytes() { return stderrBytes; }
    public void setStderrBytes(Long stderrBytes) { this.stderrBytes = stderrBytes; }
    public Boolean getOutputTruncated() { return outputTruncated; }
    public void setOutputTruncated(Boolean outputTruncated) { this.outputTruncated = outputTruncated; }
    public String getResultHash() { return resultHash; }
    public void setResultHash(String resultHash) { this.resultHash = resultHash; }
    public Long getTimeMillis() { return timeMillis; }
    public void setTimeMillis(Long timeMillis) { this.timeMillis = timeMillis; }
    public Long getMemoryKb() { return memoryKb; }
    public void setMemoryKb(Long memoryKb) { this.memoryKb = memoryKb; }
    public Long getProcPeak() { return procPeak; }
    public void setProcPeak(Long procPeak) { this.procPeak = procPeak; }
    public Integer getExitStatus() { return exitStatus; }
    public void setExitStatus(Integer exitStatus) { this.exitStatus = exitStatus; }
    public Long getRunTimeMillis() { return runTimeMillis; }
    public void setRunTimeMillis(Long runTimeMillis) { this.runTimeMillis = runTimeMillis; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
