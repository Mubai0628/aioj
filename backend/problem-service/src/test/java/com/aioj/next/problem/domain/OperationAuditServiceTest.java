package com.aioj.next.problem.domain;

import com.aioj.next.common.error.UserErrorFeedback;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.problem.persistence.entity.OperationAuditEventEntity;
import com.aioj.next.problem.persistence.mapper.OperationAuditEventMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationAuditServiceTest {
    @Mock
    private OperationAuditEventMapper auditEventMapper;

    private OperationAuditService service;

    @BeforeEach
    void setUp() {
        service = new OperationAuditService(auditEventMapper, new ObjectMapper());
        authenticate(99L, Role.ADMIN);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listReturnsReadableChineseActionName() {
        when(auditEventMapper.selectPage(any(), any())).thenReturn(page(List.of(event("SUBMISSION_CODE_ACCESS"))));

        var result = service.list(1, 20, null, null, null, null, null);

        assertEquals(1, result.total());
        assertEquals("SUBMISSION_CODE_ACCESS", result.records().get(0).action());
        assertEquals("查看提交源码", result.records().get(0).actionDisplayName());
    }

    @Test
    void unknownActionUsesSafeDisplayName() {
        when(auditEventMapper.selectPage(any(), any())).thenReturn(page(List.of(event("RAW_UNMAPPED_CONSTANT"))));

        var result = service.list(1, 20, null, null, null, null, null);

        assertEquals("RAW_UNMAPPED_CONSTANT", result.records().get(0).action());
        assertEquals("未识别审计操作", result.records().get(0).actionDisplayName());
    }

    @Test
    void listMapsAiProblemDraftLifecycleActionsToReadableNames() {
        when(auditEventMapper.selectPage(any(), any())).thenReturn(page(List.of(
                event("PROBLEM_DRAFT_GENERATION_JOB_COMPLETED"),
                event("PROBLEM_DRAFT_REGENERATION_JOB_FAILED"),
                event("PROBLEM_DRAFT_GENERATION_JOB_CREATED")
        )));

        var result = service.list(1, 20, null, null, null, null, null);

        assertEquals("AI 题目草稿生成完成", result.records().get(0).actionDisplayName());
        assertEquals("AI 题目草稿改写失败", result.records().get(1).actionDisplayName());
        assertNotEquals("未识别审计操作", result.records().get(2).actionDisplayName());
    }

    @Test
    void snowflakeIdsLeaveAsStringsEvenInsideTheSummary() {
        OperationAuditEventEntity event = event("SUBMISSION_CODE_ACCESS");
        event.setId(2070348621785022501L);
        event.setActorUserId(2070348621785022502L);
        event.setResourceId(2070348621785022503L);
        event.setContestRunId(2070348621785022504L);
        event.setSummaryJson("{\"auditLogId\":2070348621785022500,\"submissionIds\":[2070348621785022505,7]}");
        when(auditEventMapper.selectPage(any(), any())).thenReturn(page(List.of(event)));

        var result = service.list(1, 20, null, null, null, null, null);
        String json = new ObjectMapper().writeValueAsString(result);

        // Plan S6: the admin page parses summaryJson with a plain JSON.parse.
        assertEquals("{\"auditLogId\":\"2070348621785022500\",\"submissionIds\":[\"2070348621785022505\",7]}",
                result.records().get(0).summaryJson());
        assertFalse(Pattern.compile("[:\\[,]\\s*-?\\d{16,}").matcher(json).find(), json);
        assertEquals("2070348621785022502", result.records().get(0).actorUserId());
    }

    /**
     * Plan S7: the page gets an error key. A row written before the rule holds the failure's own text (here a
     * request line with the internal address); it is shown as a key, a row that already has one is left alone.
     */
    @Test
    void aStoredFailureTextReachesThePageAsAnErrorKey() {
        OperationAuditEventEntity leaking = event("AI_CONTEST_GUARD_DEGRADED");
        leaking.setSummaryJson("{\"error\":\"ResourceAccessException: I/O error on GET request for \\\"http://localhost:8202/"
                + "api/v1/internal/ai/users/7/running-participations\\\": null\",\"endpoint\":\"/api/v1/internal/ai/users/{userId}/running-participations\"}");
        OperationAuditEventEntity keyed = event("PROBLEM_DRAFT_GENERATION_JOB_COMPLETED");
        keyed.setSummaryJson("{\"errorKey\":\"agent.workerInterrupted\",\"error\":\"request.timeout\"}");
        when(auditEventMapper.selectPage(any(), any())).thenReturn(page(List.of(leaking, keyed, event("SUBMISSION_CODE_ACCESS"))));

        var result = service.list(1, 20, null, null, null, null, null);

        String shown = result.records().get(0).summaryJson();
        assertFalse(shown.contains("localhost") || shown.contains("8202") || shown.contains("Exception"), shown);
        assertTrue(UserErrorFeedback.isKey(new ObjectMapper().readTree(shown).get("error").asString()), shown);
        assertTrue(shown.contains("\"endpoint\":\"/api/v1/internal/ai/users/{userId}/running-participations\""), shown);
        assertEquals("{\"errorKey\":\"agent.workerInterrupted\",\"error\":\"request.timeout\"}", result.records().get(1).summaryJson());
        assertEquals("{\"auditLogId\":\"2070348621785022500\"}", result.records().get(2).summaryJson());
    }

    private Page<OperationAuditEventEntity> page(List<OperationAuditEventEntity> records) {
        Page<OperationAuditEventEntity> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    private OperationAuditEventEntity event(String action) {
        OperationAuditEventEntity event = new OperationAuditEventEntity();
        event.setId(101L);
        event.setActorUserId(99L);
        event.setAction(action);
        event.setResourceType("SUBMISSION");
        event.setResourceId(801L);
        event.setStatus("SUCCESS");
        event.setTraceId("trace-1");
        event.setSummaryJson("{\"auditLogId\":2070348621785022500}");
        event.setCreatedAt(Instant.parse("2026-06-26T01:00:00Z"));
        return event;
    }

    private void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "user" + userId, Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a",
                Set.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
