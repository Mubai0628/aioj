package com.aioj.next.problem.domain.search;

import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.problem.config.ProblemSearchProperties;
import com.aioj.next.problem.domain.ProblemChangedEvent;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.ProblemSearchIndexEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.ProblemSearchIndexMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProblemSearchIndexServiceTest {
    private final ProblemMapper problemMapper = mock(ProblemMapper.class);
    private final ProblemSearchIndexMapper indexMapper = mock(ProblemSearchIndexMapper.class);
    private final ProblemEmbeddingClient embeddingClient = mock(ProblemEmbeddingClient.class);
    private final ProblemSearchProperties properties = new ProblemSearchProperties();
    private ProblemSearchIndexService service;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ProblemEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ProblemSearchIndexEntity.class);
        service = new ProblemSearchIndexService(problemMapper, indexMapper, embeddingClient, properties,
                JsonMapper.builder().build());
    }

    @Test
    void aProblemThatIsNoLongerPublicLeavesTheIndex() {
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.onProblemChanged(new ProblemChangedEvent(5L));

        verify(indexMapper).deleteById(5L);
    }

    @Test
    void aNewPublicProblemIsQueuedWithItsSearchText() {
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(problem());
        when(indexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.refresh(5L);

        ArgumentCaptor<ProblemSearchIndexEntity> row = ArgumentCaptor.forClass(ProblemSearchIndexEntity.class);
        verify(indexMapper).insert(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(ProblemSearchIndexService.PENDING);
        assertThat(row.getValue().getAttempts()).isZero();
        assertThat(row.getValue().getSearchText()).startsWith("标题：迷宫最短路\n难度：MEDIUM\n标签：BFS");
        assertThat(row.getValue().getContentHash()).isEqualTo(SearchVectors.sha256(row.getValue().getSearchText()));
    }

    @Test
    void anUnchangedTextIsLeftAlone() {
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(problem());
        ProblemSearchIndexEntity current = new ProblemSearchIndexEntity();
        current.setProblemId(5L);
        current.setContentHash(SearchVectors.sha256(SearchVectors.searchText("迷宫最短路", Difficulty.MEDIUM,
                List.of("BFS"), "从起点走到终点", null, properties.getMaxInputChars())));
        when(indexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(current);

        service.refresh(5L);

        verify(indexMapper, never()).insert(any(ProblemSearchIndexEntity.class));
        verify(indexMapper, never()).update(any(), any());
    }

    @Test
    void aRefreshFailureNeverBreaksTheCommittedChange() {
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.onProblemChanged(new ProblemChangedEvent(5L))).doesNotThrowAnyException();
    }

    @Test
    void anEmbeddedRowBecomesReadyForTheTextItWasMadeFrom() {
        when(indexMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(due(0)));
        when(indexMapper.claim(eq(5L), eq("hash-5"), any(), any())).thenReturn(1);
        when(embeddingClient.embed("text-5", "PROBLEM_INDEX")).thenReturn(new ProblemEmbeddingClient.Result(
                new ProblemEmbeddingClient.Embedding(new float[]{0.6f, 0.8f}, "embed-v4"), null));

        service.embedDue();

        LambdaUpdateWrapper<ProblemSearchIndexEntity> update = capturedUpdates().get(0);
        assertThat(update.getSqlSegment()).contains("content_hash");
        assertThat(update.getParamNameValuePairs().values()).contains("READY", "embed-v4", 2, "hash-5");
        // The first model seen re-queues rows embedded by any other model.
        verify(indexMapper).requeueOtherModels(eq("embed-v4"), eq(2), any());
    }

    @Test
    void aFailedAttemptBacksOffAndTheLastOneStopsRetrying() {
        properties.setMaxAttempts(3);
        when(indexMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(due(0)), List.of(due(2)));
        when(indexMapper.claim(eq(5L), eq("hash-5"), any(), any())).thenReturn(1);
        when(embeddingClient.embed(anyString(), anyString())).thenReturn(new ProblemEmbeddingClient.Result(null, "HTTP 401"));

        service.embedDue();
        service.embedDue();

        List<LambdaUpdateWrapper<ProblemSearchIndexEntity>> updates = capturedUpdates();
        assertThat(updates.get(0).getParamNameValuePairs().values()).contains("FAILED", 1, "HTTP 401");
        // The next attempt is scheduled in the future; after the last one there is none.
        assertThat(updates.get(0).getParamNameValuePairs().values()).anyMatch(value -> value instanceof Instant
                && ((Instant) value).isAfter(Instant.now()));
        assertThat(updates.get(1).getParamNameValuePairs().values()).contains("FAILED", 3);
        assertThat(updates.get(1).getParamNameValuePairs().values()).noneMatch(value -> value instanceof Instant
                && ((Instant) value).isAfter(Instant.now()));
        verify(indexMapper, never()).requeueOtherModels(anyString(), anyInt(), any());
    }

    @Test
    void aRowClaimedByAnotherInstanceIsSkipped() {
        when(indexMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(due(0)));
        when(indexMapper.claim(eq(5L), eq("hash-5"), any(), any())).thenReturn(0);

        service.embedDue();

        verify(embeddingClient, never()).embed(anyString(), anyString());
    }

    @Test
    void retriesDoubleUpToSixHours() {
        assertThat(ProblemSearchIndexService.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(ProblemSearchIndexService.backoff(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(ProblemSearchIndexService.backoff(12)).isEqualTo(Duration.ofHours(6));
    }

    @SuppressWarnings("unchecked")
    private List<LambdaUpdateWrapper<ProblemSearchIndexEntity>> capturedUpdates() {
        ArgumentCaptor<LambdaUpdateWrapper<ProblemSearchIndexEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(indexMapper, org.mockito.Mockito.atLeastOnce()).update(isNull(), captor.capture());
        return captor.getAllValues();
    }

    private static ProblemSearchIndexEntity due(int attempts) {
        ProblemSearchIndexEntity row = new ProblemSearchIndexEntity();
        row.setProblemId(5L);
        row.setSearchText("text-5");
        row.setContentHash("hash-5");
        row.setAttempts(attempts);
        return row;
    }

    private static ProblemEntity problem() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId(5L);
        problem.setTitle("迷宫最短路");
        problem.setDifficulty(Difficulty.MEDIUM);
        problem.setTags("[\"BFS\"]");
        problem.setStatement("从起点走到终点");
        return problem;
    }
}
