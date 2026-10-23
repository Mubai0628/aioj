package com.aioj.next.ai.providercontract;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.model.CallProfile;
import com.aioj.next.ai.agent.model.GatewayMessage;
import com.aioj.next.ai.agent.model.GatewayRequest;
import com.aioj.next.ai.agent.model.GatewayToolCall;
import com.aioj.next.ai.agent.model.ToolChoiceMode;
import com.aioj.next.ai.agent.tool.ToolAuditLevel;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** The one logical request every contract test sends, whichever client sends it. */
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

    static AiModelEffectiveConfig config(String provider, String baseUrl, String model,
                                         boolean thinking, String effort) {
        return new AiModelEffectiveConfig(AiModelScope.TEXT_GENERATION, true, false, "DATABASE", provider,
                baseUrl, API_KEY, "sk-***", "database", null, model, false, thinking, effort, 0.3, 4096,
                null, null, null);
    }

    static List<ToolDescriptor> tools() {
        return List.of(descriptor("problem.read", "Read a problem.", READ_SCHEMA),
                descriptor("context.search_exact", "Search the conversation.", SEARCH_SCHEMA));
    }

    /** system, user, assistant tool call with its reasoning, tool result: one finished loop step. */
    static GatewayRequest loopRequest(ToolChoiceMode toolChoice) {
        return new GatewayRequest(List.of(
                GatewayMessage.system(SYSTEM),
                GatewayMessage.user(USER),
                GatewayMessage.assistant("", PRIOR_REASONING,
                        List.of(new GatewayToolCall("call_0", "problem.read", "{\"id\":7}"))),
                GatewayMessage.toolResult("call_0", TOOL_RESULT)),
                tools(), toolChoice, CallProfile.CHAT_STREAM);
    }

    static JsonNode schema(String json) {
        return JSON.readTree(json);
    }

    private static ToolDescriptor descriptor(String name, String description, String schema) {
        return new ToolDescriptor(name, "1.0.0", description, JSON.readTree(schema), ToolRiskLevel.LOW,
                true, true, Set.of("AI_CHAT"), Set.of(DataClassification.PUBLIC), 1000, Duration.ofSeconds(5),
                ToolAuditLevel.FULL);
    }
}
