package com.aioj.next.problem.domain;
import com.aioj.next.contract.problem.Difficulty;

import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.problem.TutorProblemResponse;
import com.aioj.next.contract.problem.TutorRecommendationResponse;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import com.aioj.next.contract.problem.Difficulty;
import java.util.HashSet;
import com.aioj.next.contract.ai.AiRecommendationProfileSignal;
import java.util.Locale;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TutorRecommendationService {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private static final Set<SubmissionStatus> KNOWLEDGE_FAILURE_STATUSES = Set.of(
        SubmissionStatus.WRONG_ANSWER,
        SubmissionStatus.RUNTIME_ERROR,
        SubmissionStatus.TIME_LIMIT_EXCEEDED,
        SubmissionStatus.MEMORY_LIMIT_EXCEEDED,
        SubmissionStatus.OUTPUT_LIMIT_EXCEEDED
    );

    private static final List<String> PROFILE_STATUS_PREFIXES = List.of(
            "wrong_answer",
            "runtime_error",
            "time_limit_exceeded",
            "memory_limit_exceeded",
            "output_limit_exceeded",
            "compile_error"
    );

    private static final Map<String, String> KNOWLEDGE_ALIASES = Map.ofEntries(
            Map.entry("数组", "array"),
            Map.entry("array", "array"),

            Map.entry("字符串", "string"),
            Map.entry("string", "string"),

            Map.entry("前缀和", "prefix_sum"),
            Map.entry("prefix_sum", "prefix_sum"),
            Map.entry("prefix_sums", "prefix_sum"),

            Map.entry("差分", "difference_array"),
            Map.entry("difference_array", "difference_array"),

            Map.entry("二分", "binary_search"),
            Map.entry("二分查找", "binary_search"),
            Map.entry("二分答案", "binary_search"),
            Map.entry("binary_search", "binary_search"),
            Map.entry("binary_search_boundary", "binary_search"),
            Map.entry("binary_search_monotonicity", "binary_search"),

            Map.entry("双指针", "two_pointers"),
            Map.entry("two_pointer", "two_pointers"),
            Map.entry("two_pointers", "two_pointers"),

            Map.entry("滑动窗口", "sliding_window"),
            Map.entry("sliding_window", "sliding_window"),

            Map.entry("贪心", "greedy"),
            Map.entry("greedy", "greedy"),

            Map.entry("动态规划", "dynamic_programming"),
            Map.entry("dp", "dynamic_programming"),
            Map.entry("dynamic_programming", "dynamic_programming"),
            Map.entry("state_machine_dp", "dynamic_programming"),
            Map.entry("状态机_dp", "dynamic_programming"),

            Map.entry("图", "graph"),
            Map.entry("图论", "graph"),
            Map.entry("graph", "graph"),
            Map.entry("graph_theory", "graph"),

            Map.entry("dfs", "dfs"),
            Map.entry("depth_first_search", "dfs"),
            Map.entry("深度优先搜索", "dfs"),

            Map.entry("bfs", "bfs"),
            Map.entry("breadth_first_search", "bfs"),
            Map.entry("bfs_shortest_path", "bfs"),
            Map.entry("广度优先搜索", "bfs"),

            Map.entry("并查集", "union_find"),
            Map.entry("union_find", "union_find"),
            Map.entry("disjoint_set", "union_find"),

            Map.entry("最短路", "shortest_path"),
            Map.entry("shortest_path", "shortest_path"),

            Map.entry("拓扑排序", "topological_sort"),
            Map.entry("topological_sort", "topological_sort"),

            Map.entry("树", "tree"),
            Map.entry("tree", "tree"),

            Map.entry("数学", "math"),
            Map.entry("math", "math"),

            Map.entry("模拟", "simulation"),
            Map.entry("simulation", "simulation"),

            Map.entry("排序", "sorting"),
            Map.entry("sorting", "sorting"),

            Map.entry("arrays", "array"),

            Map.entry("deque", "deque"),
            Map.entry("dijkstra", "shortest_path"),
            Map.entry("gcd", "math"),
            Map.entry("kmp", "kmp"),
            Map.entry("layered_graph", "graph"),
            Map.entry("lower_bound", "binary_search"),
            Map.entry("misere", "misere"),
            Map.entry("nim", "nim"),
            Map.entry("upper_bound", "binary_search"),

            Map.entry("inversion_count", "inversion_count"),
            Map.entry("tsp_state_compression", "tsp_state_compression")
    );

    private final ProblemMapper problemMapper;
    private final SubmissionMapper submissionMapper;
    private final ProblemCatalog problemCatalog;
    private final AiProfileRecommendationClient profileRecommendationClient;

    public TutorRecommendationService(
            ProblemMapper problemMapper,
            SubmissionMapper submissionMapper,
            ProblemCatalog problemCatalog,
            AiProfileRecommendationClient profileRecommendationClient
    ) {
        this.problemMapper = problemMapper;
        this.submissionMapper = submissionMapper;
        this.problemCatalog = problemCatalog;
        this.profileRecommendationClient = profileRecommendationClient;
    }

    public List<TutorRecommendationResponse> recommend(Integer requestedLimit) {
        Long userId = SecuritySupport.currentUserId();
        int limit = normalizeLimit(requestedLimit);
        List<SubmissionEntity> submissions = submissionMapper.selectList(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getUserId, userId)
                .isNull(SubmissionEntity::getContestId)
                .isNull(SubmissionEntity::getContestRunId)
                .isNull(SubmissionEntity::getContestProblemId)
                .orderByDesc(SubmissionEntity::getCreatedAt)
                .orderByDesc(SubmissionEntity::getId));

        Map<Long, ProblemHistory> history = new HashMap<>();
        for (SubmissionEntity submission : submissions) {
            if (submission.getProblemId() == null) {
                continue;
            }
            ProblemHistory current = history.computeIfAbsent(submission.getProblemId(), ignored -> new ProblemHistory());
            current.attempts++;

            SubmissionStatus status = submission.getStatus();
            if (status == SubmissionStatus.ACCEPTED) {
                current.accepted = true;
            } else if (status != null && KNOWLEDGE_FAILURE_STATUSES.contains(status)) {
                current.failureCount++;
                if (current.firstFailure == null) {
                    current.firstFailure = status;
                }
            }
        }

        List<AiRecommendationProfileSignal> profileSignals =
        profileRecommendationClient.fetch(userId, 20);

        Map<String, Double> profileWeaknessScores =
                profileWeaknessScores(profileSignals);

        List<ProblemEntity> publicProblems = problemMapper.selectList(new LambdaQueryWrapper<ProblemEntity>()
                .eq(ProblemEntity::getDeleted, false)
                .isNull(ProblemEntity::getDeletedAt)
                .isNull(ProblemEntity::getArchivedAt)
                .eq(ProblemEntity::getVisibility, com.aioj.next.contract.problem.ProblemVisibility.PUBLIC));
        Map<String, Integer> weakTagScores = new HashMap<>();

        for (ProblemEntity problem : publicProblems) {
            ProblemHistory item = history.get(problem.getId());
            if (item == null || item.accepted || item.failureCount == 0) {
                continue;
            }

            for (String tag : problemCatalog.tagsOf(problem)) {
                if (tag == null || tag.isBlank()) {
                    continue;
                }
                weakTagScores.merge(tag.trim(), item.failureCount, Integer::sum);
            }
        }
        List<ScoredProblem> ranked = new ArrayList<>();
        for (ProblemEntity problem : publicProblems) {
            ProblemHistory item = history.get(problem.getId());

            if (item != null && item.accepted) {
                continue;
            }

            List<String> problemTags = problemCatalog.tagsOf(problem);

            List<String> matchingTags = problemTags.stream()
                    .map(String::trim)
                    .filter(tag -> !tag.isEmpty())
                    .filter(weakTagScores::containsKey)
                    .toList();

            int tagScore = matchingTags.stream()
                    .mapToInt(tag -> Math.min(weakTagScores.get(tag), 5))
                    .sum() * 10;

            ProfileMatch profileMatch =
                    matchProfileTags(problemTags, profileWeaknessScores);

            double score = (item == null
                    ? 100.0
                    : 70.0
                            - Math.min(item.attempts, 10) * 3.0
                            + Math.min(item.failureCount, 5) * 2.0)
                    + tagScore
                    + profileMatch.score() * 20.0;

            String reason;
            if (profileMatch.tag() != null) {
                reason = "根据你的学习画像，优先练习「"
                        + profileMatch.tag() + "」相关题目";
            } else if (item != null && item.failureCount > 0) {
                reason = "曾提交但尚未通过，适合针对性复习";
            } else if (!matchingTags.isEmpty()) {
                reason = "你最近在「" + matchingTags.get(0)
                        + "」知识点上有未通过记录";
            } else {
                reason = "尚未提交过，适合作为新的练习题";
            }
            ranked.add(new ScoredProblem(problem, score, reason));
        }
        ranked.sort(Comparator.comparingDouble(ScoredProblem::score).reversed()
                .thenComparing(item -> item.problem().getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.problem().getId(), Comparator.nullsLast(Comparator.reverseOrder())));
        List<ScoredProblem> selected = history.isEmpty()
                ? selectColdStart(ranked, limit)
                : ranked.stream().limit(limit).toList();
        return selected.stream().map(item -> new TutorRecommendationResponse(
                problemCatalog.toTutorResponse(item.problem()),
                BigDecimal.valueOf(item.score()).setScale(2, java.math.RoundingMode.HALF_UP),
                item.reason())).toList();
    }

    private List<ScoredProblem> selectColdStart(List<ScoredProblem> ranked, int limit) {
        List<Difficulty> preferredDifficulties = List.of(
                Difficulty.EASY,
                Difficulty.MEDIUM,
                Difficulty.EASY,
                Difficulty.MEDIUM,
                Difficulty.HARD,
                Difficulty.CHALLENGE
        );

        List<ScoredProblem> selected = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();

        for (Difficulty target : preferredDifficulties) {
            if (selected.size() >= limit) {
                break;
            }

            for (ScoredProblem candidate : ranked) {
                if (selectedIds.contains(candidate.problem().getId())) {
                    continue;
                }
                if (candidate.problem().getDifficulty() != target) {
                    continue;
                }

                selected.add(candidate);
                selectedIds.add(candidate.problem().getId());
                break;
            }
        }

        // 某种难度题目不足时，用剩余公开题补齐
        for (ScoredProblem candidate : ranked) {
            if (selected.size() >= limit) {
                break;
            }
            if (selectedIds.add(candidate.problem().getId())) {
                selected.add(candidate);
            }
        }

        return selected;
    }

    private int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private Map<String, Double> profileWeaknessScores(
            List<AiRecommendationProfileSignal> signals
    ) {
        Map<String, Double> scores = new HashMap<>();

        for (AiRecommendationProfileSignal signal : signals) {
            if (signal == null
                    || !"weakness".equalsIgnoreCase(signal.category())
                    || !"NEGATIVE".equalsIgnoreCase(signal.polarity())) {
                continue;
            }

            String key = profileTopicKey(signal.profileKey());
            if (key.isBlank()) {
                continue;
            }

            double confidence = signal.confidence() == null
                    ? 0.5
                    : Math.max(0.0, Math.min(1.0, signal.confidence()));

            int evidenceCount = signal.evidenceCount() == null
                    ? 1
                    : Math.max(1, Math.min(signal.evidenceCount(), 5));

            scores.merge(
                    key,
                    confidence * evidenceCount,
                    Double::sum
            );
        }

        return scores;
    }

    private ProfileMatch matchProfileTags(
            List<String> problemTags,
            Map<String, Double> profileScores
    ) {
        double score = 0.0;
        String matchedTag = null;

        for (String rawTag : problemTags) {
            String tagKey = canonicalKnowledgeKeyOrSelf(rawTag);
            if (tagKey.isBlank()) {
                continue;
            }

            Double matchedScore = profileScores.get(tagKey);
            if (matchedScore == null) {
                continue;
            }

            score += matchedScore;
            if (matchedTag == null) {
                matchedTag = rawTag.trim();
            }
        }

        return new ProfileMatch(score, matchedTag);
    }

    private String normalizeKnowledgeKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private String profileTopicKey(String profileKey) {
        String normalized = normalizeKnowledgeKey(profileKey);
        if (normalized.isBlank()) {
            return "";
        }

        for (String prefix : PROFILE_STATUS_PREFIXES) {
            if (normalized.equals(prefix)) {
                return "";
            }

            String prefixWithSeparator = prefix + "_";
            if (normalized.startsWith(prefixWithSeparator)) {
                String subject = normalized.substring(prefixWithSeparator.length());
                return canonicalKnowledgeKey(subject);
            }
        }

        return canonicalKnowledgeKeyOrSelf(normalized);
    }

    private String canonicalKnowledgeKey(String value) {
        String normalized = normalizeKnowledgeKey(value);
        if (normalized.isBlank()) {
            return "";
        }

        String canonical = KNOWLEDGE_ALIASES.get(normalized);
        if (canonical != null) {
            return canonical;
        }

        // 支持 binary_search_boundary、dynamic_programming_state 等扩展主题。
        for (Map.Entry<String, String> entry : KNOWLEDGE_ALIASES.entrySet()) {
            if (normalized.startsWith(entry.getKey() + "_")) {
                return entry.getValue();
            }
        }

        return "";
    }

    private String canonicalKnowledgeKeyOrSelf(String value) {
            String normalized = normalizeKnowledgeKey(value);
            if (normalized.isBlank()) {
                return "";
            }

            String canonical = canonicalKnowledgeKey(normalized);
            return canonical.isBlank() ? normalized : canonical;
        }

    private record ProfileMatch(double score, String tag) {
    }

    private static final class ProblemHistory {
        private int attempts;
        private int failureCount;
        private boolean accepted;
        private SubmissionStatus firstFailure;
    }

    private record ScoredProblem(ProblemEntity problem, double score, String reason) {
    }
}
