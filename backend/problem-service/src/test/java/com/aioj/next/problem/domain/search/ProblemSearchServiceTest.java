package com.aioj.next.problem.domain.search;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemSearchFallbackReason;
import com.aioj.next.contract.problem.ProblemSearchHit;
import com.aioj.next.contract.problem.ProblemSearchMode;
import com.aioj.next.problem.config.ProblemSearchProperties;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProblemSearchServiceTest {
    private final ProblemMapper problemMapper = mock(ProblemMapper.class);
    private final ProblemVectorCache vectorCache = mock(ProblemVectorCache.class);
    private final ProblemEmbeddingClient embeddingClient = mock(ProblemEmbeddingClient.class);
    private final ProblemSearchIndexService indexService = mock(ProblemSearchIndexService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ProblemSearchProperties properties = new ProblemSearchProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ProblemSearchService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProblemEntity.class);
        service = new ProblemSearchService(problemMapper, vectorCache, embeddingClient, indexService, properties, redis,
                JsonMapper.builder().build(), meters);
        when(redis.opsForValue()).thenReturn(values);
        SecurityPrincipal principal = new SecurityPrincipal(91L, "student91", Set.of(Role.STUDENT));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a",
                Set.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void keywordModeNeverSpendsAnEmbedding() {
        when(problemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(problem(1, "迷宫最短路", "BFS")));

        var response = service.search(" 最短路 ", "keyword", null, null);

        assertThat(response.mode()).isEqualTo(ProblemSearchMode.KEYWORD);
        assertThat(response.fallback()).isFalse();
        assertThat(response.items()).extracting(ProblemSearchHit::problemId).containsExactly(1L);
        assertThat(response.items().get(0).score()).isEqualTo(0.8);
        verifyNoInteractions(embeddingClient, vectorCache);
    }

    @Test
    void semanticModeRanksByMeaningPlusKeywordMatch() {
        when(vectorCache.vectors()).thenReturn(Map.of(
                1L, vector(1, 0),
                2L, vector(0.6f, 0.8f),
                3L, vector(0, 1)));
        when(embeddingClient.embed("最短路", "PROBLEM_QUERY")).thenReturn(embedding("m", 0, 1));
        ProblemEntity maze = problem(1, "迷宫最短路", "BFS");
        when(problemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(maze), List.of(maze, problem(2, "树的直径", "树"), problem(3, "单源最短距离", "图论")));

        var response = service.search("最短路", "semantic", null, 10);

        assertThat(response.mode()).isEqualTo(ProblemSearchMode.SEMANTIC);
        assertThat(response.fallback()).isFalse();
        // 0.75 x cosine + 0.25 x keyword: 0.75, 0.6, and 0.2 for the title match with no semantic overlap.
        assertThat(response.items()).extracting(ProblemSearchHit::problemId).containsExactly(3L, 2L, 1L);
        assertThat(response.items()).extracting(ProblemSearchHit::score).containsExactly(0.75, 0.6, 0.2);
        verify(values).set(eq(queryKey("最短路")), anyString(), eq(properties.getQueryVectorTtl()));
        verify(indexService).observeHealthyEmbedding("m", 2);
    }

    @Test
    void aCachedQueryVectorSkipsTheEmbeddingCall() {
        when(vectorCache.vectors()).thenReturn(Map.of(3L, vector(0, 1)));
        when(values.get(queryKey("最短路"))).thenReturn("{\"model\":\"m\",\"vector\":\""
                + Base64.getEncoder().encodeToString(SearchVectors.toBytes(new float[]{0, 1})) + "\"}");
        when(problemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(), List.of(problem(3, "单源最短距离", "图论")));

        var response = service.search("最短路", "semantic", null, 10);

        assertThat(response.items()).extracting(ProblemSearchHit::problemId).containsExactly(3L);
        verify(embeddingClient, never()).embed(anyString(), anyString());
    }

    @Test
    void anUnavailableEmbeddingFallsBackToKeywordAndSaysSo() {
        when(vectorCache.vectors()).thenReturn(Map.of(3L, vector(0, 1)));
        when(embeddingClient.embed(anyString(), anyString())).thenReturn(new ProblemEmbeddingClient.Result(null, "HTTP 401"));
        when(problemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(problem(1, "迷宫最短路", "BFS")));

        var response = service.search("最短路", "semantic", Difficulty.MEDIUM, 10);

        assertThat(response.mode()).isEqualTo(ProblemSearchMode.KEYWORD);
        assertThat(response.fallback()).isTrue();
        assertThat(response.fallbackReason()).isEqualTo(ProblemSearchFallbackReason.EMBEDDING_UNAVAILABLE);
        assertThat(response.items()).hasSize(1);
        assertThat(meters.counter("aioj.problem.search.requests", "mode", "semantic", "result", "EMBEDDING_UNAVAILABLE")
                .count()).isEqualTo(1.0);
    }

    @Test
    void anEmptyIndexFallsBackWithoutEmbeddingTheQuery() {
        when(vectorCache.vectors()).thenReturn(Map.of());
        when(problemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var response = service.search("最短路", "semantic", null, 10);

        assertThat(response.fallbackReason()).isEqualTo(ProblemSearchFallbackReason.INDEX_NOT_READY);
        verify(embeddingClient, never()).embed(anyString(), anyString());
    }

    @Test
    void vectorsOfAnotherModelMeanTheIndexIsStillRebuilding() {
        when(vectorCache.vectors()).thenReturn(Map.of(3L, new ProblemVectorCache.Vector(new float[]{0, 1}, 1, "old", "h")));
        when(embeddingClient.embed(anyString(), anyString())).thenReturn(embedding("new", 0, 1));
        when(problemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var response = service.search("最短路", "semantic", null, 10);

        assertThat(response.fallbackReason()).isEqualTo(ProblemSearchFallbackReason.INDEX_NOT_READY);
        verify(indexService).observeHealthyEmbedding("new", 2);
    }

    @Test
    void aUserOverTheSemanticLimitGetsKeywordResults() {
        properties.setQueryRateLimitPerMinute(1);
        when(values.increment(anyString())).thenReturn(2L);
        when(problemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var response = service.search("最短路", "semantic", null, 10);

        assertThat(response.fallbackReason()).isEqualTo(ProblemSearchFallbackReason.RATE_LIMITED);
        verifyNoInteractions(embeddingClient, vectorCache);
    }

    @Test
    void aBlankQueryIsRejected() {
        assertThatThrownBy(() -> service.search("   ", "semantic", null, null))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void keywordScoreNamesTagsNumbersAndTitles() {
        ProblemEntity problem = problem(1001, "迷宫 最短路", "BFS");

        assertThat(ProblemSearchService.keywordScore("bfs 迷宫", problem, List.of("BFS"))).isEqualTo(1.0);
        assertThat(ProblemSearchService.keywordScore("1001", problem, List.of())).isEqualTo(1.0);
        assertThat(ProblemSearchService.keywordScore("最短路", problem, List.of())).isEqualTo(0.8);
        assertThat(ProblemSearchService.keywordScore("路", problem, List.of())).isZero();
        assertThat(ProblemSearchService.keywordScore("动态规划", problem, List.of("BFS"))).isZero();
    }

    private static String queryKey(String query) {
        return "aioj:problem-search:query:v1:" + SearchVectors.sha256(query);
    }

    private static ProblemVectorCache.Vector vector(float... values) {
        return new ProblemVectorCache.Vector(values, SearchVectors.norm(values), "m", "h");
    }

    private static ProblemEmbeddingClient.Result embedding(String model, float... values) {
        return new ProblemEmbeddingClient.Result(new ProblemEmbeddingClient.Embedding(values, model), null);
    }

    private static ProblemEntity problem(long id, String title, String tag) {
        ProblemEntity problem = new ProblemEntity();
        problem.setId(id);
        problem.setTitle(title);
        problem.setDifficulty(Difficulty.MEDIUM);
        problem.setTags("[\"" + tag + "\"]");
        return problem;
    }
}
