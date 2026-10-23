package com.aioj.next.executor.config;

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
 * instead of queueing behind a long package run.
 */
public class ExecutionConcurrencyFilter extends OncePerRequestFilter {
    private final Semaphore permits;

    public ExecutionConcurrencyFilter(int permits) {
        this.permits = new Semaphore(Math.max(1, permits));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!permits.tryAcquire()) {
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
