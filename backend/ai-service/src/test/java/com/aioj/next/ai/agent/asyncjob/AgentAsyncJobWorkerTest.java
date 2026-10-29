package com.aioj.next.ai.agent.asyncjob;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.config.HarnessDbQuota;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAsyncJobWorkerTest {

    private final AgentAsyncJobService jobService = mock(AgentAsyncJobService.class);

    @BeforeEach
    void leasesAreRenewed() {
        when(jobService.renewLease(any(), anyString(), anyLong())).thenReturn(true);
    }

    @Test
    void dispatchesToRegisteredHandlerAndCompletes() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        AgentAsyncJobHandler handler = new AgentAsyncJobHandler() {
            @Override
            public String jobType() {
                return "TURN_CURATE";
            }

            @Override
            public void handle(AiAsyncJobEntity job) {
                handled.incrementAndGet();
            }
        };
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of(handler));
        AiAsyncJobEntity job = job(1L, "TURN_CURATE");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString(), any())).thenReturn(List.of(job));

        worker.pollDueJobs();

        org.assertj.core.api.Assertions.assertThat(handled.get()).isEqualTo(1);
        verify(jobService).complete(job);
        verify(jobService, never()).fail(any(), any(), anyLong());
    }

    @Test
    void switchedOffSubscribersAreLeftPendingWhileOthersAreClaimed() {
        AgentAsyncJobHandler off = new AgentAsyncJobHandler() {
            @Override
            public String jobType() {
                return "AI_JUDGED_SUBMISSION_ANALYSIS";
            }

            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public void handle(AiAsyncJobEntity job) {
                throw new AssertionError("a switched-off subscriber never runs");
            }
        };
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of(off));

        worker.pollDueJobs();

        verify(jobService).claimDueJobs(anyInt(), anyLong(), anyString(),
                org.mockito.ArgumentMatchers.argThat(skipped -> skipped != null
                        && skipped.contains("AI_JUDGED_SUBMISSION_ANALYSIS")));
    }

    @Test
    void oneFailingSubscriberDoesNotHoldBackTheNext() {
        AgentAsyncJobHandler failing = handler("PROFILE_AGGREGATE", () -> {
            throw new IllegalStateException("boom");
        });
        AtomicInteger embedded = new AtomicInteger();
        AgentAsyncJobHandler healthy = handler("EMBED_DIGEST", embedded::incrementAndGet);
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of(failing, healthy));
        AiAsyncJobEntity first = job(5L, "PROFILE_AGGREGATE");
        AiAsyncJobEntity second = job(6L, "EMBED_DIGEST");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString(), any())).thenReturn(List.of(first, second));

        worker.pollDueJobs();

        verify(jobService).fail(eq(first), any(), anyLong());
        verify(jobService).complete(second);
        org.assertj.core.api.Assertions.assertThat(embedded.get()).isEqualTo(1);
    }

    @Test
    void aChannelJobRunsUnderItsEventsTrace() {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        AgentAsyncJobHandler tracing = handler("TURN_CURATE", () -> seen.set(org.slf4j.MDC.get("traceId")));
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of(tracing));
        AiAsyncJobEntity job = job(7L, "TURN_CURATE");
        job.setPayloadJson("{\"turnId\":\"t-1\",\"eventId\":\"41\",\"traceId\":\"trace-41\"}");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString(), any())).thenReturn(List.of(job));

        worker.pollDueJobs();

        org.assertj.core.api.Assertions.assertThat(seen.get()).isEqualTo("trace-41");
        org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("traceId")).isNull();
    }

    private static AgentAsyncJobHandler handler(String type, Runnable body) {
        return new AgentAsyncJobHandler() {
            @Override
            public String jobType() {
                return type;
            }

            @Override
            public void handle(AiAsyncJobEntity job) {
                body.run();
            }
        };
    }

    @Test
    void unknownJobTypeParksFailedImmediately() {
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of());
        AiAsyncJobEntity job = job(2L, "MYSTERY");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString(), any())).thenReturn(List.of(job));

        worker.pollDueJobs();

        verify(jobService).failFinal(eq(job), anyString());
        verify(jobService, never()).complete(any());
    }

    @Test
    void handlerExceptionFailsWithBackoff() throws Exception {
        AgentAsyncJobHandler failing = new AgentAsyncJobHandler() {
            @Override
            public String jobType() {
                return "TURN_CURATE";
            }

            @Override
            public void handle(AiAsyncJobEntity job) throws Exception {
                throw new IllegalStateException("curator down");
            }
        };
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of(failing));
        AiAsyncJobEntity job = job(3L, "TURN_CURATE");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString(), any())).thenReturn(List.of(job));

        worker.pollDueJobs();

        verify(jobService).fail(eq(job), any(IllegalStateException.class), eq(60L));
        verify(jobService, never()).complete(any());
    }

    @Test
    void permanentFailureParksFailedWithoutBackoff() {
        AgentAsyncJobHandler failing = new AgentAsyncJobHandler() {
            @Override
            public String jobType() {
                return "EMBED_DIGEST";
            }

            @Override
            public void handle(AiAsyncJobEntity job) {
                throw new AgentAsyncJobPermanentFailure("embed job digest ownership mismatch: 11");
            }
        };
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of(failing));
        AiAsyncJobEntity job = job(4L, "EMBED_DIGEST");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString(), any())).thenReturn(List.of(job));

        worker.pollDueJobs();

        verify(jobService).failFinal(job, "embed job digest ownership mismatch: 11");
        verify(jobService, never()).fail(any(), any(), anyLong());
        verify(jobService, never()).complete(any());
    }

    /** Plan 15.4: a job that runs over is interrupted and retried, and the next one still runs. */
    @Test
    void aJobThatRunsOverItsLimitIsInterruptedAndTheNextOneRuns() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        AgentAsyncJobHandler slow = handler("TURN_CURATE", () -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException expected) {
                interrupted.countDown();
            }
        });
        AtomicInteger embedded = new AtomicInteger();
        AgentAsyncJobHandler healthy = handler("EMBED_DIGEST", embedded::incrementAndGet);
        AiProperties properties = new AiProperties();
        properties.getAgentJobs().setJobTimeoutSeconds(1);
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, properties, List.of(slow, healthy));
        AiAsyncJobEntity first = job(8L, "TURN_CURATE");
        AiAsyncJobEntity second = job(9L, "EMBED_DIGEST");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString(), any())).thenReturn(List.of(first, second));
        long started = System.nanoTime();

        worker.pollDueJobs();

        org.assertj.core.api.Assertions.assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .as("the worker waited about the limit, not the 30 s sleep").isLessThan(10_000);
        org.assertj.core.api.Assertions.assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        verify(jobService).fail(eq(first), any(TimeoutException.class), eq(60L));
        verify(jobService, never()).complete(first);
        verify(jobService).complete(second);
        org.assertj.core.api.Assertions.assertThat(embedded.get()).isEqualTo(1);
        worker.stopJobThreads();
    }

    /** Plan 15.4: the lease is extended past the job's limit before the job runs, on a Harness thread. */
    @Test
    void eachJobRenewsItsLeasePastItsLimitBeforeItRuns() {
        AtomicBoolean renewed = new AtomicBoolean();
        when(jobService.renewLease(any(), anyString(), anyLong())).thenAnswer(invocation -> {
            renewed.set(true);
            return true;
        });
        AtomicBoolean renewedFirst = new AtomicBoolean();
        AtomicReference<Boolean> harnessThread = new AtomicReference<>();
        AgentAsyncJobHandler handler = handler("TURN_CURATE", () -> {
            renewedFirst.set(renewed.get());
            harnessThread.set(HarnessDbQuota.isHarnessThread());
        });
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of(handler));
        AiAsyncJobEntity job = job(10L, "TURN_CURATE");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString(), any())).thenReturn(List.of(job));

        worker.pollDueJobs();

        verify(jobService).renewLease(eq(job), anyString(),
                eq(330L + AgentAsyncJobWorker.LEASE_MARGIN_SECONDS));
        org.assertj.core.api.Assertions.assertThat(renewedFirst.get()).isTrue();
        org.assertj.core.api.Assertions.assertThat(harnessThread.get()).isTrue();
        verify(jobService).complete(job);
    }

    @Test
    void aJobWhoseLeaseWasLostIsLeftToItsNewOwner() {
        when(jobService.renewLease(any(), anyString(), anyLong())).thenReturn(false);
        AgentAsyncJobHandler handler = handler("TURN_CURATE", () -> {
            throw new AssertionError("a job leased to another worker never runs here");
        });
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of(handler));
        AiAsyncJobEntity job = job(11L, "TURN_CURATE");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString(), any())).thenReturn(List.of(job));

        worker.pollDueJobs();

        verify(jobService, never()).complete(any());
        verify(jobService, never()).fail(any(), any(), anyLong());
        verify(jobService, never()).failFinal(any(), anyString());
    }

    private AiAsyncJobEntity job(Long id, String type) {
        AiAsyncJobEntity job = new AiAsyncJobEntity();
        job.setId(id);
        job.setJobType(type);
        job.setStatus(AgentAsyncJobService.STATUS_RUNNING);
        return job;
    }
}
