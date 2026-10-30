package com.aioj.next.ai.domain.retention;

import com.aioj.next.ai.domain.ProblemDraftTestcaseArtifactService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Plan S6: what the nightly pass removes, how it bounds itself, and what it leaves for the next one. */
class RetentionJobTest {
    private static final Instant NOW = Instant.parse("2026-10-30T19:30:00Z");
    private static final LocalDateTime LOCAL_NOW = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final RetentionStore store = mock(RetentionStore.class);
    private final ProblemDraftTestcaseArtifactService artifacts = mock(ProblemDraftTestcaseArtifactService.class);
    private final RetentionProperties properties = new RetentionProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MovingClock clock = new MovingClock(NOW);
    private RetentionJob job;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setBatchSize(2);
        job = new RetentionJob(store, artifacts, properties, meters, clock);
        when(store.withLock(any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return true;
        });
    }

    @Test
    void offByDefaultAndThenTheScheduleTouchesNothing() {
        RetentionProperties defaults = new RetentionProperties();
        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.getDeletedConversations()).isEqualTo(Duration.ofDays(30));
        assertThat(defaults.getContestConversations()).isEqualTo(Duration.ofDays(365));
        assertThat(defaults.getRunTraces()).isEqualTo(Duration.ofDays(90));
        assertThat(defaults.getDeletedDrafts()).isEqualTo(Duration.ofDays(30));
        assertThat(defaults.getRetiredArtifacts()).isEqualTo(Duration.ofDays(30));

        new RetentionJob(store, artifacts, defaults, meters, clock).scheduledPass();

        verifyNoInteractions(store, artifacts);
    }

    @Test
    void eachRuleGetsItsOwnCutoffAndBatchesRunUntilAShortOne() throws IOException {
        RetentionStore.Conversation a = new RetentionStore.Conversation("c-a", 7L);
        RetentionStore.Conversation b = new RetentionStore.Conversation("c-b", 7L);
        RetentionStore.Conversation c = new RetentionStore.Conversation("c-c", 8L);
        LocalDateTime conversationCutoff = LOCAL_NOW.minusDays(30);
        LocalDateTime contestCutoff = LOCAL_NOW.minusDays(365);
        when(store.deletedConversations(conversationCutoff, contestCutoff, "", 2)).thenReturn(List.of(a, b));
        when(store.deletedConversations(conversationCutoff, contestCutoff, "c-b", 2)).thenReturn(List.of(c));
        when(store.purgeConversation(any(), eq(conversationCutoff))).thenReturn(new RetentionStore.PurgedRows(1, 10, 4, 3));
        when(store.expiredStudentRuns(LOCAL_NOW.minusDays(90), 2)).thenReturn(List.of(1L, 2L), List.of());
        when(store.deletedDraftRuns(LOCAL_NOW.minusDays(30), 2)).thenReturn(List.of(5L));
        when(store.purgeRuns(any())).thenReturn(6L);
        when(store.retiredArtifacts(LOCAL_NOW.minusDays(30), LOCAL_NOW.minusDays(30), 0L, 2))
                .thenReturn(List.of(new RetentionStore.Artifact(41L, "9/41.zip")));

        RetentionJob.Pass pass = job.pass();

        assertThat(pass.outcome()).isEqualTo("completed");
        assertThat(pass.counts()).isEqualTo(Map.of(
                "conversations", 3L, "messages", 30L, "other_rows", 12L, "run_traces", 9L + 12L, "artifact_files", 1L));
        verify(store, times(3)).purgeConversation(any(), eq(conversationCutoff));
        verify(store).purgeRuns(List.of(1L, 2L));
        verify(store).purgeRuns(List.of(5L));
        verify(artifacts).purgeFile(41L, "9/41.zip");
        assertThat(meters.counter("aioj.retention.deleted", "category", "messages").count()).isEqualTo(30);
        assertThat(meters.counter("aioj.retention.runs", "outcome", "completed").count()).isEqualTo(1);
    }

    @Test
    void aPassThatRunsOutOfTimeStopsAndTheNextOneCarriesOn() {
        properties.setMaxRunTime(Duration.ofMinutes(10));
        when(store.deletedConversations(any(), any(), anyString(), anyInt())).thenReturn(List.of(
                new RetentionStore.Conversation("c-1", 7L), new RetentionStore.Conversation("c-2", 7L)));
        when(store.purgeConversation(any(), any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofMinutes(6));
            return new RetentionStore.PurgedRows(1, 1, 0, 0);
        });

        RetentionJob.Pass pass = job.pass();

        assertThat(pass.outcome()).isEqualTo("partial");
        verify(store, times(2)).purgeConversation(any(), any());
        verify(store, never()).expiredStudentRuns(any(), anyInt());
        verifyNoInteractions(artifacts);
    }

    @Test
    void aConversationThatCannotBePurgedDoesNotHoldUpTheOnesBehindIt() {
        when(store.deletedConversations(any(), any(), eq(""), anyInt())).thenReturn(List.of(
                new RetentionStore.Conversation("c-1", 7L), new RetentionStore.Conversation("c-2", 7L)));
        when(store.deletedConversations(any(), any(), eq("c-2"), anyInt())).thenReturn(List.of(
                new RetentionStore.Conversation("c-3", 7L)));
        when(store.purgeConversation(any(), any())).thenReturn(RetentionStore.PurgedRows.NONE,
                RetentionStore.PurgedRows.NONE, new RetentionStore.PurgedRows(1, 2, 0, 0));
        when(store.expiredStudentRuns(any(), anyInt())).thenReturn(List.of());
        when(store.deletedDraftRuns(any(), anyInt())).thenReturn(List.of());
        when(store.retiredArtifacts(any(), any(), anyLong(), anyInt())).thenReturn(List.of());

        RetentionJob.Pass pass = job.pass();

        assertThat(pass.outcome()).isEqualTo("completed");
        assertThat(pass.counts()).containsEntry("conversations", 1L).containsEntry("messages", 2L);
        verify(store, times(2)).deletedConversations(any(), any(), anyString(), anyInt());
    }

    @Test
    void aFileThatCannotBeDeletedIsLeftForTheNextPass() throws IOException {
        when(store.deletedConversations(any(), any(), anyString(), anyInt())).thenReturn(List.of());
        when(store.expiredStudentRuns(any(), anyInt())).thenReturn(List.of());
        when(store.deletedDraftRuns(any(), anyInt())).thenReturn(List.of());
        when(store.retiredArtifacts(any(), any(), eq(0L), anyInt())).thenReturn(List.of(
                new RetentionStore.Artifact(41L, "9/41.zip"), new RetentionStore.Artifact(42L, "9/42.zip")));
        when(store.retiredArtifacts(any(), any(), eq(42L), anyInt())).thenReturn(List.of());
        doThrow(new IOException("disk says no")).when(artifacts).purgeFile(41L, "9/41.zip");

        RetentionJob.Pass pass = job.pass();

        assertThat(pass.outcome()).isEqualTo("completed");
        assertThat(pass.counts().get("artifact_files")).isEqualTo(1L);
        verify(artifacts).purgeFile(42L, "9/42.zip");
    }

    @Test
    void anotherInstanceHoldingTheLockMeansThisOneSkips() {
        doReturn(false).when(store).withLock(any());

        assertThat(job.pass().outcome()).isEqualTo("skipped");
        verify(store, never()).deletedConversations(any(), any(), anyString(), anyInt());
        assertThat(meters.counter("aioj.retention.runs", "outcome", "skipped").count()).isEqualTo(1);
    }

    @Test
    void aDryRunCountsAndDeletesNothing() {
        properties.setDryRun(true);
        when(store.count(LOCAL_NOW.minusDays(30), LOCAL_NOW.minusDays(365), LOCAL_NOW.minusDays(90),
                LOCAL_NOW.minusDays(30), LOCAL_NOW.minusDays(30))).thenReturn(Map.of("conversations", 17L));

        RetentionJob.Pass pass = job.pass();

        assertThat(pass.outcome()).isEqualTo("dry_run");
        assertThat(pass.counts()).containsEntry("conversations", 17L);
        verify(store, never()).purgeConversation(any(), any());
        verify(store, never()).purgeRuns(any());
        verifyNoInteractions(artifacts);
        assertThat(meters.find("aioj.retention.deleted").counters()).isEmpty();
    }

    @Test
    void aFailingPassIsCountedAndDoesNotThrow() {
        when(store.deletedConversations(any(), any(), anyString(), anyInt())).thenThrow(new IllegalStateException("db gone"));

        assertThat(job.pass().outcome()).isEqualTo("failed");
        assertThat(meters.counter("aioj.retention.runs", "outcome", "failed").count()).isEqualTo(1);
    }

    @Test
    void theIdBoundSitsJustAboveTheSnowflakesMintedBeforeTheCutoff() {
        ZoneId zone = ZoneId.systemDefault();
        long minted = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        LocalDateTime now = LocalDateTime.now(zone);

        assertThat(RetentionStore.idBound(now.plusSeconds(2), zone)).isGreaterThan(minted);
        assertThat(RetentionStore.idBound(now.minusSeconds(2), zone)).isLessThan(minted);
    }

    private static final class MovingClock extends Clock {
        private Instant now;

        private MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
