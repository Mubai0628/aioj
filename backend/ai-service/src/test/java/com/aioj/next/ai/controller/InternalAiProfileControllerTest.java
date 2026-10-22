package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.AiLearningProfileService;
import com.aioj.next.contract.ai.AiRecommendationProfileSignal;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalAiProfileControllerTest {
    @Test
    void recommendationSignalsAreInternalOnlyAndDelegate() throws Exception {
        AiLearningProfileService service = mock(AiLearningProfileService.class);
        List<AiRecommendationProfileSignal> signals = List.of(new AiRecommendationProfileSignal("binary_search", "二分查找", 0.8, 3));
        when(service.recommendationSignals(7L, 10)).thenReturn(signals);

        var response = new InternalAiProfileController(service).recommendationSignals(7L, 10);

        assertThat(response.data()).isEqualTo(signals);
        PreAuthorize rule = InternalAiProfileController.class
                .getDeclaredMethod("recommendationSignals", Long.class, Integer.class)
                .getAnnotation(PreAuthorize.class);
        assertThat(rule.value()).isEqualTo("hasRole('INTERNAL')");
    }
}
