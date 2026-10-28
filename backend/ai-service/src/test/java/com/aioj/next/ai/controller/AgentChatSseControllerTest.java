package com.aioj.next.ai.controller;

import com.aioj.next.ai.agent.AgentChatFacade;
import com.aioj.next.ai.agent.runtime.TurnEventBuffer;
import com.aioj.next.ai.agent.context.ContextDebugService;
import com.aioj.next.ai.domain.AccountImportParseService;
import com.aioj.next.ai.domain.AiChatContext;
import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiLearningProfileService;
import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.ProblemDraftStore;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryDebugService;
import com.aioj.next.ai.domain.memory.AiMemoryReviewService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.error.GlobalExceptionHandler;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 SSE contract of the Agent Core V3 pipeline: meta → (context only when
 * non-empty) → message → done, plus the error and resume paths. Driven against
 * a mocked {@link AgentChatFacade} — the runtime itself is covered by
 * {@code AgentRuntimeTest}/{@code TurnCoordinatorTest}.
 */
@ExtendWith(MockitoExtension.class)
class AgentChatSseControllerTest {

    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-agent-sse";

    @Mock
    private AgentChatFacade agentChatFacade;
    @Mock
    private AiQuotaService aiQuotaService;
    @Mock
    private AccountImportParseService accountImportParseService;
    @Mock
    private AiConversationService aiConversationService;
    @Mock
    private ContextDebugService contextDebugService;
    @Mock
    private AiMemoryService aiMemoryService;
    @Mock
    private AiLearningProfileService aiLearningProfileService;
    @Mock
    private AiMemoryCandidateService aiMemoryCandidateService;
    @Mock
    private AiMemoryDebugService aiMemoryDebugService;
    @Mock
    private AiMemoryReviewService aiMemoryReviewService;
    @Mock
    private ProblemDraftStore problemDraftStore;
    @Mock
    private AiAssistantResponseNormalizer responseNormalizer;

    private final ObjectMapper objectMapper = JsonMapper.builderWithJackson2Defaults().build();
    private MockMvc mockMvc;
    private AiController controller;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        controller = new AiController(
                agentChatFacade,
                aiQuotaService,
                accountImportParseService,
                aiConversationService,
                contextDebugService,
                aiMemoryService,
                aiLearningProfileService,
                aiMemoryCandidateService,
                aiMemoryDebugService,
                aiMemoryReviewService,
                problemDraftStore,
                responseNormalizer,
                objectMapper,
                Runnable::run
        );
        meters = new SimpleMeterRegistry();
        controller.setMeterRegistry(meters);
        controller.setPseudoStreamReplayer(new com.aioj.next.ai.agent.runtime.PseudoStreamReplayer(
                new com.aioj.next.ai.config.AiProperties(), objectMapper));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        authenticate(USER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void streamEmitsMetaMessageDoneWithoutContextEventWhenContextIsEmpty() throws Exception {
        when(agentChatFacade.start(eq(USER_ID), any(AiChatRequest.class)))
                .thenReturn(handle("turn-1", completedResult("你好，我是助教。")));

        String body = performStream("client-9");
        Map<String, String> events = events(body);

        assertThat(events).containsKeys("meta", "message", "done");
        assertThat(events).doesNotContainKey("context");
        assertThat(events).doesNotContainKey("clarification");
        assertThat(events).doesNotContainKey("memory");
        Map<String, Object> meta = readJson(events.get("meta"));
        assertThat(meta).containsEntry("turnId", "turn-1");
        assertThat(meta).containsEntry("clientMessageId", "client-9");
        Map<String, Object> message = readJson(events.get("message"));
        assertThat(message.get("contentMarkdown")).isEqualTo("你好，我是助教。");
        Map<String, Object> done = readJson(events.get("done"));
        assertThat(done).containsEntry("turnId", "turn-1");
    }

    @Test
    void streamEmitsErrorEventWhenTurnFails() throws Exception {
        CompletableFuture<AgentChatFacade.TurnResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "AI provider call failed"));
        when(agentChatFacade.start(eq(USER_ID), any(AiChatRequest.class)))
                .thenReturn(handle("turn-2", failed));

