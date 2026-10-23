package com.aioj.next.ai.model.api;

/**
 * What one provider and model can do (plan A1.3). The model call package checks a
 * request against it before sending anything; an illegal combination is an
 * explicit {@link ModelErrorKind#CAPABILITY_UNSUPPORTED}, never a silent downgrade.
 *
 * @param toolChoiceRequiredNative {@code tool_choice=required} can be sent; otherwise
 *                                 the Agent loop simulates it
 * @param jsonSchema               {@code response_format=json_schema} is native; otherwise
 *                                 a JSON-schema request is sent as {@code json_object}
 * @param thinkingToggle           thinking can be switched on and off per request
 * @param thinkingAlwaysOn         the model always thinks; a request without thinking still thinks
 */
public record ProviderCapabilities(
        ProviderId provider,
        String model,
        boolean chat,
        boolean embeddings,
        boolean toolCalling,
        boolean toolChoiceRequiredNative,
        boolean strictFunctionSchema,
        boolean streaming,
        boolean jsonObject,
        boolean jsonSchema,
        boolean thinkingToggle,
        boolean thinkingAlwaysOn,
        boolean modelListing
) {

    /** Whether a call with this profile thinks on this model. */
    public boolean thinks(ModelEndpointConfig endpoint, CallProfile profile, boolean thinkingDisabled) {
        if (thinkingAlwaysOn) {
            return true;
        }
        return thinkingToggle && endpoint.thinkingEnabled() && !profile.forceThinkingDisabled() && !thinkingDisabled;
    }
}
