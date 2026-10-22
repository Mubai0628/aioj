package com.aioj.next.problem.domain;

import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemCreateRequest;
import com.aioj.next.contract.problem.ProblemStandardSolutionPayload;
import com.aioj.next.contract.problem.ProblemUpdateRequest;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.contract.problem.TestCaseDto;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.ProblemSolutionEntity;
import com.aioj.next.problem.persistence.entity.ProblemTestcaseGeneratorEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.ProblemSolutionMapper;
import com.aioj.next.problem.persistence.mapper.ProblemTestCaseMapper;
import com.aioj.next.problem.persistence.mapper.ProblemTestcaseGeneratorMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemCatalogAiImportTest {
    @Mock private ProblemMapper problemMapper;
    @Mock private ProblemTestCaseMapper testCaseMapper;
    @Mock private ProblemSolutionMapper solutionMapper;
    @Mock private ProblemTestcaseGeneratorMapper testcaseGeneratorMapper;
    @Mock private OperationAuditService auditService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ContestProblemVisibilityService visibilityService;

    private ProblemCatalog catalog;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProblemEntity.class);
        catalog = new ProblemCatalog(problemMapper, testCaseMapper, solutionMapper, testcaseGeneratorMapper,
                new ObjectMapper(), auditService, jdbcTemplate, visibilityService);
        SecurityContextHolder.clearContext();
    }

    @Test
    void createTargetForcesPrivateAndUsesActorWithoutSecurityImpersonation() {
        doAnswer(invocation -> {
            ProblemEntity entity = invocation.getArgument(0);
            entity.setId(701L);
            return 1;
        }).when(problemMapper).insert(any(ProblemEntity.class));

        ProblemEntity created = catalog.createAiDraftImportTarget(createRequest(), 88L, "workspace-1", 31L,
                "HIGH_DIFFICULTY_AI", "AGENT_VERIFIED");

        assertEquals(ProblemVisibility.PRIVATE, created.getVisibility());
        assertTrue(Boolean.TRUE.equals(created.getAiGenerated()));
        assertEquals(88L, created.getCreatedBy());
        assertEquals("workspace-1", created.getAiOriginWorkspaceId());
        assertEquals(31L, created.getAiOriginDraftId());
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        ArgumentCaptor<ProblemSolutionEntity> solution = ArgumentCaptor.forClass(ProblemSolutionEntity.class);
        verify(solutionMapper).insert(solution.capture());
        assertEquals(88L, solution.getValue().getCreatedBy());
        ArgumentCaptor<ProblemTestcaseGeneratorEntity> generator =
                ArgumentCaptor.forClass(ProblemTestcaseGeneratorEntity.class);
        verify(testcaseGeneratorMapper).insert(generator.capture());
        assertEquals(88L, generator.getValue().getCreatedBy());
    }

    @Test
    void prepareExistingChangesOnlyImportProvenance() {
        ProblemEntity existing = existingProblem();
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        ProblemEntity prepared = catalog.prepareExistingAiDraftImport(701L, "workspace-1", 32L,
                "STANDARD", "AGENT_VERIFIED");

        assertEquals("old title", prepared.getTitle());
        assertEquals("old statement", prepared.getStatement());
        assertEquals(ProblemVisibility.PUBLIC, prepared.getVisibility());
        assertEquals(32L, prepared.getAiOriginDraftId());
        verify(problemMapper).updateById(existing);
    }

    @Test
    void claimLegacyTargetChangesOnlyServerProvenance() {
        ProblemEntity legacy = existingProblem();
        legacy.setAiGenerated(false);
        legacy.setAiOriginWorkspaceId(null);
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(legacy);
        when(problemMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        ProblemEntity claimed = catalog.claimAiDraftImportTarget(701L, "workspace-legacy");

        assertEquals("old title", claimed.getTitle());
        assertEquals("old statement", claimed.getStatement());
        assertEquals(ProblemVisibility.PUBLIC, claimed.getVisibility());
        assertEquals("workspace-legacy", claimed.getAiOriginWorkspaceId());
        assertTrue(Boolean.TRUE.equals(claimed.getAiGenerated()));
    }

    @Test
    void commitUsesExplicitActorForReplacementArtifactsWithoutSecurityContext() {
        ProblemEntity existing = existingProblem();
        existing.setAiOriginDraftId(32L);
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        ProblemEntity committed = catalog.commitAiDraftImport(701L, updateRequest(), 99L,
                "workspace-1", 32L, "HIGH_DIFFICULTY_AI", "MANUAL_UNVERIFIED", ProblemVisibility.PRIVATE);

        assertEquals("new title", committed.getTitle());
        assertEquals("new statement", committed.getStatement());
        assertEquals(ProblemVisibility.PRIVATE, committed.getVisibility());
        assertEquals("MANUAL_UNVERIFIED", committed.getAiTestcaseVerificationMode());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        ArgumentCaptor<ProblemSolutionEntity> solution = ArgumentCaptor.forClass(ProblemSolutionEntity.class);
        verify(solutionMapper).insert(solution.capture());
        assertEquals(99L, solution.getValue().getCreatedBy());
        ArgumentCaptor<ProblemTestcaseGeneratorEntity> generator =
                ArgumentCaptor.forClass(ProblemTestcaseGeneratorEntity.class);
        verify(testcaseGeneratorMapper).insert(generator.capture());
        assertEquals(99L, generator.getValue().getCreatedBy());
    }

    private ProblemCreateRequest createRequest() {
        return new ProblemCreateRequest("new title", Difficulty.HARD, "new statement", List.of("dp"),
                List.of(new TestCaseDto("1\n", "1\n", true)), 1000, 262144, "notes", null, null,
                List.of(new ProblemStandardSolutionPayload("cpp", "int main(){}")), null,
                "print('generator')", ProblemVisibility.PUBLIC);
    }

    private ProblemUpdateRequest updateRequest() {
        return new ProblemUpdateRequest("new title", Difficulty.HARD, "new statement", List.of("dp"),
                List.of(new TestCaseDto("2\n", "2\n", true)), 1500, 262144, "new notes", null, null,
                List.of(new ProblemStandardSolutionPayload("cpp", "int main(){return 0;}")), null,
                "print('new generator')", ProblemVisibility.PUBLIC);
    }

    private ProblemEntity existingProblem() {
        ProblemEntity entity = new ProblemEntity();
        entity.setId(701L);
        entity.setTitle("old title");
        entity.setStatement("old statement");
        entity.setDifficulty(Difficulty.EASY);
        entity.setVisibility(ProblemVisibility.PUBLIC);
        entity.setAiGenerated(true);
        entity.setAiOriginWorkspaceId("workspace-1");
        entity.setAiOriginDraftId(31L);
        entity.setDeleted(false);
        return entity;
    }
}
