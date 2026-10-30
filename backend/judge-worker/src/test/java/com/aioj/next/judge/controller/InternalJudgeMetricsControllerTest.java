package com.aioj.next.judge.controller;

import com.aioj.next.common.security.InternalServiceProperties;
import com.aioj.next.judge.config.InternalApiProperties;
import com.aioj.next.judge.monitor.JudgeRuntimeMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InternalJudgeMetricsControllerTest {
    @Test
    void checksTheProblemServiceCredentialEvenIfTheFilterIsBypassed() {
        var controller = new InternalJudgeMetricsController(mock(JudgeRuntimeMetrics.class), properties("current", "next"));

        for (String token : new String[]{null, "", "wrong"}) {
            assertThatThrownBy(() -> controller.metrics(token))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            ex -> assertThat(ex.getStatusCode().value()).isEqualTo(401));
        }
        assertThat(controller.metrics("current").code()).isZero();
        assertThat(controller.metrics("next").code()).isZero();
    }

    @Test
    void rejectsEverythingWithoutAConfiguredCredential() {
        var controller = new InternalJudgeMetricsController(mock(JudgeRuntimeMetrics.class), new InternalApiProperties());

        assertThatThrownBy(() -> controller.metrics("anything")).isInstanceOf(ResponseStatusException.class);
    }

    private static InternalApiProperties properties(String current, String next) {
        var credential = new InternalServiceProperties.Credential();
        credential.setToken(current);
        credential.setNextToken(next);
        var properties = new InternalApiProperties();
        properties.getInbound().put("problem-service", credential);
        return properties;
    }
}
