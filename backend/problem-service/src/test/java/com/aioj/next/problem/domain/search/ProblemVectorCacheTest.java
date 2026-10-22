package com.aioj.next.problem.domain.search;

import com.aioj.next.problem.config.ProblemSearchProperties;
import com.aioj.next.problem.persistence.entity.ProblemSearchIndexEntity;
import com.aioj.next.problem.persistence.mapper.ProblemSearchIndexMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProblemVectorCacheTest {
    private final ProblemSearchIndexMapper indexMapper = mock(ProblemSearchIndexMapper.class);
    private final ProblemSearchProperties properties = new ProblemSearchProperties();

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProblemSearchIndexEntity.class);
    }

    @Test
    void onlyRowsWhoseContentChangedAreReadAgain() {
        properties.setVectorRefreshInterval(Duration.ZERO);
        ProblemVectorCache cache = new ProblemVectorCache(indexMapper, properties);
        when(indexMapper.readyHeads()).thenReturn(List.of(head(1, "h1"), head(2, "h2")), List.of(head(1, "h1"), head(2, "h2b")));
        when(indexMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(row(1, "h1", 1, 0), row(2, "h2", 0, 1)), List.of(row(2, "h2b", 3, 4)));

        assertThat(cache.vectors()).containsOnlyKeys(1L, 2L);
        var refreshed = cache.vectors();

        assertThat(refreshed.get(1L).values()).containsExactly(1f, 0f);
        assertThat(refreshed.get(2L).values()).containsExactly(3f, 4f);
        assertThat(refreshed.get(2L).norm()).isEqualTo(5.0);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ProblemSearchIndexEntity>> loads = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(indexMapper, times(2)).selectList(loads.capture());
        loads.getAllValues().get(1).getSqlSegment();
        assertThat(loads.getAllValues().get(1).getParamNameValuePairs().values()).contains(2L).doesNotContain(1L);
    }

    @Test
    void aFreshSnapshotIsServedWithoutTouchingTheDatabase() {
        ProblemVectorCache cache = new ProblemVectorCache(indexMapper, properties);
        when(indexMapper.readyHeads()).thenReturn(List.of(head(1, "h1")));
        when(indexMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row(1, "h1", 1, 0)));

        cache.vectors();
        cache.vectors();

        verify(indexMapper, times(1)).readyHeads();
    }

    private static ProblemSearchIndexEntity head(long problemId, String hash) {
        ProblemSearchIndexEntity head = new ProblemSearchIndexEntity();
        head.setProblemId(problemId);
        head.setContentHash(hash);
        head.setEmbeddingModel("m");
        return head;
    }

    private static ProblemSearchIndexEntity row(long problemId, String hash, float... vector) {
        ProblemSearchIndexEntity row = head(problemId, hash);
        row.setEmbedding(SearchVectors.toBytes(vector));
        return row;
    }
}
