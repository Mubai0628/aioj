package com.aioj.next.ai.agent.retrieval.builtin;

import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.FailurePolicy;
import com.aioj.next.agent.harness.tool.api.ToolAuditLevel;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.SourceRef;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in retrieval tool {@code context.fetch_sources} (design doc §4.2/§6.4 第四层):
 * the evidence-fetch counterpart of search_digests — resolves digest hits ({@code dg-*})
 * or direct message references ({@code msg-*}, or a bare message id) back to the ORIGINAL user/assistant
 * messages (定位与取证分离). Server-side ownership is enforced on every hop: digest and
 * message rows must belong to the calling user. Since P4-1 (design doc §10) fetches may
 * reach the user's OTHER conversations (cross-conversation recall) — the userId check is
 * the hard boundary, so another user's rows still resolve to NOT_FOUND. Content is
 * returned under a per-call token budget with explicit truncation markers. A call that
 * fetches nothing is a NOT_FOUND failure that says why for each reference: an empty
 * success let the model believe it had read a source it never got (B1, 2026-10-23).
 */
@Component
public class ContextFetchSourcesTool implements AgentTool {
    /** The hitId prefix of a message, as context.search_exact and fetched sources give it. */
    public static final String MESSAGE_HIT_PREFIX = "msg-";

    private static final int MAX_HIT_IDS = 5;
    private static final int DEFAULT_MAX_TOKENS = 4000;
    private static final Pattern CODE_BLOCK = Pattern.compile("```([A-Za-z0-9_+-]*)\\s*\n(.*?)```", Pattern.DOTALL);

    private final AiTurnDigestMapper digestMapper;
    private final AiMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final int fetchBudgetTokens;
    private final ToolDescriptor descriptor;

