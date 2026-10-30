package com.aioj.next.common.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcContextTest {
    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void theWorkerRunsUnderTheSubmittersContextAndGetsItsOwnBack() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MDC.put(TraceIds.MDC_KEY, "submitter");
        Runnable wrapped = MdcContext.wrap(() -> seen.set(MDC.get(TraceIds.MDC_KEY)));
        MDC.clear();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> MDC.put(TraceIds.MDC_KEY, "worker-own")).get();
            pool.submit(wrapped).get();
            String after = pool.submit(() -> MDC.get(TraceIds.MDC_KEY)).get();

            assertThat(seen.get()).isEqualTo("submitter");
            assertThat(after).isEqualTo("worker-own");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSubmitterWithoutContextLeavesNoStaleTraceOnTheWorker() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("unset");
        Runnable wrapped = MdcContext.wrap(() -> seen.set(MDC.get(TraceIds.MDC_KEY)));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> MDC.put(TraceIds.MDC_KEY, "previous-task")).get();
            pool.submit(wrapped).get();

            assertThat(seen.get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }
}
