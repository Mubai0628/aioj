package com.aioj.next.judge.monitor;

import com.aioj.next.judge.config.JudgeQueueConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code aioj.judge.queue.depth}: messages waiting in the judge queue and in its dead-letter queue
 * (T2.5). A passive declare every 15 seconds on its own daemon thread; a scrape only reads the last
 * value, so a slow or absent broker never holds up the metrics endpoint. NaN: the broker did not answer.
 */
@Component
public class JudgeQueueDepthMetrics implements DisposableBean {
    static final long REFRESH_SECONDS = 15;
    private static final Logger log = LoggerFactory.getLogger(JudgeQueueDepthMetrics.class);

    private final AmqpAdmin admin;
    private final Map<String, AtomicReference<Double>> depthByQueue = new LinkedHashMap<>();
    private final ScheduledExecutorService refresher;
    private volatile boolean answering = true;

    @Autowired
    public JudgeQueueDepthMetrics(AmqpAdmin admin, MeterRegistry registry) {
        this(admin, registry, true);
    }

    JudgeQueueDepthMetrics(AmqpAdmin admin, MeterRegistry registry, boolean schedule) {
        this.admin = admin;
        track(registry, "judge", JudgeQueueConfig.JUDGE_QUEUE);
        track(registry, "dead", JudgeQueueConfig.JUDGE_DEAD_LETTER_QUEUE);
        if (!schedule) {
            this.refresher = null;
            return;
        }
        this.refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "judge-queue-depth");
            thread.setDaemon(true);
            return thread;
        });
        refresher.scheduleWithFixedDelay(this::refresh, 0, REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    private void track(MeterRegistry registry, String tag, String queue) {
        AtomicReference<Double> depth = new AtomicReference<>(Double.NaN);
        depthByQueue.put(queue, depth);
        Gauge.builder("aioj.judge.queue.depth", depth, AtomicReference::get)
                .description("Messages waiting in a judge queue (ready, not yet taken by a worker)")
                .tag("queue", tag)
                .register(registry);
    }

    void refresh() {
        boolean all = true;
        for (Map.Entry<String, AtomicReference<Double>> entry : depthByQueue.entrySet()) {
            try {
                QueueInformation info = admin.getQueueInfo(entry.getKey());
                entry.getValue().set(info == null ? Double.NaN : (double) info.getMessageCount());
            } catch (RuntimeException ex) {
                entry.getValue().set(Double.NaN);
                all = false;
                if (answering) {
                    log.warn("Judge queue depth unavailable queue={} error={}", entry.getKey(), ex.getClass().getSimpleName());
                }
            }
        }
        if (all && !answering) {
            log.info("Judge queue depth available again");
        }
        answering = all;
    }

    @Override
    public void destroy() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
    }
}
