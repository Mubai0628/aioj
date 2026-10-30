package com.aioj.next.executor.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/**
 * At most {@code permits} execution requests at a time (plan §3.6 rule 5:
 * concurrency 1). A request that finds no free permit gets 503 at once
 * instead of queueing behind a long package run. The 503 carries Retry-After,
 * which tells ai-service's circuit breaker that the executor is busy, not down.
 */
public class ExecutionConcurrencyFilter extends OncePerRequestFilter {
    private final Semaphore permits;
    private final Counter rejected;

    public ExecutionConcurrencyFilter(int permits) {
        this(permits, null);
    }

    /** @param meters null records nothing; otherwise the requests running now and the ones turned away (T2.5) */
    public ExecutionConcurrencyFilter(int permits, MeterRegistry meters) {
        int capacity = Math.max(1, permits);
        this.permits = new Semaphore(capacity);
        this.rejected = meters == null ? null : Counter.builder("aioj.executor.rejected")
                .description("Execution requests answered 503 because every permit was taken")
                .register(meters);
        if (meters != null) {
            Gauge.builder("aioj.executor.inflight", this.permits, held -> capacity - held.availablePermits())
                    .description("Execution requests holding a permit")
                    .register(meters);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!permits.tryAcquire()) {
            if (rejected != null) {
                rejected.increment();
            }
            response.setHeader("Retry-After", "1");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "agent executor is busy");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}
