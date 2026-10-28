package com.aioj.next.ai.agent.channel;

import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-consumer gauges for the unified channel (plan T1.7): {@code aioj.ai.channel.backlog}
 * (pending and running jobs), {@code aioj.ai.channel.oldest.wait.seconds} (how long the oldest
 * pending job has waited) and {@code aioj.ai.channel.dead.letters} (jobs out of attempts). The
 * worker counts failures ({@code aioj.ai.channel.failures}). One grouped count per refresh.
 */
@Component
public class ChannelMetrics {
    private static final Logger log = LoggerFactory.getLogger(ChannelMetrics.class);
    private static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0);

    private final AgentAsyncJobService jobService;
    private final HarnessEventChannel channel;
    private final MeterRegistry registry;
    private final Map<String, Snapshot> latest = new ConcurrentHashMap<>();
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    public ChannelMetrics(AgentAsyncJobService jobService, HarnessEventChannel channel,
                          ObjectProvider<MeterRegistry> registry) {
        this.jobService = jobService;
        this.channel = channel;
        this.registry = registry.getIfAvailable();
    }

    record Snapshot(long pending, long running, long failed, double oldestWaitSeconds) {
        long backlog() {
            return pending + running;
        }
    }

    @Scheduled(fixedDelayString = "${aioj.ai.channel.metrics-refresh-ms:30000}",
            initialDelayString = "${aioj.ai.channel.metrics-refresh-ms:30000}")
    public void refresh() {
        try {
            refresh(LocalDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("AI channel metrics refresh failed: {}", ex.toString());
        }
    }

    void refresh(LocalDateTime now) {
        Map<String, long[]> counts = new HashMap<>();
        Map<String, LocalDateTime> oldestPending = new HashMap<>();
        for (Map<String, Object> row : jobService.countsByTypeAndStatus()) {
            Object type = column(row, "job_type");
            Object status = column(row, "status");
            if (type == null || status == null) {
                continue;
            }
            long[] byStatus = counts.computeIfAbsent(type.toString(), key -> new long[3]);
            long count = column(row, "job_count") instanceof Number number ? number.longValue() : 0;
            switch (status.toString()) {
                case AgentAsyncJobService.STATUS_PENDING -> {
                    byStatus[0] += count;
                    LocalDateTime created = dateTime(column(row, "oldest_created_at"));
                    if (created != null) {
                        oldestPending.put(type.toString(), created);
                    }
                }
                case AgentAsyncJobService.STATUS_RUNNING -> byStatus[1] += count;
                case AgentAsyncJobService.STATUS_FAILED -> byStatus[2] += count;
                default -> {
                }
            }
        }
        Set<String> consumers = new java.util.TreeSet<>(channel.consumers());
        consumers.addAll(counts.keySet());
        for (String consumer : consumers) {
            long[] byStatus = counts.getOrDefault(consumer, new long[3]);
            LocalDateTime oldest = oldestPending.get(consumer);
            double wait = oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toMillis() / 1000.0);
            latest.put(consumer, new Snapshot(byStatus[0], byStatus[1], byStatus[2], wait));
            register(consumer);
        }
    }

    Snapshot snapshot(String consumer) {
        return latest.getOrDefault(consumer, EMPTY);
    }

    private void register(String consumer) {
        if (registry == null || !registered.add(consumer)) {
            return;
        }
        Gauge.builder("aioj.ai.channel.backlog", latest, map -> map.getOrDefault(consumer, EMPTY).backlog())
                .tag("consumer", consumer).strongReference(true).register(registry);
        Gauge.builder("aioj.ai.channel.oldest.wait.seconds", latest,
                        map -> map.getOrDefault(consumer, EMPTY).oldestWaitSeconds())
                .tag("consumer", consumer).strongReference(true).baseUnit("seconds").register(registry);
        Gauge.builder("aioj.ai.channel.dead.letters", latest, map -> map.getOrDefault(consumer, EMPTY).failed())
                .tag("consumer", consumer).strongReference(true).register(registry);
    }

    /** Map keys of a grouped select follow the driver's label case; match them loosely. */
    private static Object column(Map<String, Object> row, String name) {
        Object exact = row.get(name);
        if (exact != null) {
            return exact;
        }
        String wanted = name.replace("_", "").toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().replace("_", "").toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static LocalDateTime dateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime()).toLocalDateTime();
        }
        return null;
    }
}
