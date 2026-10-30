package com.aioj.next.ai.domain.retention;

import com.aioj.next.ai.domain.ProblemDraftTestcaseArtifactService;
import com.aioj.next.common.error.SafeErrors;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Plan S6 (T2.6): removes the AI data whose retention has passed, once a night. Conversations
 * the student deleted go 30 days after the deletion (contest ones after 365), student run traces
 * after 90 days, a deleted draft's workspace runs 30 days after the deletion, and testcase package
 * files that were replaced or failed after 30 days. A pass is bounded in time and carries on in the
 * next one; only counts are logged.
 */
@Component
public class RetentionJob {
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionStore store;
    private final ProblemDraftTestcaseArtifactService artifacts;
    private final RetentionProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    @Autowired
    public RetentionJob(RetentionStore store, ProblemDraftTestcaseArtifactService artifacts,
                        RetentionProperties properties, ObjectProvider<MeterRegistry> meters) {
        this(store, artifacts, properties, meters.getIfAvailable(), Clock.systemDefaultZone());
    }

    RetentionJob(RetentionStore store, ProblemDraftTestcaseArtifactService artifacts,
                 RetentionProperties properties, MeterRegistry meters, Clock clock) {
        this.store = store;
        this.artifacts = artifacts;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /** What a pass deleted, or would delete in a dry run: completed, partial (out of time), dry_run, skipped or failed. */
    public record Pass(String outcome, Map<String, Long> counts) {
    }

    @Scheduled(cron = "${aioj.ai.retention.cron:0 30 3 * * *}")
    public void scheduledPass() {
        if (properties.isEnabled()) {
            pass();
        }
    }

    public Pass pass() {
        long started = System.nanoTime();
        Pass[] done = new Pass[1];
        Pass pass;
        try {
            boolean locked = store.withLock(() -> done[0] = properties.isDryRun() ? dryRun() : purge());
            pass = locked ? done[0] : new Pass("skipped", Map.of());
        } catch (RuntimeException ex) {
            log.warn("Retention pass failed: {}", SafeErrors.describe(ex));
            pass = new Pass("failed", Map.of());
        }
        log.info("Retention pass {} in {} ms: {}", pass.outcome(), (System.nanoTime() - started) / 1_000_000,
                pass.counts());
        record(pass);
        return pass;
    }

    private Pass purge() {
        Instant deadline = clock.instant().plus(properties.getMaxRunTime());
        LocalDateTime now = LocalDateTime.now(clock);
        int batch = Math.max(1, properties.getBatchSize());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String category : List.of("conversations", "messages", "other_rows", "run_traces", "artifact_files")) {
            counts.put(category, 0L);
        }
        boolean complete = purgeConversations(now, batch, deadline, counts)
                && purgeRuns(() -> store.expiredStudentRuns(now.minus(properties.getRunTraces()), batch),
                        batch, deadline, counts)
                && purgeRuns(() -> store.deletedDraftRuns(now.minus(properties.getDeletedDrafts()), batch),
                        batch, deadline, counts)
                && purgeArtifacts(now, batch, deadline, counts);
        return new Pass(complete ? "completed" : "partial", counts);
    }

    private boolean purgeConversations(LocalDateTime now, int batch, Instant deadline, Map<String, Long> counts) {
        LocalDateTime cutoff = now.minus(properties.getDeletedConversations());
        LocalDateTime contestCutoff = now.minus(properties.getContestConversations());
        String after = "";
        while (true) {
            if (timeUp(deadline)) {
                return false;
            }
            List<RetentionStore.Conversation> found = store.deletedConversations(cutoff, contestCutoff, after, batch);
            for (RetentionStore.Conversation conversation : found) {
                if (timeUp(deadline)) {
                    return false;
                }
                RetentionStore.PurgedRows rows = store.purgeConversation(conversation, cutoff);
                counts.merge("conversations", rows.conversations(), Long::sum);
                counts.merge("messages", rows.messages(), Long::sum);
                counts.merge("other_rows", rows.otherRows(), Long::sum);
                counts.merge("run_traces", rows.runTraces(), Long::sum);
                after = conversation.id();
            }
            if (found.size() < batch) {
                return true;
            }
        }
    }

    private boolean purgeRuns(Supplier<List<Long>> select, int batch, Instant deadline, Map<String, Long> counts) {
        while (true) {
            if (timeUp(deadline)) {
                return false;
            }
            List<Long> runs = select.get();
            if (runs.isEmpty()) {
                return true;
            }
            counts.merge("run_traces", store.purgeRuns(runs), Long::sum);
            if (runs.size() < batch) {
                return true;
            }
        }
    }

    private boolean purgeArtifacts(LocalDateTime now, int batch, Instant deadline, Map<String, Long> counts) {
        LocalDateTime retiredCutoff = now.minus(properties.getRetiredArtifacts());
        LocalDateTime draftCutoff = now.minus(properties.getDeletedDrafts());
        long after = 0;
        while (true) {
            if (timeUp(deadline)) {
                return false;
            }
            List<RetentionStore.Artifact> found = store.retiredArtifacts(retiredCutoff, draftCutoff, after, batch);
            for (RetentionStore.Artifact artifact : found) {
                try {
                    artifacts.purgeFile(artifact.id(), artifact.storagePath());
                    counts.merge("artifact_files", 1L, Long::sum);
                } catch (IOException | RuntimeException ex) {
                    log.warn("Retention left testcase artifact {} for the next pass: {}", artifact.id(),
                            SafeErrors.describe(ex));
                }
                after = artifact.id();
            }
            if (found.size() < batch) {
                return true;
            }
        }
    }

    private Pass dryRun() {
        LocalDateTime now = LocalDateTime.now(clock);
        return new Pass("dry_run", store.count(now.minus(properties.getDeletedConversations()),
                now.minus(properties.getContestConversations()), now.minus(properties.getRunTraces()),
                now.minus(properties.getDeletedDrafts()), now.minus(properties.getRetiredArtifacts())));
    }

    private boolean timeUp(Instant deadline) {
        return !clock.instant().isBefore(deadline);
    }

    private void record(Pass pass) {
        if (meters == null) {
            return;
        }
        try {
            meters.counter("aioj.retention.runs", "outcome", pass.outcome()).increment();
            if (!"dry_run".equals(pass.outcome())) {
                pass.counts().forEach((category, deleted) ->
                        meters.counter("aioj.retention.deleted", "category", category).increment(deleted));
            }
        } catch (RuntimeException ignored) {
            // Metrics must not change the pass.
        }
    }
}
