package com.aioj.next.agent.harness.tool.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SCHEMA = JSON.readTree("""
            {"type":"object","additionalProperties":false,"required":["id"],
             "properties":{"id":{"type":"integer","minimum":1,"maximum":9},
                           "mode":{"type":"string","enum":["fast","full"]},
                           "tags":{"type":"array","maxItems":2,"items":{"type":"string","maxLength":3}},
                           "flag":{"type":"boolean"}}}""");

    @Test
    void aValidInstancePasses() {
        assertThat(SchemaValidator.validate(SCHEMA, JSON.readTree("""
                {"id":3,"mode":"fast","tags":["a","bc"],"flag":true}"""))).isEmpty();
    }

    @Test
    void everyViolationIsReportedWithItsPath() {
        assertThat(SchemaValidator.validate(SCHEMA, JSON.readTree("""
                {"id":0,"mode":"slow","tags":["a","toolong","c"],"flag":"yes","other":1}""")))
                .containsExactlyInAnyOrder(
                        "$.id: less than minimum 1",
                        "$.mode: value is not one of the allowed enum values",
                        "$.tags: more than maxItems 2",
                        "$.tags[1]: longer than maxLength 3",
                        "$.flag: expected boolean",
                        "$.other: is not allowed");
        assertThat(SchemaValidator.validate(SCHEMA, JSON.readTree("{}"))).containsExactly("$.id: is required");
        assertThat(SchemaValidator.validate(SCHEMA, JSON.readTree("{\"id\":null}"))).containsExactly("$.id: must not be null");
    }

    @Test
    void combinatorsAndReferencesAreOutsideTheSubset() {
        assertThat(SchemaValidator.isSupportedSubset(SCHEMA)).isTrue();
        assertThat(SchemaValidator.isSupportedSubset(JSON.readTree("""
                {"type":"object","properties":{"x":{"$ref":"#/defs/x"}}}"""))).isFalse();
        assertThat(SchemaValidator.isSupportedSubset(JSON.readTree("""
                {"type":"object","properties":{"x":{"type":"array","items":{"oneOf":[]}}}}"""))).isFalse();
    }
}