        String body = performStream("client-10");
        assertThat(body).contains("event: error");
        assertThat(body).contains("AI provider call failed");
    }

    @Test
    void streamResumeAttachesToExistingTurnInsteadOfStarting() throws Exception {
        when(agentChatFacade.resume(eq(USER_ID), eq("turn-1"), any(AiChatRequest.class)))
                .thenReturn(handle("turn-1", completedResult("第一次回答的复放。")));

        MvcResult started = mockMvc.perform(post("/ai/chat/stream?resumeTurnId=turn-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatRequest("client-9"))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("第一次回答的复放。");
        verify(agentChatFacade, never()).start(eq(USER_ID), any(AiChatRequest.class));
    }

    @Test
    void pseudoStreamTurnReplaysDeltaEventsBeforeFullMessage() throws Exception {
        // P3-5 (Q2): an L4-verified restricted turn is buffered server-side, then
        // replayed as delta slices; the full message event still follows unchanged.
        String content = "答".repeat(700);
        when(agentChatFacade.start(eq(USER_ID), any(AiChatRequest.class)))
                .thenReturn(handle("turn-1", completedResult(content, true)));

        String body = performStream("client-9");

        int deltaCount = body.split("event: delta", -1).length - 1;
        assertThat(deltaCount).isEqualTo(3);
        assertThat(body.indexOf("event: delta")).isLessThan(body.indexOf("event: message"));
        assertThat(body.lastIndexOf("event: delta")).isLessThan(body.indexOf("event: message"));
        StringBuilder reassembled = new StringBuilder();
        Matcher matcher = Pattern.compile("event: delta\\ndata: (.*?)\\n\\n", Pattern.DOTALL).matcher(body);
        while (matcher.find()) {
            reassembled.append(readJson(matcher.group(1)).get("text"));
        }
        assertThat(reassembled.toString()).isEqualTo(content);
        Map<String, Object> message = readJson(events(body).get("message"));
        assertThat(message.get("contentMarkdown")).isEqualTo(content);
    }

    @Test
    void progressWaitsForTheRecheckAndContainsOnlySafeToolMetadata() throws Exception {
        TurnEventBuffer progress = new TurnEventBuffer(8, System.nanoTime());
        CompletableFuture<AgentChatFacade.TurnResult> pending = new CompletableFuture<>();
        pending.whenComplete((value, failure) -> progress.close());
        AgentChatFacade.TurnHandle old = handle("turn-live", pending);
        AgentChatFacade.TurnHandle live = new AgentChatFacade.TurnHandle(old.conversation(), old.context(), old.user(),
                old.assistant(), old.result(), old.turnId(), progress);
        when(agentChatFacade.start(eq(USER_ID), any(AiChatRequest.class))).thenReturn(live);
        var response = controller.stream(chatRequest("client-9"), null);
        CountDownLatch sawDelta = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void flush() {
                if (toString(StandardCharsets.UTF_8).contains("event: delta")) {
                    sawDelta.countDown();
                }
            }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                response.getBody().writeTo(output);
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        writer.start();
        try {
            progress.beginStep(1);
            progress.text("先看样例");
            assertThat(sawDelta.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(pending).isNotDone();
            progress.toolStarted("code.run_samples");
            progress.toolFinished("code.run_samples", "SUCCESS", 12);
            progress.beginStep(2);
            progress.text("最终回复");
            pending.complete(completedResult("最终回复").join());
            assertThat(sawDelta.await(3, TimeUnit.SECONDS)).isTrue();
            writer.join(3000);

            assertThat(writer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            String body = output.toString(StandardCharsets.UTF_8);
            assertThat(body.indexOf("event: delta")).isLessThan(body.indexOf("event: message"));
            assertThat(body).contains("\"step\":1", "\"phase\":\"started\"", "\"phase\":\"finished\"",
                    "code.run_samples", "\"durationMs\":12");
            assertThat(body).doesNotContain("arguments", "reasoning_content", "modelPayload");
            assertThat(meters.get("aioj.ai.chat.first.delta").timer().count()).isEqualTo(1);
        } finally {
            pending.completeExceptionally(new IllegalStateException("test cleanup"));
            progress.close();
            writer.join(3000);
        }
    }

    @Test
    void newlyRestrictedTurnDiscardsAllSpeculativeProgressAndReplaysOnlySafeAnswer() throws Exception {
        TurnEventBuffer progress = new TurnEventBuffer(8, System.nanoTime());
        progress.beginStep(1);
        progress.text("不得泄露的原始草稿");
        progress.toolStarted("code.run_samples");
        progress.toolFinished("code.run_samples", "SUCCESS", 12);
        progress.close();
        AgentChatFacade.TurnHandle old = handle("turn-1", completedResult("安全拒答", true));
        when(agentChatFacade.start(eq(USER_ID), any(AiChatRequest.class))).thenReturn(
                new AgentChatFacade.TurnHandle(old.conversation(), old.context(), old.user(), old.assistant(),
                        old.result(), old.turnId(), progress));

        String body = performStream("client-9");

        assertThat(body).contains("event: delta", "安全拒答", "event: message");
        assertThat(body).doesNotContain("不得泄露的原始草稿", "event: tool", "code.run_samples");
    }

    @Test
    void failedRecheckNeverReleasesSpeculativeProgress() throws Exception {
        TurnEventBuffer progress = new TurnEventBuffer(8, System.nanoTime());
        progress.beginStep(1);
        progress.text("不得泄露的草稿");
        progress.toolStarted("code.run_samples");
        progress.close();
        CompletableFuture<AgentChatFacade.TurnResult> failed = CompletableFuture.failedFuture(
                new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "比赛策略确认暂时不可用，请稍后重试"));
        AgentChatFacade.TurnHandle old = handle("turn-1", failed);
        when(agentChatFacade.start(eq(USER_ID), any(AiChatRequest.class))).thenReturn(
                new AgentChatFacade.TurnHandle(old.conversation(), old.context(), old.user(), old.assistant(),
                        old.result(), old.turnId(), progress));

        String body = performStream("client-9");

        assertThat(body).contains("event: error");
        assertThat(body).doesNotContain("event: delta", "event: tool", "不得泄露的草稿", "code.run_samples");
    }

    @Test
    void progressOverflowFallsBackToTheCompleteFinalMessage() throws Exception {
        TurnEventBuffer progress = new TurnEventBuffer(2, System.nanoTime());
        progress.beginStep(1);
        progress.text("甲".repeat(TurnEventBuffer.MAX_BUFFERED_TEXT_CHARS));
        progress.text("乙");
        progress.close();
        AgentChatFacade.TurnHandle old = handle("turn-overflow", completedResult("完整最终答复"));
        when(agentChatFacade.start(eq(USER_ID), any(AiChatRequest.class))).thenReturn(
                new AgentChatFacade.TurnHandle(old.conversation(), old.context(), old.user(), old.assistant(),
                        old.result(), old.turnId(), progress));

        String body = performStream("client-overflow");

        assertThat(body).contains("event: message", "完整最终答复", "event: done");
        assertThat(body).doesNotContain("event: delta", "event: tool");
    }

    @Test
    void nonPseudoStreamTurnEmitsNoDeltaEvents() throws Exception {
        when(agentChatFacade.start(eq(USER_ID), any(AiChatRequest.class)))
                .thenReturn(handle("turn-1", completedResult("普通回答。")));

        String body = performStream("client-9");

        assertThat(body).doesNotContain("event: delta");
        assertThat(body).contains("普通回答。");
    }

    private AgentChatFacade.TurnHandle handle(String turnId, CompletableFuture<AgentChatFacade.TurnResult> result) {
        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        return new AgentChatFacade.TurnHandle(
                conversation,
                new AiChatContext("", "", "", "", ""),
                message(100L, "user", "你好", "client-9", "COMPLETED"),
                message(200L, "assistant", "", "client-9:assistant", "RUNNING"),
                result,
                turnId
        );
    }

    private CompletableFuture<AgentChatFacade.TurnResult> completedResult(String content) {
        return completedResult(content, false);
    }

    private CompletableFuture<AgentChatFacade.TurnResult> completedResult(String content, boolean pseudoStream) {
        AiCompletion completion = new AiCompletion(content, "deepseek", "deepseek-v4-pro", 30, 12);
        AgentChatFacade.TurnResult result = new AgentChatFacade.TurnResult(
                completion,
                new AiAssistantResponseNormalizer.NormalizedResponse(completion, Map.of(), Map.of(), List.of(), false),
                message(200L, "assistant", content, "client-9:assistant", "COMPLETED"),
                "qa",
                0,
                pseudoStream
        );
        return CompletableFuture.completedFuture(result);
    }

    private AiChatMessageResponse message(Long id, String role, String content, String clientMessageId, String status) {
        return new AiChatMessageResponse(id, CONVERSATION_ID, null, clientMessageId, role, content, null,
                status, null, Instant.now(), "COMPLETED".equals(status) ? Instant.now() : null);
    }

    private AiChatRequest chatRequest(String clientMessageId) {
        return new AiChatRequest(CONVERSATION_ID, null, "你好", null, null, null, null,
                clientMessageId, null, null, null);
    }

    private String performStream(String clientMessageId) throws Exception {
        MvcResult started = mockMvc.perform(post("/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatRequest(clientMessageId))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        return completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Map<String, String> events(String body) {
        Map<String, String> events = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("event: (\\w+)\\ndata: (.*?)\\n\\n", Pattern.DOTALL).matcher(body);
        while (matcher.find()) {
            events.put(matcher.group(1), matcher.group(2));
        }
        return events;
    }

    private Map<String, Object> readJson(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private void authenticate(Long userId) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "elvis", Set.of(Role.STUDENT));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
