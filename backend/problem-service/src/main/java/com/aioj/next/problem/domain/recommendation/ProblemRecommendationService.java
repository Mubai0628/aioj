package com.aioj.next.problem.domain.recommendation;

import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.AiRecommendationProfileSignal;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemRecommendationReason;
import com.aioj.next.contract.problem.ProblemRecommendationResponse;
import com.aioj.next.contract.problem.ProblemRecommendationsResponse;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.mapper.PracticeProblemHistory;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Explainable practice recommendations. Every public problem the student has not
 * solved in practice gets a rule score: unattempted problems start at 100,
 * attempted ones lower, plus a boost for topics the student keeps failing and for
 * confirmed weaknesses in the learning profile. A student with no practice yet
 * gets an easy-to-hard starter set. Contest submissions are left out entirely.
 */
@Service
public class ProblemRecommendationService {
    static final int DEFAULT_LIMIT = 6;
    static final int MAX_LIMIT = 50;
    /** Candidates are the newest public problems plus older ones the student attempted without solving. */
    static final int MAX_CANDIDATES = 2000;
    static final int MAX_HISTORY_PROBLEMS = 1000;
    private static final int PROFILE_SIGNAL_LIMIT = 20;
    private static final List<Difficulty> COLD_START_GRADIENT = List.of(Difficulty.EASY, Difficulty.MEDIUM,
            Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD, Difficulty.CHALLENGE);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final Comparator<Scored> BY_SCORE = Comparator.comparingDouble((Scored item) -> item.entry().score()).reversed()
            .thenComparing(item -> item.problem().getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(item -> item.problem().getId(), Comparator.nullsLast(Comparator.reverseOrder()));

    private final ProblemMapper problemMapper;
    private final SubmissionMapper submissionMapper;
    private final AiRecommendationSignalClient signalClient;
    private final KnowledgeTopics topics;
    private final ProblemRecommendationCache cache;
    private final ObjectMapper objectMapper;

    public ProblemRecommendationService(ProblemMapper problemMapper, SubmissionMapper submissionMapper,
                                        AiRecommendationSignalClient signalClient, KnowledgeTopics topics,
                                        ProblemRecommendationCache cache, ObjectMapper objectMapper) {
        this.problemMapper = problemMapper;
        this.submissionMapper = submissionMapper;
        this.signalClient = signalClient;
        this.topics = topics;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    public ProblemRecommendationsResponse recommend(Integer requestedLimit) {
        Long userId = SecuritySupport.currentUserId();
        int limit = requestedLimit == null || requestedLimit <= 0 ? DEFAULT_LIMIT : Math.min(requestedLimit, MAX_LIMIT);
        ProblemRecommendationCache.Ranking ranking = cache.get(userId);
        if (ranking == null) {
            ranking = rank(userId);
            cache.put(userId, ranking);
        }
        return present(ranking, limit);
    }

    /** Titles and visibility are read fresh, so a cached ranking never shows a problem hidden since. */
    private ProblemRecommendationsResponse present(ProblemRecommendationCache.Ranking ranking, int limit) {
        List<Long> ids = ranking.entries().stream().map(ProblemRecommendationCache.Entry::problemId).toList();
        Map<Long, ProblemEntity> visible = ids.isEmpty() ? Map.of() : problemMapper.selectList(publicProblems()
                        .in(ProblemEntity::getId, ids))
                .stream()
                .collect(Collectors.toMap(ProblemEntity::getId, Function.identity(), (left, right) -> left));
        List<ProblemRecommendationResponse> items = ranking.entries().stream()
                .filter(entry -> visible.containsKey(entry.problemId()))
                .limit(limit)
                .map(entry -> {
                    ProblemEntity problem = visible.get(entry.problemId());
                    return new ProblemRecommendationResponse(problem.getId(), problem.getTitle(), problem.getDifficulty(),
                            tags(problem), entry.score(), entry.reason(), entry.reasonParams());
                })
                .toList();
        return new ProblemRecommendationsResponse(items, ranking.solvedProblems(), ranking.coldStart());
    }

    private ProblemRecommendationCache.Ranking rank(Long userId) {
        Map<Long, PracticeProblemHistory> history = new HashMap<>();
        for (PracticeProblemHistory item : submissionMapper.practiceHistory(userId, MAX_HISTORY_PROBLEMS)) {
            if (item.getProblemId() != null) {
                history.put(item.getProblemId(), item);
            }
        }
        Map<String, Double> profileWeights = profileWeights(signalClient.weaknesses(userId, PROFILE_SIGNAL_LIMIT));
        List<ProblemEntity> candidates = candidates(history);
        Map<Long, Map<String, String>> topicsByProblem = new HashMap<>();
        for (ProblemEntity problem : candidates) {
            topicsByProblem.put(problem.getId(), topics.topicsOf(tags(problem)));
        }
        // Topics of problems the student failed and has not solved since, weighted by failures.
        Map<String, Integer> weakTopics = new HashMap<>();
        for (ProblemEntity problem : candidates) {
            PracticeProblemHistory item = history.get(problem.getId());
            if (item != null && !item.isAccepted() && item.getFailures() > 0) {
                topicsByProblem.get(problem.getId()).keySet()
                        .forEach(topic -> weakTopics.merge(topic, item.getFailures(), Integer::sum));
            }
        }
        boolean coldStart = history.isEmpty();
        List<Scored> ranked = new ArrayList<>();
        for (ProblemEntity problem : candidates) {
            PracticeProblemHistory item = history.get(problem.getId());
            if (item == null || !item.isAccepted()) {
                ranked.add(score(problem, item, topicsByProblem.get(problem.getId()), weakTopics, profileWeights, coldStart));
            }
        }
        ranked.sort(BY_SCORE);
        List<Scored> selected = coldStart ? coldStartGradient(ranked) : ranked.stream().limit(MAX_LIMIT).toList();
        return new ProblemRecommendationCache.Ranking(selected.stream().map(Scored::entry).toList(),
                submissionMapper.countSolvedPracticeProblems(userId), coldStart);
    }

    private Scored score(ProblemEntity problem, PracticeProblemHistory item, Map<String, String> problemTopics,
                         Map<String, Integer> weakTopics, Map<String, Double> profileWeights, boolean coldStart) {
        double score = item == null
                ? 100.0
                : 70.0 - Math.min(item.getAttempts(), 10) * 3.0 + Math.min(item.getFailures(), 5) * 2.0;
        String weakTag = null;
        String profileTag = null;
        for (Map.Entry<String, String> topic : problemTopics.entrySet()) {
            Integer failures = weakTopics.get(topic.getKey());
            if (failures != null) {
                score += Math.min(failures, 5) * 10.0;
                weakTag = weakTag == null ? topic.getValue() : weakTag;
            }
            Double weight = profileWeights.get(topic.getKey());
            if (weight != null) {
                score += weight * 20.0;
                profileTag = profileTag == null ? topic.getValue() : profileTag;
            }
        }
        ProblemRecommendationReason reason;
        Map<String, String> params;
        if (profileTag != null) {
            reason = ProblemRecommendationReason.PROFILE_WEAKNESS;
            params = Map.of("tag", profileTag);
        } else if (item != null) {
            reason = ProblemRecommendationReason.UNSOLVED_ATTEMPT;
            params = Map.of("attempts", String.valueOf(item.getAttempts()));
        } else if (weakTag != null) {
            reason = ProblemRecommendationReason.WEAK_TAG;
            params = Map.of("tag", weakTag);
        } else {
            reason = coldStart ? ProblemRecommendationReason.COLD_START : ProblemRecommendationReason.NEW_PROBLEM;
            params = Map.of();
        }
        double rounded = BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP).doubleValue();
        return new Scored(problem, new ProblemRecommendationCache.Entry(problem.getId(), rounded, reason, params));
    }

    /** One problem per gradient step (easy, medium, easy, medium, hard, challenge), then the best of the rest. */
    private List<Scored> coldStartGradient(List<Scored> ranked) {
        List<Scored> selected = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        for (Difficulty target : COLD_START_GRADIENT) {
            ranked.stream()
                    .filter(candidate -> candidate.problem().getDifficulty() == target
                            && !selectedIds.contains(candidate.problem().getId()))
                    .findFirst()
                    .ifPresent(candidate -> {
                        selected.add(candidate);
                        selectedIds.add(candidate.problem().getId());
                    });
        }
        for (Scored candidate : ranked) {
            if (selected.size() >= MAX_LIMIT) {
                break;
            }
            if (selectedIds.add(candidate.problem().getId())) {
                selected.add(candidate);
            }
        }
        return selected;
    }

    private Map<String, Double> profileWeights(List<AiRecommendationProfileSignal> signals) {
        Map<String, Double> weights = new HashMap<>();
        for (AiRecommendationProfileSignal signal : signals) {
            String topic = topics.profileTopic(signal.profileKey());
            if (topic.isEmpty()) {
                continue;
            }
            // A missing confidence reads as neutral; evidence beyond five adds nothing.
            double confidence = signal.confidence() > 0 ? Math.min(1.0, signal.confidence()) : 0.5;
            int evidence = Math.max(1, Math.min(signal.evidenceCount(), 5));
            weights.merge(topic, confidence * evidence, Double::sum);
        }
        return weights;
    }

    private List<ProblemEntity> candidates(Map<Long, PracticeProblemHistory> history) {
        Map<Long, ProblemEntity> byId = new LinkedHashMap<>();
        problemMapper.selectList(publicProblems()
                        .orderByDesc(ProblemEntity::getCreatedAt)
                        .orderByDesc(ProblemEntity::getId)
                        .last("LIMIT " + MAX_CANDIDATES))
                .forEach(problem -> byId.put(problem.getId(), problem));
        List<Long> olderUnsolved = history.values().stream()
                .filter(item -> !item.isAccepted() && !byId.containsKey(item.getProblemId()))
                .map(PracticeProblemHistory::getProblemId)
                .toList();
        if (!olderUnsolved.isEmpty()) {
            problemMapper.selectList(publicProblems().in(ProblemEntity::getId, olderUnsolved))
                    .forEach(problem -> byId.putIfAbsent(problem.getId(), problem));
        }
        return List.copyOf(byId.values());
    }

    /** Public, active problems, reading only the columns ranking needs. */
    private LambdaQueryWrapper<ProblemEntity> publicProblems() {
        return new LambdaQueryWrapper<ProblemEntity>()
                .select(ProblemEntity::getId, ProblemEntity::getTitle, ProblemEntity::getDifficulty,
                        ProblemEntity::getTags, ProblemEntity::getCreatedAt)
                .eq(ProblemEntity::getDeleted, false)
                .isNull(ProblemEntity::getDeletedAt)
                .isNull(ProblemEntity::getArchivedAt)
                .eq(ProblemEntity::getVisibility, ProblemVisibility.PUBLIC);
    }

    private List<String> tags(ProblemEntity problem) {
        if (!StringUtils.hasText(problem.getTags())) {
            return List.of();
        }
        try {
            List<String> tags = objectMapper.readValue(problem.getTags(), STRING_LIST);
            return tags == null ? List.of() : tags.stream().filter(StringUtils::hasText).toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private record Scored(ProblemEntity problem, ProblemRecommendationCache.Entry entry) {
    }
}
