package com.aioj.next.common.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan S6: ids of 16 or more digits leave as strings wherever they sit in audit JSON. */
class JsonLongIdsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void longIntegersBecomeStringsInObjectsArraysAndNestedTrees() {
        JsonNode stored = JSON.readTree("""
                {"problemId":1990011223344556677,"refs":[1990011223344556678,{"run":-1990011223344556679}],
                 "huge":123456789012345678901234567890,"similarity":0.91,"count":3,"name":"x"}""");

        JsonNode out = JsonLongIds.stringifyNode(stored);

        assertThat(out).isEqualTo(JSON.readTree("""
                {"problemId":"1990011223344556677","refs":["1990011223344556678",{"run":"-1990011223344556679"}],
                 "huge":"123456789012345678901234567890","similarity":0.91,"count":3,"name":"x"}"""));
        assertThat(stored.path("problemId").isIntegralNumber()).as("the stored tree is not changed").isTrue();
    }

    @Test
    void sixteenDigitsIsTheBoundary() {
        JsonNode out = JsonLongIds.stringifyNode(JSON.readTree("[999999999999999,1000000000000000,-999999999999999]"));

        assertThat(out.get(0).isIntegralNumber()).isTrue();
        assertThat(out.get(1).asString()).isEqualTo("1000000000000000");
        assertThat(out.get(1).isString()).isTrue();
        assertThat(out.get(2).isIntegralNumber()).isTrue();
    }

    @Test
    void aTreeWithoutLongIntegersIsReturnedAsItIs() {
        JsonNode tree = JSON.readTree("{\"a\":[1,2.5,\"1990011223344556677\"],\"b\":{\"c\":null}}");

        assertThat(JsonLongIds.stringifyNode(tree)).isSameAs(tree);
        assertThat(JsonLongIds.stringifyNode(null)).isNull();
    }

    @Test
    void mapsAndListsReadFromAJsonColumnAreConvertedToo() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("submissionIds", new ArrayList<>(List.of(1990011223344556677L, 7L)));
        evidence.put("pair", Map.of("left", BigInteger.valueOf(1990011223344556678L)));
        evidence.put("ratio", 0.5);
        evidence.put("note", null);

        Map<String, Object> out = JsonLongIds.stringifyMap(evidence);

        assertThat(out.get("submissionIds")).isEqualTo(List.of("1990011223344556677", 7L));
        assertThat(out.get("pair")).isEqualTo(Map.of("left", "1990011223344556678"));
        assertThat(out.get("ratio")).isEqualTo(0.5);
        assertThat(out).containsKey("note");
        assertThat(evidence.get("submissionIds")).isEqualTo(List.of(1990011223344556677L, 7L));
        assertThat(JsonLongIds.stringifyMap(null)).isNull();
    }

    @Test
    void jsonTextIsRewrittenAndOtherTextIsLeftAlone() {
        assertThat(JsonLongIds.stringifyText("{\"contestRunId\":1990011223344556677,\"count\":2}", JSON))
                .isEqualTo("{\"contestRunId\":\"1990011223344556677\",\"count\":2}");
        String small = "{\"count\": 2}";
        assertThat(JsonLongIds.stringifyText(small, JSON)).isSameAs(small);
        assertThat(JsonLongIds.stringifyText("not json 1990011223344556677", JSON))
                .isEqualTo("not json 1990011223344556677");
        assertThat(JsonLongIds.stringifyText(null, JSON)).isNull();
    }

    @Test
    void idsAsText() {
        assertThat(JsonLongIds.text(1990011223344556677L)).isEqualTo("1990011223344556677");
        assertThat(JsonLongIds.text(null)).isNull();
    }
}
