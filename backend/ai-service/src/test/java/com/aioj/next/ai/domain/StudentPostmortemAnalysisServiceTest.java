package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisRequest;
import com.aioj.next.contract.ai.StudentPostmortemCodeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentPostmortemAnalysisServiceTest {

    @Test
    void successfulAnalysisKeepsDeterministicCandidatesAndRecordsUsage() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenReturn(new AiCompletion("## 个人成绩概览\n本场需要继续复盘二分。", "mock", "mock-model", 80, 32));
        StudentPostmortemAnalysisService service = new StudentPostmortemAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()), new ObjectMapper());

        var response = service.analyze(request());

        assertThat(response.success()).isTrue();
        assertThat(response.markdown()).contains("个人成绩概览");
        assertThat(response.weaknessCandidates()).hasSize(1);
        assertThat(response.weaknessCandidates().get(0).knowledgeNode()).isEqualTo("binary_search");
        var candidate = response.weaknessCandidates().get(0);
        assertThat(candidate.symptom()).isEqualTo("提交后仍未通过");
        assertThat(candidate.evidence()).hasSize(1);
        assertThat(candidate.evidence().get(0)).contains("WRONG_ANSWER");
        assertThat(candidate.symptom()).isNotEqualTo(candidate.evidence().get(0));
        assertThat(response.practiceSuggestions()).isNotEmpty();
        verify(quotaService).record(7L, "mock", "mock-model", 80, 32, true);
    }

    @Test
    void providerFailureStillReturnsCandidatesAndPracticeSuggestions() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.providerName()).thenReturn("mock");
        when(provider.model()).thenReturn("mock-model");
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenThrow(new IllegalStateException("upstream unavailable"));
        StudentPostmortemAnalysisService service = new StudentPostmortemAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()), new ObjectMapper());

        var response = service.analyze(request());

        assertThat(response.success()).isFalse();
        assertThat(response.markdown()).isNull();
        assertThat(response.weaknessCandidates()).isNotEmpty();
        assertThat(response.practiceSuggestions()).isNotEmpty();
        assertThat(response.errorMessage()).contains("upstream unavailable");
        verify(quotaService).record(eq(7L), eq("mock"), eq("mock-model"), eq(0L), eq(0L), eq(false));
    }

    @Test
    void acmAnalysisIncludesRepresentativeCodeAndAvoidsScoreInstructions() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenReturn(new AiCompletion("## 个人成绩概览\n已通过 A 题。", "mock", "mock-model", 60, 20));
        StudentPostmortemAnalysisService service = new StudentPostmortemAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()), new ObjectMapper());

        var response = service.analyze(acmRequest());
        assertThat(response.weaknessCandidates()).isEmpty();

        var contextCaptor = forClass(AiChatContext.class);
        verify(provider).chat(any(AiChatRequest.class), contextCaptor.capture(), eq(AiModelScope.REPORT_ANALYSIS));
        String pack = contextCaptor.getValue().conversationContextPack();
        assertThat(pack).contains("ACM mode rules");
        assertThat(pack).contains("Representative ACM Code References");
        assertThat(pack).contains("int main()");
        assertThat(pack).contains("Do not mention total score");
    }

    @Test
    void unattemptedAcmProblemIsNotAWeaknessCandidate() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenReturn(new AiCompletion("ok", "mock", "mock-model", 1, 1));
        StudentPostmortemAnalysisService service = new StudentPostmortemAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()), new ObjectMapper());

        StudentPostmortemAnalysisRequest request = new StudentPostmortemAnalysisRequest(
                7L, 12L, 34L, 56L, 7L,
                "Spring Contest", "Replay Run", "ACM",
                """
                        {
                          "submissionCount": 0,
                          "weaknessSeeds": [],
                          "problems": [
                            {
                              "label": "B",
                              "title": "迷宫最短逃脱",
                              "bestStatus": null,
                              "submissionCount": 0,
                              "tags": ["BFS"]
                            }
                          ]
                        }
                        """,
                "B 题没有提交记录。",
                List.of()
        );

        var response = service.analyze(request);

        assertThat(response.success()).isTrue();
        assertThat(response.weaknessCandidates()).isEmpty();
    }

    @Test
        void acceptedAcmProblemWithSeedRemainsAReviewCandidate() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenReturn(new AiCompletion("ok", "mock", "mock-model", 1, 1));
        StudentPostmortemAnalysisService service = new StudentPostmortemAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()), new ObjectMapper());

        StudentPostmortemAnalysisRequest request = new StudentPostmortemAnalysisRequest(
                7L, 12L, 34L, 56L, 7L,
                "Spring Contest", "Replay Run", "ACM",
                """
                        {
                        "weaknessSeeds": ["A 星港间距：最佳状态 ACCEPTED，提交 3 次，标签 binary_search"],
                        "problems": [
                                {
                                "label": "A",
                                "title": "星港间距",
                                "bestStatus": "ACCEPTED",
                                "submissionCount": 3,
                                "tags": ["binary_search"]
                                }
                        ]
                        }
                        """,
                "A 题最终通过，但有需要复盘的记录。",
                List.of()
        );

        var response = service.analyze(request);

        assertThat(response.success()).isTrue();
        assertThat(response.weaknessCandidates()).hasSize(1);
        var candidate = response.weaknessCandidates().get(0);
        assertThat(candidate.knowledgeNode()).isEqualTo("binary_search");
        assertThat(candidate.symptom()).contains("最终通过");
        assertThat(candidate.evidence().get(0)).contains("ACCEPTED");
        }

    private StudentPostmortemAnalysisRequest request() {
        return new StudentPostmortemAnalysisRequest(
                7L,
                12L,
                34L,
                56L,
                7L,
                "Spring Contest",
                "Replay Run",
                "IOI",
                """
                        {
                          "submissionCount": 3,
                          "acceptedCount": 1,
                          "totalScore": 35,
                          "maxScore": 100,
                          "weaknessSeeds": ["A 星港间距：最佳状态 WRONG_ANSWER，得分 35/100，标签 binary_search, greedy"],
                          "problems": [
                            {
                              "label": "A",
                              "title": "星港间距",
                              "bestStatus": "WRONG_ANSWER",
                              "bestScore": 35,
                              "maxScore": 100,
                              "submissionCount": 3,
                              "tags": ["binary_search", "greedy"]
                            }
                          ]
                        }
                        """,
                "学生在 A 题二分答案与贪心 check 上得分不稳定。",
                List.of()
        );
    }

    private StudentPostmortemAnalysisRequest acmRequest() {
        return new StudentPostmortemAnalysisRequest(
                7L,
                12L,
                34L,
                56L,
                7L,
                "Spring Contest",
                "Replay Run",
                "ACM",
                """
                        {
                          "submissionCount": 2,
                          "acceptedCount": 1,
                          "weaknessSeeds": [],
                          "problems": [
                            {
                              "label": "A",
                              "title": "星港间距",
                              "bestStatus": "ACCEPTED",
                              "submissionCount": 2,
                              "tags": ["binary_search", "greedy"]
                            }
                          ]
                        }
                        """,
                "ACM 赛制：学生提交 2 次，通过 1 题。",
                List.of(new StudentPostmortemCodeReference(
                        101L,
                        201L,
                        "A",
                        "星港间距",
                        "cpp",
                        "ACCEPTED",
                        60000L,
                        24,
                        "int main() { return 0; }"
                ))
        );
    }
}
