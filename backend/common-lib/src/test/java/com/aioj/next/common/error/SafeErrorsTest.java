package com.aioj.next.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** What may reach a log line about an exception (T2.5). */
class SafeErrorsTest {
    @Test
    void describeNamesTheCausesAndTheirStructuredDetailsButNoMessage() {
        String body = "{\"error\":\"messages[0] 'CANARY-PROMPT' rejected\"}";
        // How RestClient's default error handler builds it: the body is part of the message.
        var response = HttpClientErrorException.create("400 Bad Request: \"" + body + "\"", HttpStatus.BAD_REQUEST,
                "Bad Request", null, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        var wrapped = new IllegalStateException("upstream said " + response.getMessage(), response);
        var domain = new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Problem service failed: " + response.getMessage());

        assertThat(SafeErrors.describe(wrapped))
                .isEqualTo("IllegalStateException <- HttpClientErrorException.BadRequest[status=400]");
        assertThat(SafeErrors.describe(domain)).isEqualTo("DomainException[code=50300]");
    }

    @Test
    void ioAndSqlFailuresKeepWhatTellsThemApart() {
        var timeout = new ResourceAccessException("I/O error on GET for http://x/?q=CANARY-QUERY",
                new SocketTimeoutException("Read timed out"));
        var sql = new SQLException("Data too long for column 'content' value 'CANARY-CODE'", "22001", 1406);

        assertThat(SafeErrors.describe(timeout)).isEqualTo("ResourceAccessException <- SocketTimeoutException");
        assertThat(SafeErrors.describe(sql)).isEqualTo("SQLException[sqlState=22001 vendorCode=1406]");
        assertThat(SafeErrors.describe(null)).isEqualTo("none");
    }

    @Test
    void traceKeepsWhereItWasThrownWithoutTheMessages() {
        var error = new IllegalStateException("outer CANARY-A", new IllegalArgumentException("inner CANARY-B"));

        String trace = SafeErrors.trace(error);

        assertThat(trace).doesNotContain("CANARY-A", "CANARY-B")
                .startsWith("IllegalStateException")
                .contains("\nCaused by: IllegalArgumentException", "\tat com.aioj.next.common.error.SafeErrorsTest");
    }

    @Test
    void aCauseCycleEndsTheChain() {
        var first = new RuntimeException("a");
        var second = new IllegalStateException("b", first);
        first.initCause(second);

        assertThat(SafeErrors.describe(first)).isEqualTo("RuntimeException <- IllegalStateException");
    }
}
