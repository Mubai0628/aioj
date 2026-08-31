package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

@TableName("ai_problem_draft_testcase_upload_chunks")
public class ProblemDraftTestcaseUploadChunkEntity {
    @TableId
    private String uploadId;
    private Integer chunkIndex;
    private Integer sizeBytes;
    private String sha256;
    private LocalDateTime createdAt;

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public Integer getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Integer sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
