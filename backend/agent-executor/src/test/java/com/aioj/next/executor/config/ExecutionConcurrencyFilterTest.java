package com.aioj.next.executor.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionConcurrencyFilterTest {

    @Test
    void aSecondExecutionIsTurnedAwayWhileTheFirstRuns() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ExecutionConcurrencyFilter filter = new ExecutionConcurrencyFilter(1, meters);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FilterChain slow = (request, response) -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };

        CompletableFuture<MockHttpServletResponse> first = CompletableFuture.supplyAsync(() -> {
            MockHttpServletResponse response = new MockHttpServletResponse();
            try {
                filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/internal/sandbox/run-one"), response, slow);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            return response;
        });
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(meters.get("aioj.executor.inflight").gauge().value()).isEqualTo(1.0);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/internal/sandbox/run-one"), second,
                (request, response) -> {
                    throw new AssertionError("must not run while the first holds the permit");
                });
        release.countDown();

        assertThat(second.getStatus()).isEqualTo(503);
        assertThat(second.getHeader("Retry-After")).as("busy, not down").isEqualTo("1");
        assertThat(meters.get("aioj.executor.rejected").counter().count()).isEqualTo(1.0);
        assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/internal/sandbox/run-one"), third,
                (request, response) -> {
                });
        assertThat(third.getStatus()).as("the permit is back").isEqualTo(200);
        assertThat(meters.get("aioj.executor.inflight").gauge().value()).isZero();
    }
}