    public ContextFetchSourcesTool(
            AiTurnDigestMapper digestMapper,
            AiMessageMapper messageMapper,
            ObjectMapper objectMapper,
            AiProperties properties
    ) {
        this.digestMapper = digestMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.fetchBudgetTokens = properties == null ? 8000 : properties.getAgentCore().getFetchBudgetTokens();
        this.descriptor = new ToolDescriptor(
                "context.fetch_sources",
                "1.1.0",
                "Fetch the ORIGINAL messages behind search hits: pass their hitId (dg-* from "
                        + "context.search_digests, msg-* from context.search_exact; a bare message id also works). "
                        + "Hits may come from any of your own conversations (see the search_digests "
                        + "scope parameter). Always use this to verify exact wording/code before answering questions "
                        + "about earlier turns — never rely on digest summaries alone for facts, numbers or code.",
                buildSchema(),
                Set.of("AI_CHAT"),
                fetchBudgetTokens,
                Duration.ofSeconds(8),
                "fetch",
                ExecutionClass.READ_ONLY,
                FailurePolicy.FAIL_OPEN,
                ToolAuditLevel.FULL);
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        List<String> hitIds = normalizedHitIds(input);
        if (hitIds.isEmpty()) {
            return ToolResult.failure(null, ToolStatus.SCHEMA_ERROR, "EMPTY_HIT_IDS",
                    "hitIds must contain at least one dg-*/msg-* reference");
        }
        int maxTokens = input.path("maxTokens").isIntegralNumber()
                ? Math.min(fetchBudgetTokens, Math.max(500, input.path("maxTokens").asInt(0)))
                : Math.min(fetchBudgetTokens, DEFAULT_MAX_TOKENS);
        boolean includeUser = includes(input, "USER_MESSAGE");
        boolean includeAssistant = includes(input, "ASSISTANT_MESSAGE");
        boolean includeCodeBlocks = includes(input, "SELECTED_CODE_BLOCKS");

        Set<Long> messageIds = new LinkedHashSet<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (String hitId : hitIds) {
            if (hitId.startsWith(ContextSearchDigestsTool.HIT_ID_PREFIX)) {
                resolveDigestHit(context, hitId, includeUser, includeAssistant, messageIds, skipped);
            } else if (hitId.startsWith(MESSAGE_HIT_PREFIX)) {
                Long messageId = parseId(hitId.substring(MESSAGE_HIT_PREFIX.length()));
                if (messageId == null) {
                    skipped.add(skipEntry(hitId, "INVALID_REFERENCE"));
                } else {
                    messageIds.add(messageId);
                }
            } else if (bareMessageId(hitId) != null) {
                // Models copy the bare messageId of a hit; it means the same message.
                messageIds.add(bareMessageId(hitId));
            } else {
                skipped.add(skipEntry(hitId, "INVALID_REFERENCE"));
            }
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        int tokensUsed = 0;
        boolean truncated = false;
        for (Long messageId : messageIds) {
            AiMessageEntity message;
            try {
                message = messageMapper.selectById(messageId);
            } catch (RuntimeException ex) {
                return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "FETCH_FAILED", "message fetch failed");
            }
            if (message == null || !isOwned(message, context)) {
                skipped.add(skipEntry(MESSAGE_HIT_PREFIX + messageId, "NOT_FOUND"));
                continue;
            }
            String content = message.getContent() == null ? "" : message.getContent();
            int messageTokens = Math.max(1, content.length() / 4);
            if (tokensUsed + messageTokens > maxTokens) {
                int remainingChars = Math.max(0, (maxTokens - tokensUsed) * 4);
                if (remainingChars < 200) {
                    truncated = true;
                    skipped.add(skipEntry(MESSAGE_HIT_PREFIX + messageId, "BUDGET_EXCEEDED"));
                    continue;
                }
                content = content.substring(0, remainingChars) + "\n…[TRUNCATED]";
                messageTokens = content.length() / 4;
                truncated = true;
            }
            tokensUsed += messageTokens;
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("hitId", MESSAGE_HIT_PREFIX + message.getId());
            source.put("messageId", String.valueOf(message.getId()));
            source.put("role", message.getRole());
            source.put("createdAt", message.getCreatedAt() == null ? null : message.getCreatedAt().toString());
            source.put("content", content);
            source.put("trustLevel", "user".equals(message.getRole())
                    ? TrustLevel.USER_PROVIDED.name()
                    : TrustLevel.MODEL_INFERRED.name());
            if (includeCodeBlocks) {
                source.put("codeBlocks", extractCodeBlocks(content));
            }
            sources.add(source);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sources", sources);
        data.put("sourceCount", sources.size());
        data.put("tokensUsed", tokensUsed);
        data.put("maxTokens", maxTokens);
        data.put("truncated", truncated);
        if (!skipped.isEmpty()) {
            data.put("skipped", skipped);
        }
        if (sources.isEmpty()) {
            return new ToolResult<>(null, ToolStatus.NOT_FOUND, data, List.of(), DataClassification.USER_PRIVATE,
                    TrustLevel.USER_PROVIDED, null, truncated, null, null, List.of(), "NO_SOURCE_FETCHED",
                    nothingFetched(skipped));
        }
        List<SourceRef> refs = sources.stream()
                .map(source -> new SourceRef("MESSAGE", String.valueOf(source.get("messageId"))))
                .toList();
        return ToolResult.success(null, data, refs, DataClassification.USER_PRIVATE, TrustLevel.USER_PROVIDED);
    }

    private void resolveDigestHit(
            ToolExecutionContext context,
            String hitId,
            boolean includeUser,
            boolean includeAssistant,
            Set<Long> messageIds,
            List<Map<String, Object>> skipped
    ) {
        Long digestId = parseId(hitId.substring(ContextSearchDigestsTool.HIT_ID_PREFIX.length()));
        if (digestId == null) {
            skipped.add(skipEntry(hitId, "INVALID_REFERENCE"));
            return;
        }
        AiTurnDigestEntity digest;
        try {
            digest = digestMapper.selectOne(new QueryWrapper<AiTurnDigestEntity>()
                    .eq("id", digestId)
                    .eq("user_id", context.userId())
                    .last("LIMIT 1"));
        } catch (RuntimeException ex) {
            skipped.add(skipEntry(hitId, "FETCH_FAILED"));
            return;
        }
        if (digest == null) {
            skipped.add(skipEntry(hitId, "NOT_FOUND"));
            return;
        }
        try {
            JsonNode source = objectMapper.readTree(digest.getStructuredDigest() == null ? "{}" : digest.getStructuredDigest())
                    .path("source");
            if (includeUser) {
                Long userMessageId = parseId(source.path("userMessageId").asString(""));
                if (userMessageId != null) {
                    messageIds.add(userMessageId);
                }
            }
            if (includeAssistant) {
                Long assistantMessageId = parseId(source.path("assistantMessageId").asString(""));
                if (assistantMessageId != null) {
                    messageIds.add(assistantMessageId);
                }
            }
        } catch (Exception ex) {
            skipped.add(skipEntry(hitId, "MALFORMED_DIGEST"));
        }
    }

    /** Ownership boundary: the row must belong to the calling user (any of their conversations). */
    private boolean isOwned(AiMessageEntity message, ToolExecutionContext context) {
        return message.getUserId() != null
                && message.getUserId() == context.userId();
    }

    private List<Map<String, Object>> extractCodeBlocks(String content) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Matcher matcher = CODE_BLOCK.matcher(content);
        while (matcher.find() && blocks.size() < 8) {
            Map<String, Object> block = new LinkedHashMap<>();
            String language = matcher.group(1);
            block.put("language", language == null || language.isBlank() ? "text" : language.toLowerCase());
            block.put("code", matcher.group(2).strip());
            blocks.add(block);
        }
        return blocks;
    }

