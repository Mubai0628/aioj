package com.aioj.next.ai.providercontract;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The texts and schemas of the one logical request every contract test sends;
 * {@link ModelContractFixtures} builds it in the model call package's types.
 */
final class ContractFixtures {
    static final String API_KEY = "sk-contract-test-key";
    static final String SYSTEM = "You are a tutor. system-text-31c4";
    static final String USER = "Why does my DP time out? user-text-8b2e";
    static final String PRIOR_REASONING = "I should read the problem first. reasoning-text-46a1";
    static final String TOOL_RESULT = "{\"title\":\"Knapsack\",\"limits\":\"n<=100\"}";

    /** A schema with the features MFJS relies on; it must reach the Provider unchanged. */
    static final String READ_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "properties":{"id":{"type":"integer","minimum":1,"description":"problem id"},
                           "sections":{"type":"array","maxItems":2,
                                       "items":{"type":"string","enum":["statement","samples"]}}},
             "required":["id"]}""";
    static final String SEARCH_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "properties":{"query":{"type":"string","minLength":1,"maxLength":100},
                           "limit":{"type":"integer","minimum":1,"maximum":5}},
             "required":["query"]}""";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ContractFixtures() {
    }

    static JsonNode schema(String json) {
        return JSON.readTree(json);
    }
}
