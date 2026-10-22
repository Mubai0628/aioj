package com.aioj.next.problem.domain;

import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.contract.problem.TutorProblemResponse;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.aioj.next.contract.ai.AiRecommendationProfileSignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;

@ExtendWith(MockitoExtension.class)
class TutorRecommendationServiceTest {
    @Mock
    private ProblemMapper problemMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private ProblemCatalog problemCatalog;
    @Mock
    private AiProfileRecommendationClient profileRecommendationClient;

    private TutorRecommendationService service;

    @BeforeEach
    void setUp() {
        when(problemCatalog.tagsOf(any())).thenReturn(List.of());
        when(profileRecommendationClient.fetch(any(), anyInt()))
                .thenReturn(List.of());

        service = new TutorRecommendationService(
                problemMapper,
                submissionMapper,
                problemCatalog,
                profileRecommendationClient
        );
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityPrincipal(42L, "student", Set.of(Role.STUDENT)), "n/a"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsOnlyCurrentUsersPracticeHistoryAndExcludesAcceptedProblems() {
        ProblemEntity unseen = problem(1L, "Unseen");
        ProblemEntity solved = problem(2L, "Solved");
        SubmissionEntity ownAccepted = submission(2L, 42L, SubmissionStatus.ACCEPTED);
        SubmissionEntity otherFailed = submission(1L, 99L, SubmissionStatus.WRONG_ANSWER);
        when(submissionMapper.selectList(any())).thenReturn(List.of(ownAccepted));
        when(problemMapper.selectList(any())).thenReturn(List.of(unseen, solved));
        when(problemCatalog.toTutorResponse(unseen)).thenReturn(tutorProblem(1L));

        var result = service.recommend(10);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).problem().problemId());
    }

    @Test
    void failedProblemIsRecommendedWithRetryReason() {
        ProblemEntity failed = problem(3L, "Retry");
        when(submissionMapper.selectList(any())).thenReturn(List.of(submission(3L, 42L, SubmissionStatus.WRONG_ANSWER)));
        when(problemMapper.selectList(any())).thenReturn(List.of(failed));
        when(problemCatalog.toTutorResponse(failed)).thenReturn(tutorProblem(3L));

        var result = service.recommend(1);

        assertEquals(1, result.size());
        assertEquals("曾提交但尚未通过，适合针对性复习", result.get(0).reason());
    }

    @Test
    void prioritizesProblemsSharingTagsWithFailureHistory() {
        ProblemEntity failed = problem(3L, "Failed BFS");
        failed.setTags("[\"BFS\"]");

        ProblemEntity related = problem(4L, "Related BFS");
        related.setTags("[\"BFS\"]");

        ProblemEntity unrelated = problem(5L, "Unrelated DP");
        unrelated.setTags("[\"DP\"]");

        when(submissionMapper.selectList(any()))
                .thenReturn(List.of(submission(3L, 42L, SubmissionStatus.WRONG_ANSWER)));

        when(problemMapper.selectList(any()))
                .thenReturn(List.of(failed, related, unrelated));

        when(problemCatalog.tagsOf(failed)).thenReturn(List.of("BFS"));
        when(problemCatalog.tagsOf(related)).thenReturn(List.of("BFS"));
        when(problemCatalog.tagsOf(unrelated)).thenReturn(List.of("DP"));
        when(problemCatalog.toTutorResponse(related)).thenReturn(tutorProblem(4L));

        var result = service.recommend(1);

        assertEquals(1, result.size());
        assertEquals(4L, result.get(0).problem().problemId());
    }

    @Test
    void activeWeakProfilePrioritizesMatchingTag() {
        ProblemEntity unrelated = problem(6L, "Unrelated");
        ProblemEntity prefixSum = problem(7L, "Prefix Sum");

        when(submissionMapper.selectList(any()))
                .thenReturn(List.of());

        when(problemMapper.selectList(any()))
                .thenReturn(List.of(unrelated, prefixSum));

        when(problemCatalog.tagsOf(unrelated))
                .thenReturn(List.of("图论"));

        when(problemCatalog.tagsOf(prefixSum))
                .thenReturn(List.of("前缀和"));

        when(profileRecommendationClient.fetch(any(), anyInt()))
                .thenReturn(List.of(
                        new AiRecommendationProfileSignal(
                                "前缀和",
                                "weakness",
                                "NEGATIVE",
                                1.0,
                                2
                        )
                ));

        when(problemCatalog.toTutorResponse(prefixSum))
                .thenReturn(tutorProblem(7L));

        var result = service.recommend(1);

        assertEquals(1, result.size());
        assertEquals(7L, result.get(0).problem().problemId());
        assertTrue(result.get(0).reason().contains("前缀和"));
    }

    @Test
        void errorProfileMatchesChineseKnowledgeTag() {
        ProblemEntity binarySearch = problem(8L, "Binary Search");

        when(submissionMapper.selectList(any()))
                .thenReturn(List.of());

        when(problemMapper.selectList(any()))
                .thenReturn(List.of(binarySearch));

        when(problemCatalog.tagsOf(binarySearch))
                .thenReturn(List.of("二分查找"));

        when(profileRecommendationClient.fetch(any(), anyInt()))
                .thenReturn(List.of(
                        new AiRecommendationProfileSignal(
                                "wrong_answer_binary_search",
                                "weakness",
                                "NEGATIVE",
                                0.8,
                                2
                        )
                ));

        when(problemCatalog.toTutorResponse(binarySearch))
                .thenReturn(tutorProblem(8L));

        var result = service.recommend(1);

        assertEquals(1, result.size());
        assertTrue(result.get(0).reason().contains("根据你的学习画像"));
        assertTrue(result.get(0).reason().contains("二分查找"));
        }

        @Test
        void errorProfileMatchesDpAlias() {
        ProblemEntity dynamicProgramming = problem(9L, "DP Problem");

        when(submissionMapper.selectList(any()))
                .thenReturn(List.of());

        when(problemMapper.selectList(any()))
                .thenReturn(List.of(dynamicProgramming));

        when(problemCatalog.tagsOf(dynamicProgramming))
                .thenReturn(List.of("DP"));

        when(profileRecommendationClient.fetch(any(), anyInt()))
                .thenReturn(List.of(
                        new AiRecommendationProfileSignal(
                                "wrong_answer_dynamic_programming",
                                "weakness",
                                "NEGATIVE",
                                0.8,
                                2
                        )
                ));

        when(problemCatalog.toTutorResponse(dynamicProgramming))
                .thenReturn(tutorProblem(9L));

        var result = service.recommend(1);

        assertEquals(1, result.size());
        assertTrue(result.get(0).reason().contains("根据你的学习画像"));
        assertTrue(result.get(0).reason().contains("DP"));
        }

        @Test
        void genericErrorProfileDoesNotMatchAsKnowledgeTopic() {
        ProblemEntity implementation = problem(10L, "Basic Implementation");

        when(submissionMapper.selectList(any()))
                .thenReturn(List.of());

        when(problemMapper.selectList(any()))
                .thenReturn(List.of(implementation));

        when(problemCatalog.tagsOf(implementation))
                .thenReturn(List.of("基础实现"));

        when(profileRecommendationClient.fetch(any(), anyInt()))
                .thenReturn(List.of(
                        new AiRecommendationProfileSignal(
                                "wrong_answer_basic_implementation",
                                "weakness",
                                "NEGATIVE",
                                0.8,
                                2
                        )
                ));

        when(problemCatalog.toTutorResponse(implementation))
                .thenReturn(tutorProblem(10L));

        var result = service.recommend(1);

        assertEquals(1, result.size());
        assertTrue(!result.get(0).reason().contains("根据你的学习画像"));
        }

        @Test
        void pureCanonicalProfileMatchesUnknownTopic() {
        ProblemEntity segmentTree = problem(11L, "Segment Tree");

        when(submissionMapper.selectList(any()))
                .thenReturn(List.of());

        when(problemMapper.selectList(any()))
                .thenReturn(List.of(segmentTree));

        when(problemCatalog.tagsOf(segmentTree))
                .thenReturn(List.of("segment_tree"));

        when(profileRecommendationClient.fetch(any(), anyInt()))
                .thenReturn(List.of(
                        new AiRecommendationProfileSignal(
                                "segment_tree",
                                "weakness",
                                "NEGATIVE",
                                0.8,
                                2
                        )
                ));

        when(problemCatalog.toTutorResponse(segmentTree))
                .thenReturn(tutorProblem(11L));

        var result = service.recommend(1);

        assertEquals(1, result.size());
        assertEquals(11L, result.get(0).problem().problemId());
        assertTrue(result.get(0).reason().contains("segment_tree"));
        }

        @Test
        void errorProfileMatchesInversionCount() {
        ProblemEntity inversion = problem(12L, "Inversion Count");

        when(submissionMapper.selectList(any()))
                .thenReturn(List.of());

        when(problemMapper.selectList(any()))
                .thenReturn(List.of(inversion));

        when(problemCatalog.tagsOf(inversion))
                .thenReturn(List.of("inversion_count"));

        when(profileRecommendationClient.fetch(any(), anyInt()))
                .thenReturn(List.of(
                        new AiRecommendationProfileSignal(
                                "wrong_answer_inversion_count",
                                "weakness",
                                "NEGATIVE",
                                0.8,
                                2
                        )
                ));

        when(problemCatalog.toTutorResponse(inversion))
                .thenReturn(tutorProblem(12L));

        var result = service.recommend(1);

        assertEquals(1, result.size());
        assertEquals(12L, result.get(0).problem().problemId());
        assertTrue(result.get(0).reason().contains("inversion_count"));
        }

        @Test
        void errorProfileMatchesTspStateCompression() {
        ProblemEntity tsp = problem(13L, "TSP State Compression");

        when(submissionMapper.selectList(any()))
                .thenReturn(List.of());

        when(problemMapper.selectList(any()))
                .thenReturn(List.of(tsp));

        when(problemCatalog.tagsOf(tsp))
                .thenReturn(List.of("tsp_state_compression"));

        when(profileRecommendationClient.fetch(any(), anyInt()))
                .thenReturn(List.of(
                        new AiRecommendationProfileSignal(
                                "wrong_answer_tsp_state_compression",
                                "weakness",
                                "NEGATIVE",
                                0.8,
                                2
                        )
                ));

        when(problemCatalog.toTutorResponse(tsp))
                .thenReturn(tutorProblem(13L));

        var result = service.recommend(1);

        assertEquals(1, result.size());
        assertEquals(13L, result.get(0).problem().problemId());
        assertTrue(result.get(0).reason().contains("tsp_state_compression"));
        }

    @Test
    void coldStartUsesDifficultyGradient() {
        ProblemEntity easy1 = problem(1L, "Easy 1");
        easy1.setDifficulty(Difficulty.EASY);

        ProblemEntity medium1 = problem(2L, "Medium 1");
        medium1.setDifficulty(Difficulty.MEDIUM);

        ProblemEntity easy2 = problem(3L, "Easy 2");
        easy2.setDifficulty(Difficulty.EASY);

        ProblemEntity medium2 = problem(4L, "Medium 2");
        medium2.setDifficulty(Difficulty.MEDIUM);

        ProblemEntity hard = problem(5L, "Hard");
        hard.setDifficulty(Difficulty.HARD);

        ProblemEntity challenge = problem(6L, "Challenge");
        challenge.setDifficulty(Difficulty.CHALLENGE);

        when(submissionMapper.selectList(any())).thenReturn(List.of());
        when(problemMapper.selectList(any()))
                .thenReturn(List.of(easy1, medium1, easy2, medium2, hard, challenge));

        when(problemCatalog.toTutorResponse(any())).thenAnswer(invocation ->
                tutorProblem((ProblemEntity) invocation.getArgument(0)));

        var result = service.recommend(6);

        assertEquals(
                List.of(
                        Difficulty.EASY,
                        Difficulty.MEDIUM,
                        Difficulty.EASY,
                        Difficulty.MEDIUM,
                        Difficulty.HARD,
                        Difficulty.CHALLENGE
                ),
                result.stream()
                        .map(item -> item.problem().difficulty())
                        .toList()
        );
    }

    @Test
        void binarySearchProfileMatchesLowerBoundTag() {
        ProblemEntity lowerBound = problem(14L, "Lower Bound");

        when(submissionMapper.selectList(any()))
                .thenReturn(List.of());

        when(problemMapper.selectList(any()))
                .thenReturn(List.of(lowerBound));

        when(problemCatalog.tagsOf(lowerBound))
                .thenReturn(List.of("lower_bound"));

        when(profileRecommendationClient.fetch(any(), anyInt()))
                .thenReturn(List.of(
                        new AiRecommendationProfileSignal(
                                "wrong_answer_binary_search",
                                "weakness",
                                "NEGATIVE",
                                0.8,
                                2
                        )
                ));

        when(problemCatalog.toTutorResponse(lowerBound))
                .thenReturn(tutorProblem(14L));

        var result = service.recommend(1);

        assertEquals(1, result.size());
        assertEquals(14L, result.get(0).problem().problemId());
        assertTrue(result.get(0).reason().contains("lower_bound"));
        }

    private TutorProblemResponse tutorProblem(ProblemEntity problem) {
        return new TutorProblemResponse(
                problem.getId(),
                "v1",
                null,
                "http://localhost:5175/problems/" + problem.getId(),
                problem.getTitle(),
                problem.getDifficulty(),
                "statement",
                null,
                List.of(),
                List.of(),
                1000,
                262144
        );
    }

    private ProblemEntity problem(Long id, String title) {
        ProblemEntity problem = new ProblemEntity();
        problem.setId(id);
        problem.setTitle(title);
        problem.setVisibility(ProblemVisibility.PUBLIC);
        problem.setDeleted(false);
        problem.setCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        return problem;
    }

    private SubmissionEntity submission(Long problemId, Long userId, SubmissionStatus status) {
        SubmissionEntity submission = new SubmissionEntity();
        submission.setProblemId(problemId);
        submission.setUserId(userId);
        submission.setStatus(status);
        return submission;
    }

    private TutorProblemResponse tutorProblem(Long id) {
        return new TutorProblemResponse(id, "v1", null, "http://localhost:5175/problems/" + id,
                "problem", null, "statement", null, List.of(), List.of(), 1000, 262144);
    }
}
