package com.aioj.next.problem.domain.search;

import com.aioj.next.contract.problem.Difficulty;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SearchVectorsTest {
    @Test
    void vectorsRoundTripAsLittleEndianFloats() {
        float[] vector = {0.5f, -1.25f, 3.0e-7f};

        byte[] bytes = SearchVectors.toBytes(vector);

        assertThat(bytes).hasSize(12);
        assertThat(bytes[0]).isEqualTo((byte) 0x00);
        assertThat(bytes[3]).isEqualTo((byte) 0x3f);
        assertThat(SearchVectors.fromBytes(bytes)).containsExactly(vector);
        assertThat(SearchVectors.fromBytes(null)).isEmpty();
        assertThat(SearchVectors.fromBytes(new byte[5])).isEmpty();
    }

    @Test
    void searchTextLeadsWithTitleAndTagsAndFitsTheBudget() {
        String text = SearchVectors.searchText("迷宫  最短路", Difficulty.MEDIUM, List.of("BFS", "图论"),
                "给定一个\n\n网格迷宫", null, 2000);

        assertThat(text).isEqualTo("标题：迷宫 最短路\n难度：MEDIUM\n标签：BFS、图论\n题面：给定一个 网格迷宫\n");
        assertThat(SearchVectors.searchText("t", null, List.of(), "x".repeat(5000), "", 120)).hasSize(120);
    }

    @Test
    void cuttingNeverSplitsASurrogatePair() {
        assertThat(SearchVectors.cut("a😀b", 2)).isEqualTo("a");
        assertThat(SearchVectors.cut("abc", 5)).isEqualTo("abc");
    }

    @Test
    void cosineUsesPrecomputedNormsAndRejectsMismatchedDimensions() {
        float[] left = {3, 4};
        float[] right = {6, 8};

        assertThat(SearchVectors.cosine(left, SearchVectors.norm(left), right, SearchVectors.norm(right)))
                .isCloseTo(1.0, within(1e-9));
        assertThat(SearchVectors.cosine(left, 5, new float[]{1, 0, 0}, 1)).isZero();
        assertThat(SearchVectors.cosine(left, 0, right, 10)).isZero();
    }

    @Test
    void unreadableTagsCountAsNone() {
        var mapper = JsonMapper.builder().build();

        assertThat(SearchVectors.tags(mapper, "[\" BFS \", \"\", \"图论\"]")).containsExactly("BFS", "图论");
        assertThat(SearchVectors.tags(mapper, "not json")).isEmpty();
        assertThat(SearchVectors.tags(mapper, null)).isEmpty();
    }
}
