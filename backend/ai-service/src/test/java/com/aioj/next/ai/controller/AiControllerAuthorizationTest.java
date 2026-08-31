package com.aioj.next.ai.controller;

import com.aioj.next.contract.ai.ProblemDraftApprovalRequest;
import com.aioj.next.contract.ai.ProblemDraftAgentTurnRequest;
import com.aioj.next.contract.ai.ProblemDraftRefineRequest;
import com.aioj.next.contract.ai.ProblemDraftRegenerateRequest;
import com.aioj.next.contract.ai.ProblemDraftRejectRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiControllerAuthorizationTest {

    private static final String TEACHER_OR_ADMIN = "hasAnyRole('TEACHER','ADMIN')";

    @Test
    void problem_draft_management_endpoints_allow_teacher_and_admin() throws Exception {
        assertPreAuthorize("generateDraft", TEACHER_OR_ADMIN, ProblemDraftRequest.class);
        assertPreAuthorize("listDrafts", TEACHER_OR_ADMIN, long.class, long.class, String.class, String.class, Long.class, String.class, String.class);
        assertPreAuthorize("draft", TEACHER_OR_ADMIN, Long.class);
        assertPreAuthorize("refineDraft", TEACHER_OR_ADMIN, Long.class, ProblemDraftRefineRequest.class, String.class);
        assertPreAuthorize("regenerateDraft", TEACHER_OR_ADMIN, Long.class, ProblemDraftRegenerateRequest.class);
        assertPreAuthorize("createProblemDraftRegenerationJob", TEACHER_OR_ADMIN, Long.class, ProblemDraftRegenerateRequest.class);
        assertPreAuthorize("approveDraft", TEACHER_OR_ADMIN, Long.class, ProblemDraftApprovalRequest.class, String.class);
        assertPreAuthorize("rejectDraft", TEACHER_OR_ADMIN, Long.class, ProblemDraftRejectRequest.class);
        assertPreAuthorize("archiveDraft", TEACHER_OR_ADMIN, Long.class);
        assertPreAuthorize("restoreDraft", TEACHER_OR_ADMIN, Long.class);
        assertPreAuthorize("deleteDraft", TEACHER_OR_ADMIN, Long.class);
    }

    @Test
    void contest_assistance_statistics_endpoints_allow_teacher_and_admin() throws Exception {
        assertPreAuthorize("contestAiAssistanceStatistics", TEACHER_OR_ADMIN, Long.class, Long.class);
        assertPreAuthorize("contestAiAssistanceStatisticsConversations", TEACHER_OR_ADMIN,
                Long.class, Long.class, Long.class);
        assertPreAuthorize("contestAiAssistanceStatisticsMessages", TEACHER_OR_ADMIN,
                Long.class, Long.class, String.class, Long.class);
    }

    @Test
    void problemDraftWorkspaceRoutesStayUnderGatewayAiPrefix() throws Exception {
        assertGetMapping("problemDraftWorkspace", "/ai/admin/problem-draft-workspaces/{workspaceId}",
                String.class);
        assertGetMapping("problemDraftWorkspaceMessages",
                "/ai/admin/problem-draft-workspaces/{workspaceId}/messages", String.class, long.class, long.class);
        assertGetMapping("problemDraftWorkspaceAgentRuns",
                "/ai/admin/problem-draft-workspaces/{workspaceId}/agent-runs", String.class, Long.class);
        assertGetMapping("problemDraftWorkspaceToolCalls",
                "/ai/admin/problem-draft-workspaces/{workspaceId}/tool-calls", String.class, Long.class);
        Method turn = AiController.class.getDeclaredMethod("continueProblemDraftAgent",
                String.class, ProblemDraftAgentTurnRequest.class);
        assertEquals("/ai/admin/problem-draft-workspaces/{workspaceId}/turns",
                turn.getAnnotation(PostMapping.class).value()[0]);
    }

    private static void assertPreAuthorize(String methodName, String expected, Class<?>... parameterTypes) throws Exception {
        Method method = AiController.class.getDeclaredMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertEquals(expected, annotation.value());
    }

    private static void assertGetMapping(String methodName, String expected, Class<?>... parameterTypes)
            throws Exception {
        Method method = AiController.class.getDeclaredMethod(methodName, parameterTypes);
        assertEquals(expected, method.getAnnotation(GetMapping.class).value()[0]);
    }
}
