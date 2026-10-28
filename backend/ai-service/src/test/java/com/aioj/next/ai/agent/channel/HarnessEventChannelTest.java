package com.aioj.next.ai.agent.channel;

import com.aioj.next.agent.harness.channel.api.EventSubject;
import com.aioj.next.agent.harness.channel.api.EventSubscriber;
import com.aioj.next.agent.harness.channel.api.HarnessEvent;
import com.aioj.next.agent.harness.channel.api.PublishedEvent;
import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobHandler;
import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.ai.persistence.entity.AiDomainEventEntity;
import com.aioj.next.ai.persistence.mapper.AiDomainEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarnessEventChannelTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiDomainEventMapper eventMapper = mock(AiDomainEventMapper.class);
    private final AgentAsyncJobService jobService = mock(AgentAsyncJobService.class);

    @Test
    void publishStoresTheEventOnceAndGivesEachSubscriberItsOwnJob() {
        storesWithId(41L);
        HarnessEventChannel channel = channel(List.of(handler("TURN_CURATE", "TURN_COMPLETED"),
                handler("EMBED_DIGEST", "DIGEST_READY"), handler("PROFILE_AGGREGATE", "TURN_COMPLETED")));

        PublishedEvent published = channel.publish(turnCompleted("t-1"));

        assertThat(published.eventId()).isEqualTo("41");
        assertThat(published.duplicate()).isFalse();
        assertThat(published.consumers()).containsExactly("TURN_CURATE", "PROFILE_AGGREGATE");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(jobService).enqueue(eq("TURN_CURATE"), eq("41:TURN_CURATE"), payload.capture(), eq(5));
        verify(jobService).enqueue(eq("PROFILE_AGGREGATE"), eq("41:PROFILE_AGGREGATE"), anyString(), eq(5));
        verify(jobService, never()).enqueue(eq("EMBED_DIGEST"), anyString(), anyString(), anyInt());
        assertThat(objectMapper.readTree(payload.getValue()).get("turnId").asString()).isEqualTo("t-1");
        assertThat(objectMapper.readTree(payload.getValue()).get("eventId").asString()).isEqualTo("41");
        assertThat(objectMapper.readTree(payload.getValue()).get("eventType").asString()).isEqualTo("TURN_COMPLETED");
        assertThat(objectMapper.readTree(payload.getValue()).get("traceId").asString()).isEqualTo("trace-1");
    }

    @Test
    void theStoredRowCarriesTheEnvelope() {
        storesWithId(41L);
        HarnessEventChannel channel = channel(List.of());

        channel.publish(turnCompleted("t-1"));

        ArgumentCaptor<AiDomainEventEntity> row = ArgumentCaptor.forClass(AiDomainEventEntity.class);
        verify(eventMapper).insert(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo("TURN_COMPLETED");
        assertThat(row.getValue().getSchemaVersion()).isEqualTo(1);
        assertThat(row.getValue().getProducer()).isEqualTo("ai-service:tutor");
        assertThat(row.getValue().getUserId()).isEqualTo(7L);
        assertThat(row.getValue().getSourceType()).isEqualTo("turn");
        assertThat(row.getValue().getSourceId()).isEqualTo("t-1");
        assertThat(row.getValue().getIdempotencyKey()).isEqualTo("turn-completed:t-1");
        assertThat(row.getValue().getSensitivity()).isEqualTo("USER_PRIVATE_SAFE");
        assertThat(row.getValue().getTraceId()).isEqualTo("trace-1");
    }

    @Test
    void aDuplicatePublishDeliversNothingNew() {
        AiDomainEventEntity earlier = new AiDomainEventEntity();
        earlier.setId(40L);
        when(eventMapper.selectOne(any(QueryWrapper.class))).thenReturn(earlier);
        HarnessEventChannel channel = channel(List.of(handler("TURN_CURATE", "TURN_COMPLETED")));

        PublishedEvent published = channel.publish(turnCompleted("t-1"));

        assertThat(published.eventId()).isEqualTo("40");
        assertThat(published.duplicate()).isTrue();
        assertThat(published.consumers()).isEmpty();
        verify(eventMapper, never()).insert(any(AiDomainEventEntity.class));
        verify(jobService, never()).enqueue(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void aRaceOnTheIdempotencyKeyResolvesToTheWinner() {
        AiDomainEventEntity winner = new AiDomainEventEntity();
        winner.setId(39L);
        when(eventMapper.selectOne(any(QueryWrapper.class))).thenReturn(null, winner);
        when(eventMapper.insert(any(AiDomainEventEntity.class))).thenThrow(new DuplicateKeyException("uk_idempotency"));
        HarnessEventChannel channel = channel(List.of(handler("TURN_CURATE", "TURN_COMPLETED")));

        PublishedEvent published = channel.publish(turnCompleted("t-1"));

        assertThat(published.eventId()).isEqualTo("39");
        assertThat(published.duplicate()).isTrue();
        verify(jobService, never()).enqueue(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void theEventAndItsJobsShareOneTransaction() {
        storesWithId(41L);
        TransactionOperations transactions = mock(TransactionOperations.class);
        when(transactions.execute(any())).thenAnswer(invocation -> invocation
                .<org.springframework.transaction.support.TransactionCallback<Object>>getArgument(0)
                .doInTransaction(null));
        HarnessEventChannel channel = new HarnessEventChannel(eventMapper, jobService, objectMapper, transactions,
                new AiProperties(), provider(List.of(handler("TURN_CURATE", "TURN_COMPLETED"))), provider(List.of()));
        when(jobService.enqueue(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("job table down"));

        assertThatThrownBy(() -> channel.publish(turnCompleted("t-1"))).isInstanceOf(IllegalStateException.class);
        verify(transactions, times(1)).execute(any());
    }

    @Test
    void eventsNeedTheirUser() {
        HarnessEventChannel channel = channel(List.of());

        assertThatThrownBy(() -> channel.publish(HarnessEvent.builder("TURN_COMPLETED", "k-1", "ai-service:tutor")
                .subject(EventSubject.NONE).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void harnessSubscribersAreConsumersToo() {
        EventSubscriber subscriber = mock(EventSubscriber.class);
        when(subscriber.name()).thenReturn("audit-mirror");
        when(subscriber.eventTypes()).thenReturn(Set.of("TURN_COMPLETED"));
        HarnessEventChannel channel = new HarnessEventChannel(eventMapper, jobService, objectMapper,
                TransactionOperations.withoutTransaction(), new AiProperties(),
                provider(List.of(handler("TURN_CURATE", "TURN_COMPLETED"), handler("PLAIN_JOB"))), provider(List.of(subscriber)));

        assertThat(channel.consumersOf("TURN_COMPLETED")).containsExactly("TURN_CURATE", "audit-mirror");
        assertThat(channel.consumers()).containsExactlyInAnyOrder("TURN_CURATE", "audit-mirror");
    }

    /** Text-level checks of the additive V72 script, like V70 and V71: nullable columns only. */
    @Test
    void v72OnlyAddsNullableColumns() throws IOException {
        String sql;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V72__ai_domain_event_envelope.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        String ddl = sql.replaceAll("(?m)^\\s*--.*$", "");

        assertThat(ddl).contains("alter table ai_domain_events");
        assertThat(ddl.split("add column", -1)).hasSize(4);
        assertThat(ddl).contains("schema_version", "producer", "trace_id");
        assertThat(ddl).doesNotContain("drop ", "modify ", "change ", "rename ", "not null", "default ",
                "delete from", "insert into", "update ", "create table");
    }

    private HarnessEvent turnCompleted(String turnId) {
        return HarnessEvent.builder(ChannelEventTypes.TURN_COMPLETED, "turn-completed:" + turnId, "ai-service:tutor")
                .subject(new EventSubject("7", "c-1", turnId, null))
                .payload(objectMapper.valueToTree(Map.of("turnId", turnId, "userId", 7)))
                .traceId("trace-1")
                .build();
    }

    private void storesWithId(long id) {
        when(eventMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<AiDomainEventEntity>getArgument(0).setId(id);
            return 1;
        }).when(eventMapper).insert(any(AiDomainEventEntity.class));
    }

    private HarnessEventChannel channel(List<AgentAsyncJobHandler> handlers) {
        return new HarnessEventChannel(eventMapper, jobService, objectMapper, TransactionOperations.withoutTransaction(),
                new AiProperties(), provider(handlers), provider(List.of()));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(List<T> beans) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> beans.stream());
        when(provider.stream()).thenAnswer(invocation -> beans.stream());
        return provider;
    }

    private static AgentAsyncJobHandler handler(String jobType, String... eventTypes) {
        return new AgentAsyncJobHandler() {
            @Override
            public String jobType() {
                return jobType;
            }

            @Override
            public Set<String> eventTypes() {
                return Set.of(eventTypes);
            }

            @Override
            public void handle(AiAsyncJobEntity job) {
            }
        };
    }
}
