package com.aioj.next.ai.agent.context;

import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiAgentRunEntity;
import com.aioj.next.ai.persistence.entity.AiContextManifestEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.mapper.AiAgentRunMapper;
import com.aioj.next.ai.persistence.mapper.AiContextManifestMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse.ContextBudgetReport;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse.ContextBuildReport;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse.ContextSection;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse.RecentMessage;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse.TokenEstimate;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse.Warning;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Context-debug view of a conversation, built from what the agent runtime
 * recorded for its latest model call (ai_context_manifests, plan Q10). It
 * replaces the retired legacy context service. A manifest keeps section types,
 * token estimates and trust levels but no content, so section previews are
 * empty; the fields only the legacy pipeline produced (conversation state,
 * clarifications, summary segments) come back empty too.
 */
@Service
public class ContextDebugService {
    static final int RECENT_MESSAGE_LIMIT = 12;
    static final int RUN_LOOKBACK = 5;
    static final int PREVIEW_CHARS = 160;

    private static final Logger log = LoggerFactory.getLogger(ContextDebugService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final AiConversationService conversationService;
    private final AiAgentRunMapper runMapper;
    private final AiContextManifestMapper manifestMapper;
    private final AiMessageMapper messageMapper;
    private final AiAssistantResponseNormalizer responseNormalizer;
    private final ObjectMapper objectMapper;

    public ContextDebugService(AiConversationService conversationService,
                               AiAgentRunMapper runMapper,
                               AiContextManifestMapper manifestMapper,
                               AiMessageMapper messageMapper,
                               AiAssistantResponseNormalizer responseNormalizer,
                               ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.runMapper = runMapper;
        this.manifestMapper = manifestMapper;
        this.messageMapper = messageMapper;
        this.responseNormalizer = responseNormalizer;
        this.objectMapper = objectMapper;
    }

    public AiConversationContextDebugResponse contextDebug(Long userId, String conversationId) {
        conversationService.getOwnedConversation(userId, conversationId);
        List<RecentMessage> recentMessages = recentMessages(conversationId);
        AiContextManifestEntity manifest = latestManifest(userId, conversationId);
        if (manifest == null) {
            return new AiConversationContextDebugResponse(conversationId, String.valueOf(userId), Map.of(),
                    recentMessages, List.of(), List.of(), List.of(), "", List.of(), Map.of(),
                    ContextBuildReport.empty(), new TokenEstimate(0, 0, 0, 0, 0),
                    List.of(new Warning("info", "NO_MANIFEST",
                            "No model call in this conversation has recorded a context manifest yet.")));
        }

        List<ContextSection> sections = sections(manifest);
        Map<String, Integer> sourceSummary = new LinkedHashMap<>();
        Map<String, Integer> tokensBySection = new LinkedHashMap<>();
        int total = 0;
        int required = 0;
        int requiredCount = 0;
        for (ContextSection section : sections) {
            sourceSummary.merge(section.type(), 1, Integer::sum);
            tokensBySection.merge(section.type(), section.estimatedTokens(), Integer::sum);
            total += section.estimatedTokens();
            if (section.required()) {
                required += section.estimatedTokens();
                requiredCount++;
            }
        }
        List<String> manifestWarnings = warnings(manifest);
        ContextBudgetReport budget = new ContextBudgetReport(nullToEmpty(manifest.getModel()), 0, 0, 0, total, total,
                false, List.of(), List.of(), tokensBySection, manifestWarnings);
        ContextBuildReport report = new ContextBuildReport(sections, sourceSummary, total, required, total - required,
                requiredCount, sections.size() - requiredCount, budget);
        int recentTurnTokens = tokensBySection.getOrDefault(ContextSectionType.RECENT_TURNS.name(), 0);
        int memoryTokens = tokensBySection.getOrDefault(ContextSectionType.BOOTSTRAP_CONTEXT.name(), 0)
                + tokensBySection.getOrDefault(ContextSectionType.RETRIEVED_EVIDENCE.name(), 0);
        TokenEstimate tokenEstimate = new TokenEstimate(recentTurnTokens, 0, 0, memoryTokens, total);
        List<Warning> warnings = manifestWarnings.stream()
                .map(warning -> new Warning("warn", warning, warning))
                .toList();
        return new AiConversationContextDebugResponse(conversationId, String.valueOf(userId), Map.of(),
                recentMessages, List.of(), List.of(), List.of(), preview(manifest, sections), sections,
                sourceSummary, report, tokenEstimate, warnings);
    }

    /** The newest manifest of the newest runs in the conversation; a run that failed before calling the model has none. */
    private AiContextManifestEntity latestManifest(Long userId, String conversationId) {
        List<AiAgentRunEntity> runs = runMapper.selectList(new QueryWrapper<AiAgentRunEntity>()
                .eq("conversation_id", conversationId)
                .eq("user_id", userId)
                .orderByDesc("started_at")
                .last("LIMIT " + RUN_LOOKBACK));
        for (AiAgentRunEntity run : runs) {
            AiContextManifestEntity manifest = manifestMapper.selectOne(new QueryWrapper<AiContextManifestEntity>()
                    .eq("agent_run_id", run.getId())
                    .orderByDesc("call_seq")
                    .last("LIMIT 1"));
            if (manifest != null) {
                return manifest;
            }
        }
        return null;
    }

    private List<RecentMessage> recentMessages(String conversationId) {
        List<AiMessageEntity> newestFirst = messageMapper.selectList(new QueryWrapper<AiMessageEntity>()
                .eq("conversation_id", conversationId)
                .orderByDesc("created_at")
                .last("LIMIT " + RECENT_MESSAGE_LIMIT));
        List<RecentMessage> messages = new ArrayList<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            AiMessageEntity message = newestFirst.get(i);
            messages.add(new RecentMessage(String.valueOf(message.getId()), message.getRole(),
                    contentPreview(message), message.getCreatedAt() == null
                    ? null : message.getCreatedAt().atZone(ZONE).toInstant()));
        }
        return messages;
    }