    private boolean includes(JsonNode input, String value) {
        JsonNode include = input.path("include");
        if (!include.isArray() || include.isEmpty()) {
            return true; // default: everything
        }
        for (JsonNode item : include) {
            if (value.equals(item.asString(""))) {
                return true;
            }
        }
        return false;
    }

    private List<String> normalizedHitIds(JsonNode input) {
        List<String> hitIds = new ArrayList<>();
        JsonNode array = input.path("hitIds");
        if (!array.isArray()) {
            return hitIds;
        }
        for (JsonNode item : array) {
            if (hitIds.size() >= MAX_HIT_IDS) {
                break;
            }
            if (item.isString() && !item.asString("").isBlank() && !hitIds.contains(item.asString("").trim())) {
                hitIds.add(item.asString("").trim());
            }
        }
        return hitIds;
    }

    /** A bare number, as models copy it from a hit's messageId; null when the reference is not one. */
    private Long bareMessageId(String hitId) {
        return !hitId.isEmpty() && hitId.chars().allMatch(Character::isDigit) ? parseId(hitId) : null;
    }

    private static String nothingFetched(List<Map<String, Object>> skipped) {
        StringBuilder message = new StringBuilder("No source was fetched:");
        for (Map<String, Object> skip : skipped) {
            message.append(' ').append(skip.get("hitId")).append(" (").append(skip.get("reason")).append(')');
        }
        return message.append(". Pass the hitId of a hit: msg-* from context.search_exact, dg-* from "
                + "context.search_digests.").toString();
    }

    private Long parseId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> skipEntry(String hitId, String reason) {
        Map<String, Object> skip = new LinkedHashMap<>();
        skip.put("hitId", hitId);
        skip.put("reason", reason);
        return skip;
    }

    private JsonNode buildSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("hitIds");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode hitIds = properties.putObject("hitIds");
        hitIds.put("type", "array");
        hitIds.put("description", "hitId values: dg-* from context.search_digests, msg-* from "
                + "context.search_exact or fetched sources; a bare number is read as a message id.");
        hitIds.put("minItems", 1);
        hitIds.put("maxItems", MAX_HIT_IDS);
        hitIds.putObject("items").put("type", "string").put("minLength", 1);
        ObjectNode include = properties.putObject("include");
        include.put("type", "array");
        include.put("description", "What to include per hit (default: all).");
        include.putObject("items").put("type", "string").putArray("enum")
                .add("USER_MESSAGE").add("ASSISTANT_MESSAGE").add("SELECTED_CODE_BLOCKS");
        ObjectNode maxTokens = properties.putObject("maxTokens");
        maxTokens.put("type", "integer");
        maxTokens.put("description", "Total content budget across all fetched sources (default 4000, max " + fetchBudgetTokens + ").");
        maxTokens.put("minimum", 500);
        maxTokens.put("maximum", fetchBudgetTokens);
        return schema;
    }
}
