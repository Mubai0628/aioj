package com.aioj.next.ai.agent.retrieval.builtin;

import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextFetchSourcesToolTest {

    private final AiTurnDigestMapper digestMapper = mock(AiTurnDigestMapper.class);
    private final AiMessageMapper messageMapper = mock(AiMessageMapper.class);
    private final ContextFetchSourcesTool tool = new ContextFetchSourcesTool(
            digestMapper, messageMapper, new ObjectMapper(), new AiProperties());

    @Test
    void digestHitFetchesBothOriginalMessages() {
        when(digestMapper.selectOne(any(QueryWrapper.class))).thenReturn(digestRow());
        when(messageMapper.selectById(100L)).thenReturn(message(100L, 7L, "c-1", "user", "讲一下第二道异或题"));
        when(messageMapper.selectById(200L)).thenReturn(message(200L, 7L, "c-1", "assistant", "用异或前缀和：\n```cpp\nint main(){}\n```"));

        ToolResult<Object> result = tool.execute(context(), readTree("{\"hitIds\":[\"dg-11\"]}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        Map<String, Object> data = data(result);
        List<Map<String, Object>> sources = sources(data);
        assertThat(sources).hasSize(2);
        Map<String, Object> first = sources.get(0);
        assertThat(first.get("hitId")).isEqualTo("msg-100");
        assertThat(first.get("messageId")).isEqualTo("100");
        assertThat(first.get("role")).isEqualTo("user");
        assertThat(first.get("trustLevel")).isEqualTo("USER_PROVIDED");
        Map<String, Object> second = sources.get(1);
        assertThat(second.get("role")).isEqualTo("assistant");
        assertThat(second.get("trustLevel")).isEqualTo("MODEL_INFERRED");
        assertThat((List<Map<String, Object>>) second.get("codeBlocks")).hasSize(1);
    }

    @Test
    void userOnlyIncludeSkipsAssistantMessage() {
        when(digestMapper.selectOne(any(QueryWrapper.class))).thenReturn(digestRow());
        when(messageMapper.selectById(100L)).thenReturn(message(100L, 7L, "c-1", "user", "u"));

        ToolResult<Object> result = tool.execute(context(), readTree(
                "{\"hitIds\":[\"dg-11\"],\"include\":[\"USER_MESSAGE\"]}"));

        assertThat(sources(data(result))).hasSize(1);
    }

    @Test
    void crossUserMessageIsRejectedAsNotFound() {
        when(messageMapper.selectById(100L)).thenReturn(message(100L, 99L, "c-1", "user", "别人的消息"));

        ToolResult<Object> result = tool.execute(context(), readTree("{\"hitIds\":[\"msg-100\"]}"));

        assertThat(result.status()).isEqualTo(ToolStatus.NOT_FOUND);
        assertThat(result.errorCode()).isEqualTo("NO_SOURCE_FETCHED");
        assertThat(result.sources()).isEmpty();
        Map<String, Object> data = data(result);
        assertThat(sources(data)).isEmpty();
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) data.get("skipped");
        assertThat(skipped).hasSize(1);
        assertThat(skipped.get(0).get("reason")).isEqualTo("NOT_FOUND");
    }

    @Test
    void crossConversationSameUserMessageFetches() {
        // P4-1: the message sits in ANOTHER conversation of the same user — allowed.
        when(messageMapper.selectById(100L)).thenReturn(message(100L, 7L, "c-other", "user", "另一个会话的题面"));

        ToolResult<Object> result = tool.execute(context(), readTree("{\"hitIds\":[\"msg-100\"]}"));

        Map<String, Object> data = data(result);
        List<Map<String, Object>> sources = sources(data);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("content")).isEqualTo("另一个会话的题面");
    }

    @Test
    void crossConversationDigestHitResolvesToOriginalMessages() {
        // P4-1: a dg-* hit from another conversation of the same user dereferences fine.
        AiTurnDigestEntity otherConvDigest = digestRow();
        otherConvDigest.setConversationId("c-other");
        when(digestMapper.selectOne(any(QueryWrapper.class))).thenReturn(otherConvDigest);
        when(messageMapper.selectById(100L)).thenReturn(message(100L, 7L, "c-other", "user", "u"));
        when(messageMapper.selectById(200L)).thenReturn(message(200L, 7L, "c-other", "assistant", "a"));

        ToolResult<Object> result = tool.execute(context(), readTree("{\"hitIds\":[\"dg-11\"]}"));

        assertThat(sources(data(result))).hasSize(2);
    }

    @Test
    void crossConversationOtherUserMessageStillNotFound() {
        // The hard boundary is userId: another user's message in another conversation
        // must stay invisible even though the conversation check is relaxed.
        when(messageMapper.selectById(100L)).thenReturn(message(100L, 99L, "c-other", "user", "别人的"));

        ToolResult<Object> result = tool.execute(context(), readTree("{\"hitIds\":[\"msg-100\"]}"));

        assertThat(result.status()).isEqualTo(ToolStatus.NOT_FOUND);
        Map<String, Object> data = data(result);
        assertThat(sources(data)).isEmpty();
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) data.get("skipped");
        assertThat(skipped.get(0).get("reason")).isEqualTo("NOT_FOUND");
    }

    @Test
    void tokenBudgetTruncatesLongContent() {
        String huge = "x".repeat(20000);
        when(messageMapper.selectById(100L)).thenReturn(message(100L, 7L, "c-1", "user", huge));

        ToolResult<Object> result = tool.execute(context(), readTree(
                "{\"hitIds\":[\"msg-100\"],\"maxTokens\":1000}"));

        Map<String, Object> data = data(result);
        List<Map<String, Object>> sources = sources(data);
        assertThat(sources).hasSize(1);
        assertThat((String) sources.get(0).get("content")).endsWith("…[TRUNCATED]");
        assertThat(data.get("truncated")).isEqualTo(true);
    }

    @Test
    void aFetchThatGetsNothingFailsWithEachReasonAndAHint() {
        when(messageMapper.selectById(100L)).thenReturn(message(100L, 99L, "c-1", "user", "别人的消息"));

        ToolResult<Object> result = tool.execute(context(), readTree("{\"hitIds\":[\"???\",\"msg-100\"]}"));

        assertThat(result.status()).isEqualTo(ToolStatus.NOT_FOUND);
        assertThat(result.errorCode()).isEqualTo("NO_SOURCE_FETCHED");
        assertThat(result.errorMessage()).isEqualTo("No source was fetched: ??? (INVALID_REFERENCE) msg-100 (NOT_FOUND). "
                + "Pass the hitId of a hit: msg-* from context.search_exact, dg-* from context.search_digests.");
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) data(result).get("skipped");
        assertThat(skipped).extracting(skip -> skip.get("reason")).containsExactly("INVALID_REFERENCE", "NOT_FOUND");
    }

    @Test
    void aBareNumberIsReadAsAMessageId() {
        // B1 (2026-10-23): models pass the bare messageId a search hit shows.
        when(messageMapper.selectById(100L)).thenReturn(message(100L, 7L, "c-1", "user", "埋下的题面"));

        ToolResult<Object> result = tool.execute(context(), readTree("{\"hitIds\":[\"100\"]}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        List<Map<String, Object>> sources = sources(data(result));
        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.get("hitId")).isEqualTo("msg-100");
            assertThat(source.get("content")).isEqualTo("埋下的题面");
        });
        assertThat(result.sources()).extracting(ref -> ref.id()).containsExactly("100");
    }

    @Test
    void aPartlyValidFetchSucceedsAndListsWhatItSkipped() {
        when(messageMapper.selectById(100L)).thenReturn(message(100L, 7L, "c-1", "user", "u"));

        ToolResult<Object> result = tool.execute(context(), readTree(
                "{\"hitIds\":[\"msg-100\",\"99999999999999999999\"]}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        Map<String, Object> data = data(result);
        assertThat(sources(data)).hasSize(1);
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) data.get("skipped");
        assertThat(skipped).singleElement().satisfies(skip -> {
            assertThat(skip.get("hitId")).isEqualTo("99999999999999999999");
            assertThat(skip.get("reason")).isEqualTo("INVALID_REFERENCE");
        });
    }

    @Test
    void emptyHitIdsFailsSchema() {
        ToolResult<Object> result = tool.execute(context(), readTree("{\"hitIds\":[]}"));
        assertThat(result.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(7L, "c-1", "t-current", 5L, null, Set.of("AI_CHAT"), Instant.now(), "trace");
    }

    private tools.jackson.databind.JsonNode readTree(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResult<Object> result) {
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sources(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("sources");
    }

    private AiTurnDigestEntity digestRow() {
        AiTurnDigestEntity digest = new AiTurnDigestEntity();
        digest.setId(11L);
        digest.setTurnId("t-1");
        digest.setConversationId("c-1");
        digest.setUserId(7L);
        digest.setStructuredDigest("{\"source\":{\"userMessageId\":\"100\",\"assistantMessageId\":\"200\",\"sourceHash\":\"h\"}}");
        digest.setCreatedAt(LocalDateTime.now());
        return digest;
    }

    private AiMessageEntity message(Long id, Long userId, String conversationId, String role, String content) {
        AiMessageEntity message = new AiMessageEntity();
        message.setId(id);
        message.setUserId(userId);
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }
}
