package com.aioj.next.common.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiTokenFilterTest {
    @Test
    void denialLogsNeverContainCredentialValues() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(InternalApiTokenFilter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var filter = problem("private-current-value", "private-next-value", "private-judge-value", "");
            assertThat(call(filter, "/api/v1/internal/ai/context", "private-wrong-value")).isEqualTo(401);
            assertThat(call(filter, "/api/v1/internal/testcase-packages/1", "private-current-value")).isEqualTo(403);
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message).contains("caller=", "path=", "reason=")
                            .doesNotContain("private-current-value", "private-next-value", "private-judge-value", "private-wrong-value"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void problemServiceSeparatesAiAndJudgeAndAuthorizesEveryAiPrefix() throws Exception {
        var filter = problem("ai-current", "ai-next", "judge-current", "judge-next");
        for (String path : new String[]{"/api/v1/internal/ai/context", "/api/v1/internal/ai-problem-drafts/import/prepare"}) {
            assertThat(call(filter, path, "ai-current")).isEqualTo(200);
            assertThat(call(filter, path, "ai-next")).isEqualTo(200);
            assertThat(call(filter, path, "judge-current")).isEqualTo(403);
        }
        for (String path : new String[]{"/api/v1/internal/testcase-packages/1", "/api/v1/internal/submissions/judged-events"}) {
            assertThat(call(filter, path, "judge-current")).isEqualTo(200);
            assertThat(call(filter, path, "judge-next")).isEqualTo(200);
            assertThat(call(filter, path, "ai-current")).isEqualTo(403);
        }
        assertThat(call(filter, "/api/v1/internal/contests/ai-policy/check", "ai-current")).isEqualTo(403);
        assertThat(call(filter, "/api/v1/internal/ai-lookalike", "ai-current")).isEqualTo(403);
        assertThat(call(filter, "/api/v1/internal/new-route", "judge-current")).isEqualTo(403);
    }

    @Test
    void allOtherServicesRestrictTheirOwnInboundCallerToTheirOwnPrefix() throws Exception {
        for (var policy : new Policy[]{
                new Policy(InternalApiAccess.aiService(), "problem-service", "/api/v1/internal/ai/embedding"),
                new Policy(InternalApiAccess.judgeWorker(), "problem-service", "/api/v1/internal/judge/metrics"),
                new Policy(InternalApiAccess.agentExecutor(), "ai-service", "/api/v1/internal/sandbox/compile")}) {
            var filter = new InternalApiTokenFilter(Map.of(policy.caller(), credential("current", "next")), policy.access());
            assertThat(call(filter, policy.path(), "current")).isEqualTo(200);
            assertThat(call(filter, policy.path(), "next")).isEqualTo(200);
            assertThat(call(filter, "/api/v1/internal/not-owned", "current")).isEqualTo(403);
            assertThat(call(filter, policy.path(), "some-other-direction")).isEqualTo(401);
        }
    }

    @Test
    void missingWrongJwtUnknownAndEmptyConfigurationFailClosed() throws Exception {
        var filter = problem("ai", "", "judge", "");
        for (String token : new String[]{null, "", " ", "wrong", "eyJhbGciOiJIUzI1NiJ9.jwt.signature", "x".repeat(4097)}) {
            assertThat(call(filter, "/api/v1/internal/ai/context", token)).isEqualTo(401);
        }
        assertThat(call(new InternalApiTokenFilter(Map.of(), InternalApiAccess.aiService()),
                "/api/v1/internal/ai/context", "any")).isEqualTo(401);
        assertThat(call(problem("", "", "", ""), "/api/v1/internal/ai/context", "")).isEqualTo(401);
    }

    @Test
    void nextCanBeRemovedAndOldCurrentCanBeRetiredWithoutChangingTheCaller() throws Exception {
        assertThat(call(problem("old", "new", "judge", ""), "/api/v1/internal/ai/context", "new")).isEqualTo(200);
        assertThat(call(problem("new", "", "judge", ""), "/api/v1/internal/ai/context", "old")).isEqualTo(401);
        assertThat(call(problem("new", "", "judge", ""), "/api/v1/internal/ai/context", "new")).isEqualTo(200);
        assertThat(InternalApiTokenFilter.matches("next", "old", "next")).isTrue();
        assertThat(InternalApiTokenFilter.matches("old", "new", "")).isFalse();
        assertThat(InternalApiTokenFilter.matches("", "", "")).isFalse();
    }

    @Test
    void duplicateDirectionSecretsAndDuplicateHeadersAreRejected() throws Exception {
        assertThat(call(problem("same", "", "same", ""), "/api/v1/internal/ai/context", "same")).isEqualTo(401);
        var request = new MockHttpServletRequest("GET", "/api/v1/internal/ai/context");
        request.addHeader("X-Internal-Token", "ai");
        request.addHeader("X-Internal-Token", "ai");
        var response = new MockHttpServletResponse();
        problem("ai", "", "judge", "").doFilter(request, response, (req, res) -> { throw new AssertionError("must reject"); });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void roleAndCallerAreAvailableOnlyDuringTheInternalRequest() throws Exception {
        var filter = problem("ai", "", "judge", "");
        var request = new MockHttpServletRequest("GET", "/api/v1/internal/ai/context");
        request.addHeader("X-Internal-Token", "ai");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getName()).isEqualTo("ai-service");
            assertThat(authentication.getAuthorities()).extracting(Object::toString)
                    .containsExactly("ROLE_INTERNAL", "INTERNAL_CALLER_ai-service");
        });
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(call(filter, "/api/v1/problems", null)).isEqualTo(200);
        assertThat(call(filter, "/api/v1/internal", null)).isEqualTo(401);
    }

    private static InternalApiTokenFilter problem(String ai, String aiNext, String judge, String judgeNext) {
        return new InternalApiTokenFilter(Map.of("ai-service", credential(ai, aiNext),
                "judge-worker", credential(judge, judgeNext)), InternalApiAccess.problemService());
    }

    private static InternalServiceProperties.Credential credential(String current, String next) {
        var credential = new InternalServiceProperties.Credential();
        credential.setToken(current);
        credential.setNextToken(next);
        return credential;
    }

    private static int call(InternalApiTokenFilter filter, String path, String token) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        if (token != null) { request.addHeader("X-Internal-Token", token); }
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        FilterChain chain = (req, res) -> called.set(true);
        filter.doFilter(request, response, chain);
        assertThat(called.get()).isEqualTo(response.getStatus() == 200);
        return response.getStatus();
    }

    private record Policy(Map<String, Set<String>> access, String caller, String path) { }
}