    private String contentPreview(AiMessageEntity message) {
        if (message.getContent() == null || message.getContent().isBlank()) {
            return "";
        }
        String visible = responseNormalizer.normalizeVisibleContent(message.getRole(), message.getContent())
                .replaceAll("\\s+", " ")
                .trim();
        return visible.length() <= PREVIEW_CHARS ? visible : visible.substring(0, PREVIEW_CHARS);
    }

    private List<ContextSection> sections(AiContextManifestEntity manifest) {
        List<ContextSection> sections = new ArrayList<>();
        JsonNode array = readJson(manifest.getSectionsJson());
        if (array == null || !array.isArray()) {
            return sections;
        }
        int index = 0;
        for (JsonNode node : array) {
            String type = node.path("type").asString("");
            String trust = node.path("trust").asString("");
            boolean atomic = node.path("atomic").asBoolean(false);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("trust", trust);
            metadata.put("atomic", atomic);
            if (node.has("messageCount")) {
                metadata.put("messageCount", node.path("messageCount").asInt(0));
            }
            sections.add(new ContextSection("manifest-" + index, type, type, index, "manifest", trust,
                    node.path("tokenEstimate").asInt(0), atomic, "", metadata));
            index++;
        }
        return sections;
    }

    private List<String> warnings(AiContextManifestEntity manifest) {
        JsonNode array = readJson(manifest.getWarningsJson());
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        for (JsonNode node : array) {
            String warning = node.asString("");
            if (!warning.isBlank()) {
                warnings.add(warning);
            }
        }
        return warnings;
    }

    /** One line per section plus the call that recorded them; never content. */
    private String preview(AiContextManifestEntity manifest, List<ContextSection> sections) {
        StringBuilder preview = new StringBuilder()
                .append("turn ").append(nullToEmpty(manifest.getTurnId()))
                .append(" | run ").append(manifest.getAgentRunId())
                .append(" | call ").append(manifest.getCallSeq())
                .append(" | model ").append(nullToEmpty(manifest.getModel()))
                .append(" | prompt ").append(nullToEmpty(manifest.getPromptVersion()))
                .append('\n')
                .append("provider input tokens ").append(manifest.getInputTokens() == null ? "-" : manifest.getInputTokens())
                .append(", cache hit ").append(manifest.getCacheHitTokens() == null ? "-" : manifest.getCacheHitTokens());
        for (ContextSection section : sections) {
            preview.append('\n').append(section.type()).append(' ').append(section.estimatedTokens())
                    .append(" tokens ").append(section.sensitivity());
            if (section.required()) {
                preview.append(" atomic");
            }
        }
        return preview.toString();
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (RuntimeException ex) {
            log.warn("Context manifest JSON could not be read: {}", ex.toString());
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
