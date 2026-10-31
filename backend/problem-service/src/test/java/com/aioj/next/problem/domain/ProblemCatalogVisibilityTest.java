package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiProblemRunContextRequest;
import com.aioj.next.contract.ai.AiProblemRunContextResponse;
import com.aioj.next.contract.problem.ProblemResponse;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.ProblemSolutionMapper;
import com.aioj.next.problem.persistence.mapper.ProblemTestCaseMapper;
import com.aioj.next.problem.persistence.mapper.ProblemTestcaseGeneratorMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemCatalogVisibilityTest {
    @Mock
    private ProblemMapper problemMapper;
    @Mock
    private ProblemTestCaseMapper testCaseMapper;
    @Mock
    private ProblemSolutionMapper solutionMapper;
    @Mock
    private ProblemTestcaseGeneratorMapper testcaseGeneratorMapper;
    @Mock
    private OperationAuditService auditService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ContestProblemVisibilityService visibilityService;

    private ProblemCatalog catalog;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProblemEntity.class);
        catalog = new ProblemCatalog(problemMapper, testCaseMapper, solutionMapper, testcaseGeneratorMapper,
                new ObjectMapper(), auditService, jdbcTemplate, visibilityService, event -> { });
        lenient().when(testCaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousReaderGetsNotFoundForPrivateProblem() {
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(privateProblem());
        when(visibilityService.isPrivate(any(ProblemEntity.class))).thenReturn(true);
        org.mockito.Mockito.lenient().when(visibilityService.isStaffViewer()).thenReturn(false);

        DomainException error = assertThrows(DomainException.class, () -> catalog.get(7L, null, null, false));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void staffReaderCanReadPrivateProblem() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityPrincipal(99L, "teacher", Set.of(Role.TEACHER)), "n/a"));
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(privateProblem());
        when(visibilityService.isPrivate(any(ProblemEntity.class))).thenReturn(true);
        when(visibilityService.isStaffViewer()).thenReturn(true);

        ProblemResponse response = catalog.get(7L, null, null, true);

        assertEquals(7L, response.id());
        assertEquals(ProblemVisibility.PRIVATE, response.visibility());
    }

    @Test
    void staffWithoutExplicitStaffViewGetsSameStudentExperience() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityPrincipal(99L, "teacher", Set.of(Role.TEACHER)), "n/a"));
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(privateProblem());
        when(visibilityService.isPrivate(any(ProblemEntity.class))).thenReturn(true);
        org.mockito.Mockito.lenient().when(visibilityService.isStaffViewer()).thenReturn(true);
        org.mockito.Mockito.lenient().when(visibilityService.canViewPrivateProblem(any(), any(), any(), any(), any())).thenReturn(false);

        DomainException error = assertThrows(DomainException.class, () -> catalog.get(7L, null, null, false));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void activeParticipantInsideRunWindowCanReadPrivateProblem() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityPrincipal(5L, "student", Set.of(Role.STUDENT)), "n/a"));
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(privateProblem());
        when(visibilityService.isPrivate(any(ProblemEntity.class))).thenReturn(true);
        org.mockito.Mockito.lenient().when(visibilityService.isStaffViewer()).thenReturn(false);
        when(visibilityService.canViewPrivateProblem(org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.eq(302L),
                org.mockito.ArgumentMatchers.eq(401L), org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(true);

        ProblemResponse response = catalog.get(7L, 302L, 401L, false);

        assertEquals(7L, response.id());
    }

    @Test
    void participantOutsideRunWindowStillGetsNotFound() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityPrincipal(5L, "student", Set.of(Role.STUDENT)), "n/a"));
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(privateProblem());
        when(visibilityService.isPrivate(any(ProblemEntity.class))).thenReturn(true);
        org.mockito.Mockito.lenient().when(visibilityService.isStaffViewer()).thenReturn(false);
        when(visibilityService.canViewPrivateProblem(any(), any(), any(), any(), any())).thenReturn(false);

        DomainException error = assertThrows(DomainException.class, () -> catalog.get(7L, 302L, 401L, false));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void staffListWithoutVisibilityParamFiltersToPublic() {
        when(visibilityService.isStaffViewer()).thenReturn(true);
        Page<ProblemEntity> resultPage = new Page<>(1, 20);
        resultPage.setRecords(List.of());
        ArgumentCaptor<LambdaQueryWrapper<ProblemEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(problemMapper.selectPage(any(Page.class), captor.capture())).thenReturn(resultPage);

        catalog.list(1, 20, null, null, null, null, null, null);

        assertEquals(true, captor.getValue().getSqlSegment().contains("visibility"));
    }

    @Test
    void staffListWithExplicitAllVisibilityShowsEverything() {
        when(visibilityService.isStaffViewer()).thenReturn(true);
        Page<ProblemEntity> resultPage = new Page<>(1, 20);
        resultPage.setRecords(List.of(privateProblem()));
        resultPage.setTotal(1);
        ArgumentCaptor<LambdaQueryWrapper<ProblemEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(problemMapper.selectPage(any(Page.class), captor.capture())).thenReturn(resultPage);

        PageResponse<ProblemResponse> response = catalog.list(1, 20, null, null, null, null, "ALL", null);

        assertEquals(1, response.records().size());
        assertEquals(ProblemVisibility.PRIVATE, response.records().get(0).visibility());
        assertEquals(false, captor.getValue().getSqlSegment().contains("visibility"));
    }

    @Test
    void studentListWithoutVisibilityParamFiltersToPublic() {
        when(visibilityService.isStaffViewer()).thenReturn(false);
        Page<ProblemEntity> resultPage = new Page<>(1, 20);
        resultPage.setRecords(List.of());
        ArgumentCaptor<LambdaQueryWrapper<ProblemEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(problemMapper.selectPage(any(Page.class), captor.capture())).thenReturn(resultPage);

        catalog.list(1, 20, null, null, null, null, null, null);

        assertEquals(true, captor.getValue().getSqlSegment().contains("visibility"));
    }

    /**
     * Plan S9: what a problem read carries (the page, and the AI's problem tool behind the same
     * response) comes from a query for sample cases only; hidden cases are never asked for.
     */
    @Test
    void aProblemReadAsksOnlyForSampleCases() {
        ProblemEntity problem = privateProblem();
        problem.setVisibility(ProblemVisibility.PUBLIC);
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(problem);
        when(visibilityService.isPrivate(any(ProblemEntity.class))).thenReturn(false);

        catalog.get(7L, null, null, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<com.aioj.next.problem.persistence.entity.ProblemTestCaseEntity>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(testCaseMapper).selectList(query.capture());
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.aioj.next.problem.persistence.entity.ProblemTestCaseEntity.class);
        assertEquals(true, query.getValue().getSqlSegment().contains("sample"));
        assertEquals(true, query.getValue().getParamNameValuePairs().containsValue(true));
    }

    /** Plan 15.5: a self-test run gets the public samples and the judge's limit for its language. */
    @Test
    void runContextOfAPublicProblemHasItsSamplesAndTheLimitForTheLanguage() {
        ProblemEntity problem = privateProblem();
        problem.setVisibility(ProblemVisibility.PUBLIC);
        problem.setPythonTimeLimitMultiplier(new java.math.BigDecimal("2.5"));
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(problem);
        when(visibilityService.isPrivate(any(ProblemEntity.class))).thenReturn(false);
        com.aioj.next.problem.persistence.entity.ProblemTestCaseEntity sample =
                new com.aioj.next.problem.persistence.entity.ProblemTestCaseEntity();
        sample.setInput("1 2\n");
        sample.setExpectedOutput("3\n");
        sample.setSample(true);
        when(testCaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sample));

        AiProblemRunContextResponse python = catalog.aiProblemRunContext(
                new AiProblemRunContextRequest(5L, 7L, "python", null, null));
        AiProblemRunContextResponse cpp = catalog.aiProblemRunContext(
                new AiProblemRunContextRequest(5L, 7L, "cpp", null, null));

        assertEquals(2500, python.timeLimitMillis());
        assertEquals(1000, cpp.timeLimitMillis());
        assertEquals(262144, python.memoryLimitKb());
        assertEquals(List.of(new com.aioj.next.contract.problem.TestCaseDto("1 2\n", "3\n", true)), python.samples());
        assertEquals(false, python.contestRun());
    }

    /** Plan 15.5: a private problem is only run by an active participant of a run that uses it. */
    @Test
    void runContextOfAPrivateProblemIsOnlyForAnActiveParticipant() {
        when(problemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(privateProblem());
        when(visibilityService.isPrivate(any(ProblemEntity.class))).thenReturn(true);
        lenient().when(visibilityService.canViewPrivateProblem(any(), any(), any(), any(), any())).thenReturn(false);
        lenient().when(visibilityService.canViewPrivateProblem(org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(302L), org.mockito.ArgumentMatchers.eq(401L),
                org.mockito.ArgumentMatchers.eq(7L), any(Instant.class))).thenReturn(true);

        assertEquals(true, catalog.aiProblemRunContext(new AiProblemRunContextRequest(5L, 7L, "cpp", 302L, 401L))
                .contestRun());
        for (AiProblemRunContextRequest refused : List.of(
                new AiProblemRunContextRequest(5L, 7L, "cpp", null, null),
                new AiProblemRunContextRequest(5L, 7L, "cpp", 302L, null),
                new AiProblemRunContextRequest(6L, 7L, "cpp", 302L, 401L))) {
            DomainException error = assertThrows(DomainException.class, () -> catalog.aiProblemRunContext(refused));
            assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
        }
    }

    private ProblemEntity privateProblem() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId(7L);
        problem.setTitle("Private problem");
        problem.setStatement("statement");
        problem.setTimeLimitMillis(1000);
        problem.setMemoryLimitKb(262144);
        problem.setVisibility(ProblemVisibility.PRIVATE);
        problem.setCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        problem.setUpdatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        return problem;
    }
}
