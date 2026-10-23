package com.aioj.next.ai.providercontract;

import tools.jackson.databind.JsonNode;

import static com.aioj.next.ai.providercontract.ContractFixtures.PRIOR_REASONING;
import static com.aioj.next.ai.providercontract.ContractFixtures.READ_SCHEMA;
import static com.aioj.next.ai.providercontract.ContractFixtures.SEARCH_SCHEMA;
import static com.aioj.next.ai.providercontract.ContractFixtures.schema;
import static org.assertj.core.api.Assertions.assertThat;

/** What every client must put on the wire for {@link ContractFixtures#loopRequest}. */
final class WireAssertions {
    private WireAssertions() {
    }

    /** system, user, assistant tool call with its full reasoning, tool result. */
    static void assertLoopMessages(JsonNode body) {
        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.path(0).path("role").asString()).isEqualTo("system");
        assertThat(messages.path(0).path("content").asString()).isEqualTo(ContractFixtures.SYSTEM);
        assertThat(messages.path(1).path("role").asString()).isEqualTo("user");
        assertThat(messages.path(1).path("content").asString()).isEqualTo(ContractFixtures.USER);
        JsonNode assistant = messages.path(2);
        assertThat(assistant.path("role").asString()).isEqualTo("assistant");
        assertThat(assistant.path("reasoning_content").asString())
                .as("reasoning goes back on every assistant tool call").isEqualTo(PRIOR_REASONING);
        assertThat(assistant.path("tool_calls")).hasSize(1);
        assertThat(assistant.path("tool_calls").path(0).path("id").asString()).isEqualTo("call_0");
        assertThat(assistant.path("tool_calls").path(0).path("function").path("name").asString())
                .isEqualTo("problem__read");
        assertThat(assistant.path("tool_calls").path(0).path("function").path("arguments").asString())
                .isEqualTo("{\"id\":7}");
        assertThat(messages.path(3).path("role").asString()).isEqualTo("tool");
        assertThat(messages.path(3).path("tool_call_id").asString()).isEqualTo("call_0");
        assertThat(messages.path(3).path("content").asString()).isEqualTo(ContractFixtures.TOOL_RESULT);
    }

    /** Wire-encoded names and the MFJS schemas unchanged. */
    static void assertToolsPassThrough(JsonNode body) {
        JsonNode tools = body.path("tools");
        assertThat(tools).hasSize(2);
        assertThat(tools.path(0).path("type").asString()).isEqualTo("function");
        assertThat(tools.path(0).path("function").path("name").asString()).isEqualTo("problem__read");
        assertThat(tools.path(0).path("function").path("parameters")).isEqualTo(schema(READ_SCHEMA));
        assertThat(tools.path(1).path("function").path("name").asString()).isEqualTo("context__search_exact");
        assertThat(tools.path(1).path("function").path("parameters")).isEqualTo(schema(SEARCH_SCHEMA));
    }

    /** {@code null} when the function sends no strict flag. */
    static Boolean strictFlag(JsonNode body, int tool) {
        JsonNode strict = body.path("tools").path(tool).path("function").path("strict");
        return strict.isMissingNode() || strict.isNull() ? null : strict.asBoolean(false);
    }
}
