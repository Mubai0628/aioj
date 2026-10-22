package com.aioj.next.ai.agent.digest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StubDigestFactoryTest {

    private final StubDigestFactory factory = new StubDigestFactory(new ObjectMapper());

    @Test
    void extractsCodeBlocksKeywordsAndExplicitRefs() throws Exception {
        TurnDigestInput input = new TurnDigestInput(
                "t-1", "c-1", 7L, "100", "200",
                "这题用 `lower_bound` 行吗？数据范围 1e5。\n```cpp\nint main() { return 0; }\n```",
                "可以，注意边界。\n```python\nprint('hi')\n```",
                "deepseek-v4-pro",
                42L, List.of(42L, 43L),
                "check(d) 为什么取最左", "88",
                null,
                "PROBLEM_PAGE"
        );

        StubDigestFactory.BuiltStubDigest stub = factory.build(input);
        JsonNode root = new ObjectMapper().readTree(stub.structuredDigestJson());

        assertThat(root.get("schemaVersion").asInt()).isEqualTo(3);
        assertThat(root.get("turnId").asString()).isEqualTo("t-1");
        assertThat(root.get("entryPoint").asString()).isEqualTo("PROBLEM_PAGE");

        JsonNode codeRefs = root.get("codeRefs");
        assertThat(codeRefs).hasSize(2);
        assertThat(codeRefs.get(0).get("language").asString()).isEqualTo("cpp");
        assertThat(codeRefs.get(0).get("firstLine").asString()).contains("int main");
        assertThat(codeRefs.get(1).get("language").asString()).isEqualTo("python");

        assertThat(root.get("searchKeywords").toString()).contains("lower_bound");

        assertThat(root.get("problemRefs").toString()).contains("42", "43");
        JsonNode entities = root.get("entities");
        assertThat(entities).hasSize(2);
        assertThat(entities.get(0).get("type").asString()).isEqualTo("PROBLEM");

        JsonNode selection = root.get("explicitSelection");
        assertThat(selection.get("text").asString()).contains("check(d)");
        assertThat(selection.get("sourceMessageId").asString()).isEqualTo("88");

        assertThat(root.get("source").get("userMessageId").asString()).isEqualTo("100");
        assertThat(root.get("source").get("assistantMessageId").asString()).isEqualTo("200");
        assertThat(root.get("source").get("sourceHash").asString()).isEqualTo(stub.sourceHash());

        assertThat(stub.digestVersion()).isEqualTo(1);
        assertThat(stub.summary()).startsWith("用户：");
        assertThat(stub.searchText()).contains("lower_bound");
        assertThat(stub.tokenEstimate()).isGreaterThan(0);
    }

    @Test
    void sourceHashIsStableAndContentSensitive() {
        TurnDigestInput first = input("同一句话", "同一回答");
        TurnDigestInput second = input("同一句话", "不同回答");

        assertThat(factory.build(first).sourceHash()).isEqualTo(factory.build(first).sourceHash());
        assertThat(factory.build(first).sourceHash()).isNotEqualTo(factory.build(second).sourceHash());
    }

    @Test
    void handlesBlankContentsWithoutKeywords() throws Exception {
        StubDigestFactory.BuiltStubDigest stub = factory.build(input(null, null));
        JsonNode root = new ObjectMapper().readTree(stub.structuredDigestJson());

        assertThat(root.get("codeRefs")).isEmpty();
        assertThat(root.get("entities")).isEmpty();
        assertThat(root.get("explicitSelection").isNull()).isTrue();
        assertThat(stub.searchText()).isEmpty();
        assertThat(stub.sourceHash()).hasSize(64);
    }

    private TurnDigestInput input(String user, String assistant) {
        return new TurnDigestInput("t-9", "c-9", 7L, "1", "2", user, assistant,
                null, null, List.of(), null, null, null, "CHAT");
    }
}
