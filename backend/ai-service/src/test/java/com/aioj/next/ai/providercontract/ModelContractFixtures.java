package com.aioj.next.ai.providercontract;

import com.aioj.next.ai.model.DefaultModelClient;
import com.aioj.next.ai.model.api.CallProfile;
import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.model.api.ModelCallListener;
import com.aioj.next.ai.model.api.ModelCallRecord;
import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelRequest;
import com.aioj.next.ai.model.api.ProviderId;
import com.aioj.next.ai.model.api.ToolCall;
import com.aioj.next.ai.model.api.ToolChoice;
import com.aioj.next.ai.model.api.ToolSpec;
import com.aioj.next.ai.model.config.ModelClientProperties;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.aioj.next.ai.providercontract.ContractFixtures.API_KEY;
import static com.aioj.next.ai.providercontract.ContractFixtures.PRIOR_REASONING;
import static com.aioj.next.ai.providercontract.ContractFixtures.READ_SCHEMA;
import static com.aioj.next.ai.providercontract.ContractFixtures.SEARCH_SCHEMA;
import static com.aioj.next.ai.providercontract.ContractFixtures.SYSTEM;
import static com.aioj.next.ai.providercontract.ContractFixtures.TOOL_RESULT;
import static com.aioj.next.ai.providercontract.ContractFixtures.USER;
import static com.aioj.next.ai.providercontract.ContractFixtures.schema;

/** {@link ContractFixtures}' logical request, in the model call package's own types (plan T1.2b). */
final class ModelContractFixtures {
    static final String SCOPE = "TEXT_GENERATION";

    private ModelContractFixtures() {
    }

    static ModelEndpointConfig endpoint(ProviderId provider, String baseUrl, String model, boolean thinking,
                                        String effort) {
        return ModelEndpointConfig.builder(provider).baseUrl(baseUrl).apiKey(API_KEY).model(model)
                .thinking(thinking, effort).temperature(0.3).maxTokens(4096).build();
    }

    static List<ToolSpec> tools() {
        return List.of(new ToolSpec("problem.read", "Read a problem.", schema(READ_SCHEMA)),
                new ToolSpec("context.search_exact", "Search the conversation.", schema(SEARCH_SCHEMA)));
    }

    /** system, user, assistant tool call with its reasoning, tool result: one finished loop step. */
    static ModelRequest loopRequest(ModelEndpointConfig endpoint, ToolChoice toolChoice) {
        return ModelRequest.builder(SCOPE).endpoint(endpoint)
                .message(ChatMessage.system(SYSTEM))
                .message(ChatMessage.user(USER))
                .message(ChatMessage.assistant("", PRIOR_REASONING,
                        List.of(new ToolCall("call_0", "problem.read", "{\"id\":7}"))))
                .message(ChatMessage.toolResult("call_0", TOOL_RESULT))
                .tools(tools()).toolChoice(toolChoice).profile(CallProfile.CHAT_STREAM).build();
    }

    static ModelRequest userOnly(ModelEndpointConfig endpoint, CallProfile profile) {
        return ModelRequest.builder(SCOPE).endpoint(endpoint).message(ChatMessage.user(USER)).profile(profile).build();
    }

    /** Short timeouts and near-instant backoff, so failure tests stay fast. */
    static ModelClientProperties fastProperties() {
        ModelClientProperties properties = new ModelClientProperties();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setFirstByteTimeout(Duration.ofSeconds(5));
        properties.setTotalTimeout(Duration.ofSeconds(10));
        properties.getRetry().setInitialBackoff(Duration.ofMillis(10));
        properties.getRetry().setMaxBackoff(Duration.ofMillis(20));
        return properties;
    }

    /** A client whose configuration source always answers with the given endpoint. */
    static DefaultModelClient client(ModelEndpointConfig endpoint, ModelClientProperties properties,
                                     ModelCallListener... listeners) {
        return new DefaultModelClient(scope -> endpoint, List.of(listeners), properties);
    }

    /** Keeps every finished-call record. */
    static final class Recorder implements ModelCallListener {
        final List<ModelCallRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void onFinish(ModelCallRecord record) {
            records.add(record);
        }

        ModelCallRecord last() {
            return records.get(records.size() - 1);
        }
    }
}
