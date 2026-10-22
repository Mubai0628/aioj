package com.aioj.next.judge.monitor;

import com.aioj.next.contract.submission.SubmissionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Judge task metrics. Meters go to the actuator registry (Prometheus); the
 * snapshot backs the admin judge monitor page. Error details are reduced to the
 * exception type so the page never shows sandbox output or paths.
 */
@Component
public class JudgeRuntimeMetrics {
    private final MeterRegistry registry;
    private final Map<SubmissionStatus, Counter> finishedByStatus = new EnumMap<>(SubmissionStatus.class);
    private final Counter failedTasks;
    private final Timer queueWait;
    private final Timer judgeDuration;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong systemErrors = new AtomicLong();
    private final AtomicReference<Instant> lastFinishedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();
    private final AtomicReference<String> lastFailureType = new AtomicReference<>();

    public JudgeRuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.failedTasks = Counter.builder("aioj.judge.tasks.failed")
                .description("Judge tasks that ended with an exception and went to the dead-letter queue")
                .register(registry);
        this.queueWait = Timer.builder("aioj.judge.queue.wait")
                .description("Time a judge task waited in the queue before a worker took it")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        this.judgeDuration = Timer.builder("aioj.judge.duration")
                .description("Wall time from taking a judge task to its terminal result")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        registry.gauge("aioj.judge.inflight", inFlight);
    }

    public void taskStarted(Long queueWaitMillis) {
        started.incrementAndGet();
        inFlight.incrementAndGet();
        if (queueWaitMillis != null && queueWaitMillis >= 0) {
            queueWait.record(queueWaitMillis, TimeUnit.MILLISECONDS);
        }
    }

    public void taskFinished(SubmissionStatus status, long judgeMillis) {
        finished.incrementAndGet();
        inFlight.updateAndGet(value -> Math.max(0, value - 1));
        lastFinishedAt.set(Instant.now());
        if (status == SubmissionStatus.SYSTEM_ERROR) {
            systemErrors.incrementAndGet();
        }
        if (status != null) {
            finishedCounter(status).increment();
        }
        judgeDuration.record(Duration.ofMillis(Math.max(0L, judgeMillis)));
    }

    public void taskFailed(Throwable error, boolean wasStarted) {
        failed.incrementAndGet();
        if (wasStarted) {
            inFlight.updateAndGet(value -> Math.max(0, value - 1));
        }
        failedTasks.increment();
        lastFailureAt.set(Instant.now());
        lastFailureType.set(error == null ? "unknown" : error.getClass().getSimpleName());
    }

    public Snapshot snapshot() {
        return new Snapshot(started.get(), finished.get(), failed.get(), systemErrors.get(), inFlight.get(),
                lastFinishedAt.get(), lastFailureAt.get(), lastFailureType.get(),
                queueWait.count() == 0 ? null : Math.round(queueWait.mean(TimeUnit.MILLISECONDS)),
                judgeDuration.count() == 0 ? null : Math.round(judgeDuration.mean(TimeUnit.MILLISECONDS)));
    }

    private Counter finishedCounter(SubmissionStatus status) {
        synchronized (finishedByStatus) {
            return finishedByStatus.computeIfAbsent(status, key -> Counter.builder("aioj.judge.tasks.finished")
                    .description("Judge tasks that reached a terminal verdict")
                    .tag("status", key.name())
                    .register(registry));
        }
    }

    public record Snapshot(long started,
                           long finished,
                           long failed,
                           long systemErrors,
                           int inFlight,
                           Instant lastFinishedAt,
                           Instant lastFailureAt,
                           String lastFailureType,
                           Long averageQueueWaitMillis,
                           Long averageJudgeMillis) {
    }
}
