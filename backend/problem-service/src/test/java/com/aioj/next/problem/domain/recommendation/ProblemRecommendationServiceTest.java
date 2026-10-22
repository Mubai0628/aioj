package com.aioj.next.problem.domain.recommendation;

import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.AiRecommendationProfileSignal;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemRecommendationReason;
import com.aioj.next.contract.problem.ProblemRecommendationResponse;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.mapper.PracticeProblemHistory;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProblemRecommendationServiceTest {
    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    private final ProblemMapper problemMapper = mock(ProblemMapper.class);
    private final SubmissionMapper submissionMapper = mock(SubmissionMapper.class);
    private final AiRecommendationSignalClient signalClient = mock(AiRecommendationSignalClient.class);
    private final ProblemRecommendationCache cache = mock(ProblemRecommendationCache.class);
    private ProblemRecommendationService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProblemEntity.class);
        service = new ProblemRecommendationService(problemMapper, submissionMapper, signalClient, new KnowledgeTopics(),
                cache, JsonMapper.builder().build());
        SecurityPrincipal principal = new SecurityPrincipal(91L, "student91", Set.of(Role.STUDENT));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a",
                Set.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        when(signalClient.weaknesses(eq(91L), anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void solvedProblemsAreLeftOutAndAttemptsComeWithTheirCount() {
        catalog(problem(1, Difficulty.EASY, "[\"数组\"]", 1),
                problem(2, Difficulty.MEDIUM, "[\"贪心\"]", 2),
                problem(3, Difficulty.EASY, "[\"模拟\"]", 3));
        history(attempt(1, 1, 0, true), attempt(2, 3, 2, false));
        when(submissionMapper.countSolvedPracticeProblems(91L)).thenReturn(1L);

        var response = service.recommend(null);

        assertThat(response.items()).extracting(ProblemRecommendationResponse::problemId).containsExactly(3L, 2L);
        assertThat(response.items().get(0).reason()).isEqualTo(ProblemRecommendationReason.NEW_PROBLEM);
        // 70 - 3 attempts x 3 + 2 failures x 2, plus its own failing topic: min(2, 5) x 10.
        assertThat(response.items().get(1).score()).isEqualTo(85.0);
        assertThat(response.items().get(1).reason()).isEqualTo(ProblemRecommendationReason.UNSOLVED_ATTEMPT);
        assertThat(response.items().get(1).reasonParams()).containsEntry("attempts", "3");
        assertThat(response.solvedProblems()).isEqualTo(1L);
        assertThat(response.coldStart()).isFalse();
        verify(cache).put(eq(91L), any());
    }

    @Test
    void aTopicTheStudentKeepsFailingOutranksAnUnrelatedNewProblem() {
        catalog(problem(2, Difficulty.MEDIUM, "[\"二分\"]", 1),
                problem(4, Difficulty.MEDIUM, "[\"binary_search\"]", 2),
                problem(3, Difficulty.EASY, "[\"模拟\"]", 3));
        history(attempt(2, 2, 2, false));

        var response = service.recommend(10);

        assertThat(response.items()).extracting(ProblemRecommendationResponse::problemId).containsExactly(4L, 3L, 2L);
        assertThat(response.items().get(0).score()).isEqualTo(120.0);
        assertThat(response.items().get(0).reason()).isEqualTo(ProblemRecommendationReason.WEAK_TAG);
        assertThat(response.items().get(0).reasonParams()).containsEntry("tag", "binary_search");
    }

    @Test
    void aConfirmedWeaknessBoostsMatchingProblemsAndNamesTheirTag() {
        catalog(problem(5, Difficulty.MEDIUM, "[\"数组\", \"二分查找\"]", 1),
                problem(6, Difficulty.EASY, "[\"模拟\"]", 2),
                problem(9, Difficulty.EASY, "[\"排序\"]", 3));
        history(attempt(9, 1, 0, true));
        when(signalClient.weaknesses(eq(91L), anyInt())).thenReturn(List.of(
                new AiRecommendationProfileSignal("wrong_answer_lower_bound", "下界", 0.8, 3),
                new AiRecommendationProfileSignal("wrong_answer_debugging", "调试", 0.9, 5)));

        var response = service.recommend(10);

        assertThat(response.items()).extracting(ProblemRecommendationResponse::problemId).containsExactly(5L, 6L);
        // 100 + confidence 0.8 x 3 evidence x 20; the generic debugging profile matches nothing.
        assertThat(response.items().get(0).score()).isEqualTo(148.0);
        assertThat(response.items().get(0).reason()).isEqualTo(ProblemRecommendationReason.PROFILE_WEAKNESS);
        assertThat(response.items().get(0).reasonParams()).containsEntry("tag", "二分查找");
        assertThat(response.items().get(1).reason()).isEqualTo(ProblemRecommendationReason.NEW_PROBLEM);
    }

    @Test
    void aStudentWithNoPracticeGetsAnEasyToHardStarterSet() {
        catalog(problem(11, Difficulty.EASY, "[]", 7),
                problem(12, Difficulty.EASY, "[]", 6),
                problem(21, Difficulty.MEDIUM, "[]", 5),
                problem(22, Difficulty.MEDIUM, "[]", 4),
                problem(31, Difficulty.HARD, "[]", 3),
                problem(41, Difficulty.CHALLENGE, "[]", 2),
                problem(13, Difficulty.EASY, "[]", 1));
        history();

        var response = service.recommend(10);

        assertThat(response.coldStart()).isTrue();
        assertThat(response.items()).extracting(ProblemRecommendationResponse::problemId)
                .containsExactly(11L, 21L, 12L, 22L, 31L, 41L, 13L);
        assertThat(response.items()).extracting(ProblemRecommendationResponse::reason)
                .containsOnly(ProblemRecommendationReason.COLD_START);
    }

    @Test
    void aCachedRankingIsServedWithFreshVisibility() {
        when(cache.get(91L)).thenReturn(new ProblemRecommendationCache.Ranking(List.of(
                entry(1), entry(2), entry(3)), 4, false));
        when(problemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(problem(1, Difficulty.EASY, "[\"数组\"]", 1), problem(3, Difficulty.HARD, "[]", 3)));

        var response = service.recommend(2);

        assertThat(response.items()).extracting(ProblemRecommendationResponse::problemId).containsExactly(1L, 3L);
        assertThat(response.items().get(0).tags()).containsExactly("数组");
        assertThat(response.solvedProblems()).isEqualTo(4L);
        verifyNoInteractions(submissionMapper, signalClient);
        verify(cache, never()).put(any(), any());
    }

    @Test
    void theLimitDefaultsToSixAndIsCappedAtFifty() {
        List<ProblemEntity> problems = new ArrayList<>();
        for (int id = 1; id <= 60; id++) {
            problems.add(problem(id, Difficulty.EASY, "[]", id));
        }
        catalog(problems.toArray(ProblemEntity[]::new));
        history(attempt(1, 1, 0, true));

        assertThat(service.recommend(null).items()).hasSize(6);
        assertThat(service.recommend(500).items()).hasSize(50);
    }

    private void catalog(ProblemEntity... problems) {
        when(problemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(problems));
    }

    private void history(PracticeProblemHistory... rows) {
        when(submissionMapper.practiceHistory(eq(91L), anyInt())).thenReturn(List.of(rows));
    }

    private static ProblemRecommendationCache.Entry entry(long problemId) {
        return new ProblemRecommendationCache.Entry(problemId, 100.0, ProblemRecommendationReason.NEW_PROBLEM, Map.of());
    }

    private static PracticeProblemHistory attempt(long problemId, int attempts, int failures, boolean accepted) {
        PracticeProblemHistory row = new PracticeProblemHistory();
        row.setProblemId(problemId);
        row.setAttempts(attempts);
        row.setFailures(failures);
        row.setAccepted(accepted);
        return row;
    }

    private static ProblemEntity problem(long id, Difficulty difficulty, String tags, int ageRank) {
        ProblemEntity problem = new ProblemEntity();
        problem.setId(id);
        problem.setTitle("Problem " + id);
        problem.setDifficulty(difficulty);
        problem.setTags(tags);
        // A higher rank is newer; equal scores rank newer problems first.
        problem.setCreatedAt(BASE.plusSeconds(ageRank * 60L));
        return problem;
    }
}
