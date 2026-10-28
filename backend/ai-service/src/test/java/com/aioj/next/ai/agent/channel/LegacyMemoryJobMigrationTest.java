package com.aioj.next.ai.agent.channel;

import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.ai.persistence.entity.AiDomainEventEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.mapper.AiAsyncJobMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryJobMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegacyMemoryJobMigrationTest {
    private final AiMemoryJobMapper legacy = mock(AiMemoryJobMapper.class);
    private final AiAsyncJobMapper jobs = mock(AiAsyncJobMapper.class);
    private final HarnessEventChannel channel = mock(HarnessEventChannel.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LegacyMemoryJobMigration migration = new LegacyMemoryJobMigration(legacy, jobs, channel, mapper,
            TransactionOperations.withoutTransaction(), Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void mapsBothPayloadsWithoutDependingOnLegacyEventRows() {
        AiMemoryJobEntity submission = job(11L, "AI_JUDGED_SUBMISSION_ANALYSIS", "RUNNING",
                "{\"userId\":7,\"submissionId\":501,\"problemId\":9,\"status\":\"ACCEPTED\",\"judgedAt\":\"2026-01-01T00:00:00Z\"}");
        AiMemoryJobEntity merge = job(12L, "AI_MEMORY_MERGE_REVIEW", "FAILED_RETRYABLE",
                "{\"userId\":\"7\",\"candidateId\":\"99\",\"action\":\"ACCEPT\",\"reason\":\"confirmed\"}");
        when(legacy.selectList(any(Wrapper.class))).thenReturn(List.of(submission, merge));
        when(legacy.selectOne(any(Wrapper.class))).thenReturn(submission, merge);
        when(channel.deliver(any(HarnessEvent.class), anyString(), anyString())).thenAnswer(invocation -> {
            HarnessEvent event = invocation.getArgument(0);
            AiDomainEventEntity stored = new AiDomainEventEntity();
            stored.setId(event.type().equals(ChannelEventTypes.SUBMISSION_JUDGED) ? 101L : 102L);
            return new HarnessEventChannel.Delivery(stored, false,
                    List.of(event.type().equals(ChannelEventTypes.SUBMISSION_JUDGED)
                            ? submission.getJobType() : merge.getJobType()));
        });
        when(legacy.update(isNull(), any(Wrapper.class))).thenReturn(1);

        migration.migrateUnfinished();

        ArgumentCaptor<HarnessEvent> events = ArgumentCaptor.forClass(HarnessEvent.class);
        verify(channel).deliver(events.capture(), eq("submission"), eq("501"));
        verify(channel).deliver(events.capture(), eq("AI_MEMORY_CANDIDATE"), eq("99"));
        HarnessEvent first = events.getAllValues().get(0);
        HarnessEvent second = events.getAllValues().get(1);
        assertThat(first.type()).isEqualTo(ChannelEventTypes.SUBMISSION_JUDGED);
        assertThat(first.idempotencyKey()).isEqualTo("legacy-memory-job:11");
        assertThat(first.producer()).isEqualTo("ai-service:legacy-migration");
        assertThat(first.subject().userId()).isEqualTo("7");
        assertThat(first.payload()).isEqualTo(mapper.readTree(submission.getPayloadJson()));
        assertThat(second.type()).isEqualTo(ChannelEventTypes.MEMORY_CANDIDATE_PROPOSED);
        assertThat(second.idempotencyKey()).isEqualTo("legacy-memory-job:12");
        assertThat(second.payload()).isEqualTo(mapper.readTree(merge.getPayloadJson()));
        verify(legacy, times(2)).update(isNull(), any(Wrapper.class));
    }

    @Test
    void repeatedStartFindsNoUnfinishedRows() {
        AiMemoryJobEntity merge = job(15L, "AI_MEMORY_MERGE_REVIEW", "QUEUED", "{\"userId\":7,\"candidateId\":99}");
        when(legacy.selectList(any(Wrapper.class))).thenReturn(List.of(merge), List.of());
        when(legacy.selectOne(any(Wrapper.class))).thenReturn(merge);
        AiDomainEventEntity stored = new AiDomainEventEntity();
        stored.setId(104L);
        when(channel.deliver(any(HarnessEvent.class), anyString(), anyString()))
                .thenReturn(new HarnessEventChannel.Delivery(stored, false, List.of(merge.getJobType())));
        when(legacy.update(isNull(), any(Wrapper.class))).thenReturn(1);

        migration.migrateUnfinished();
        migration.migrateUnfinished();

        verify(channel, times(1)).deliver(any(HarnessEvent.class), anyString(), anyString());
        verify(legacy, times(1)).update(isNull(), any(Wrapper.class));
    }

    @Test
    void duplicateEventRequiresTheExistingSubscriberJobBeforeFinalizing() {
        AiMemoryJobEntity merge = job(15L, "AI_MEMORY_MERGE_REVIEW", "QUEUED", "{\"userId\":7,\"candidateId\":99}");
        when(legacy.selectList(any(Wrapper.class))).thenReturn(List.of(merge));
        when(legacy.selectOne(any(Wrapper.class))).thenReturn(merge);
        AiDomainEventEntity stored = new AiDomainEventEntity();
        stored.setId(104L);
        when(channel.deliver(any(HarnessEvent.class), anyString(), anyString()))
                .thenReturn(new HarnessEventChannel.Delivery(stored, true, List.of()));
        when(jobs.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(legacy.update(isNull(), any(Wrapper.class))).thenReturn(1);

        migration.migrateUnfinished();

        verify(jobs).selectCount(any(Wrapper.class));
        verify(legacy).update(isNull(), any(Wrapper.class));
    }

    @Test
    void badPayloadIsNotPublishedOrMarkedFinished() {
        AiMemoryJobEntity bad = job(16L, "AI_MEMORY_MERGE_REVIEW", "QUEUED",
                "{\"userId\":7,\"candidateId\":99,\"eventId\":\"would-be-overwritten\"}");
        when(legacy.selectList(any(Wrapper.class))).thenReturn(List.of(bad));
        when(legacy.selectOne(any(Wrapper.class))).thenReturn(bad);

        migration.migrateUnfinished();

        verifyNoInteractions(channel, jobs);
        verify(legacy, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void unavailableSubscriberLeavesTheOldRowUnfinished() {
        AiMemoryJobEntity merge = job(17L, "AI_MEMORY_MERGE_REVIEW", "QUEUED", "{\"userId\":7,\"candidateId\":99}");
        when(legacy.selectList(any(Wrapper.class))).thenReturn(List.of(merge));
        when(legacy.selectOne(any(Wrapper.class))).thenReturn(merge);
        AiDomainEventEntity stored = new AiDomainEventEntity();
        stored.setId(105L);
        when(channel.deliver(any(HarnessEvent.class), anyString(), anyString()))
                .thenReturn(new HarnessEventChannel.Delivery(stored, false, List.of()));

        migration.migrateUnfinished();

        verify(legacy, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void noLegacyJobsMeansNoWrites() {
        when(legacy.selectList(any(Wrapper.class))).thenReturn(List.of());
        migration.migrateUnfinished();
        verifyNoInteractions(channel, jobs);
        verify(legacy, never()).update(isNull(), any(Wrapper.class));
    }

    private static AiMemoryJobEntity job(long id, String type, String status, String payload) {
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setId(id);
        job.setJobType(type);
        job.setStatus(status);
        job.setPayloadJson(payload);
        return job;
    }
}
