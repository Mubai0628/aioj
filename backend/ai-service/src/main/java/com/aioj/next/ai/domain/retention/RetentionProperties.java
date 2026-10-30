package com.aioj.next.ai.domain.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long AI data is kept (plan S6, T2.6). The job is off unless the deployment turns it
 * on: starting a local stack must not delete a developer's data.
 */
@ConfigurationProperties(prefix = "aioj.ai.retention")
public class RetentionProperties {

    private boolean enabled = false;
    /** Count what a pass would delete and delete nothing. */
    private boolean dryRun = false;
    /** A conversation the student deleted is removed this long after the deletion. */
    private Duration deletedConversations = Duration.ofDays(30);
    /** The same for a conversation with contest records, so deleting it does not erase them. */
    private Duration contestConversations = Duration.ofDays(365);
    /** Tool calls, context manifests and runs of student turns. */
    private Duration runTraces = Duration.ofDays(90);
    /** A draft workspace's runs, counted from the deletion of its draft. */
    private Duration deletedDrafts = Duration.ofDays(30);
    /** Testcase package files that were replaced, failed or never finished writing. */
    private Duration retiredArtifacts = Duration.ofDays(30);
    private int batchSize = 500;
    /** A pass stops here and the next one carries on. */
    private Duration maxRunTime = Duration.ofMinutes(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public Duration getDeletedConversations() {
        return deletedConversations;
    }

    public void setDeletedConversations(Duration deletedConversations) {
        this.deletedConversations = deletedConversations;
    }

    public Duration getContestConversations() {
        return contestConversations;
    }

    public void setContestConversations(Duration contestConversations) {
        this.contestConversations = contestConversations;
    }

    public Duration getRunTraces() {
        return runTraces;
    }

    public void setRunTraces(Duration runTraces) {
        this.runTraces = runTraces;
    }

    public Duration getDeletedDrafts() {
        return deletedDrafts;
    }

    public void setDeletedDrafts(Duration deletedDrafts) {
        this.deletedDrafts = deletedDrafts;
    }

    public Duration getRetiredArtifacts() {
        return retiredArtifacts;
    }

    public void setRetiredArtifacts(Duration retiredArtifacts) {
        this.retiredArtifacts = retiredArtifacts;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getMaxRunTime() {
        return maxRunTime;
    }

    public void setMaxRunTime(Duration maxRunTime) {
        this.maxRunTime = maxRunTime;
    }
}
