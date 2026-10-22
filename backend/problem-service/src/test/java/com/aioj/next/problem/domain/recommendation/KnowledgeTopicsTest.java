package com.aioj.next.problem.domain.recommendation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeTopicsTest {
    private final KnowledgeTopics topics = new KnowledgeTopics();

    @Test
    void tagsNormalizeLikeKnowledgeNodesAndShareSynonyms() {
        assertThat(KnowledgeTopics.normalize("  Binary Search ")).isEqualTo("binary_search");
        assertThat(KnowledgeTopics.normalize("二分-查找")).isEqualTo("二分_查找");
        assertThat(topics.tagTopic("二分查找")).isEqualTo("binary_search");
        assertThat(topics.tagTopic("DP")).isEqualTo("dynamic_programming");
        assertThat(topics.tagTopic("快速排序")).isEqualTo("sorting");
        assertThat(topics.tagTopic("segment_tree")).isEqualTo("segment_tree");
    }

    @Test
    void aTagExtendingAKnownTopicTakesTheMostSpecificOne() {
        assertThat(topics.tagTopic("binary_search_on_answer")).isEqualTo("binary_search");
        assertThat(topics.tagTopic("bfs_shortest_path_grid")).isEqualTo("bfs");
        assertThat(topics.tagTopic("dp_optimization")).isEqualTo("dynamic_programming");

        KnowledgeTopics overlapping = new KnowledgeTopics(Map.of("tree", "tree", "tree_dp", "dynamic_programming"));
        assertThat(overlapping.tagTopic("tree_dp_rerooting")).isEqualTo("dynamic_programming");
        assertThat(overlapping.tagTopic("tree_diameter")).isEqualTo("tree");
    }

    @Test
    void statusProfileKeysCountOnlyWithAKnownTopic() {
        assertThat(topics.profileTopic("wrong_answer_lower_bound")).isEqualTo("binary_search");
        assertThat(topics.profileTopic("time_limit_exceeded_dp")).isEqualTo("dynamic_programming");
        assertThat(topics.profileTopic("wrong_answer_debugging")).isEmpty();
        assertThat(topics.profileTopic("wrong_answer")).isEmpty();
        assertThat(topics.profileTopic("status_wrong_answer")).isEmpty();
        assertThat(topics.profileTopic("inversion_count")).isEqualTo("inversion_count");
        assertThat(topics.profileTopic("二分查找")).isEqualTo("binary_search");
    }

    @Test
    void problemTopicsKeepTheFirstTagThatNamedThem() {
        assertThat(topics.topicsOf(List.of("二分", "binary_search", " 贪心 ", "")))
                .containsExactly(Map.entry("binary_search", "二分"), Map.entry("greedy", "贪心"));
    }
}
