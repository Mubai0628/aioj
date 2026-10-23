package com.aioj.next.agent.harness.tool.internal;

import com.aioj.next.agent.harness.tool.api.SourceRef;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a result into the JSON envelope the model sees: data cut to the
 * descriptor's token budget (about 4 characters per token), error messages
 * redacted and shortened, and {@code instructionAllowed=false} on every
 * envelope. Same envelope as ai-service's ToolResultSanitizer.
 */
final class ModelPayloadRenderer {
    static final String TRUNCATION_WARNING =
            "Result was truncated to fit the token budget; narrow the query or fetch with pagination.";

    private final ObjectMapper objectMapper;

    ModelPayloadRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Returns the result as the model will see it, with {@code truncated} set when the data was cut. */
    Rendered render(ToolResult result, ToolDescriptor descriptor) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("status", result.status().name());
        int maxChars = descriptor == null ? 16_000 : descriptor.maxResultTokens() * 4;
        boolean truncated = result.truncated();
        List<String> warnings = new ArrayList<>(result.warnings());
        JsonNode dataNode = result.data() == null ? null : objectMapper.valueToTree(result.data());
        if (dataNode != null && !dataNode.isNull()) {
            String serialized = dataNode.toString();
            if (serialized.length() > maxChars) {
                truncated = true;
                warnings.add(TRUNCATION_WARNING);
                envelope.put("data", serialized.substring(0, maxChars) + "…[truncated]");
                envelope.put("dataTruncatedAsText", true);
            } else {
                envelope.set("data", dataNode);
            }
        }
        if (!result.sources().isEmpty()) {
            ArrayNode sources = envelope.putArray("sources");
            for (SourceRef source : result.sources()) {
                ObjectNode node = sources.addObject();
                node.put("type", source.type());
                node.put("id", source.id());
            }
        }
        if (result.dataClass() != null) {
            envelope.put("classification", result.dataClass());
        }
        if (result.trust() != null) {
            envelope.put("trustLevel", result.trust().name());
        }
        envelope.put("truncated", truncated);
        if (result.nextCursor() != null) {
            envelope.put("nextCursor", result.nextCursor());
        }
        if (result.errorCode() != null) {
            envelope.put("errorCode", result.errorCode());
        }
        if (result.errorMessage() != null) {
            envelope.put("errorMessage", redact(result.errorMessage()));
        }
        if (!warnings.isEmpty()) {
            ArrayNode warningNodes = envelope.putArray("warnings");
            warnings.forEach(warningNodes::add);
        }
        envelope.put("instructionAllowed", false);
        ToolResult rendered = truncated == result.truncated() ? result
                : new ToolResult(result.status(), result.data(), result.sources(), result.dataClass(), result.trust(),
                        true, result.nextCursor(), warnings, result.errorCode(), result.errorMessage());
        return new Rendered(rendered, envelope.toString());
    }

    private static String redact(String message) {
        String redacted = message.replaceAll("(?i)(api[-_ ]?key|token|secret|password)\\s*[:=]\\s*\\S+", "$1=***");
        redacted = redacted.replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-***");
        return redacted.length() <= 300 ? redacted : redacted.substring(0, 300);
    }

    record Rendered(ToolResult result, String payload) {
    }
}
