package com.aioj.next.problem.domain.recommendation;

import com.aioj.next.contract.problem.ProblemRecommendationReason;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProblemRecommendationCacheTest {
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ProblemRecommendationCache cache = new ProblemRecommendationCache(redis, JsonMapper.builder().build());

    @Test
    void rankingRoundTripsThroughRedisWithATtl() {
        when(redis.opsForValue()).thenReturn(values);
        var ranking = new ProblemRecommendationCache.Ranking(List.of(
                new ProblemRecommendationCache.Entry(1001L, 120.5, ProblemRecommendationReason.WEAK_TAG, Map.of("tag", "二分"))),
                3, false);

        cache.put(91L, ranking);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("aioj:problem-recommendations:v1:91"), json.capture(), eq(ProblemRecommendationCache.TTL));
        when(values.get("aioj:problem-recommendations:v1:91")).thenReturn(json.getValue());

        assertThat(cache.get(91L)).isEqualTo(ranking);
    }

    @Test
    void redisOutageMeansRankingAgainNotAnError() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get(91L)).isNull();
        assertThatCode(() -> cache.evict(91L)).doesNotThrowAnyException();
    }

    @Test
    void unreadableEntryIsIgnored() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("{not json");

        assertThat(cache.get(91L)).isNull();
    }
}
