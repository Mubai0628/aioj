package com.aioj.next.executor.domain;

import com.aioj.next.common.error.SafeErrors;
import com.aioj.next.executor.config.AgentExecutorProperties;
import com.aioj.next.sandbox.client.LanguageProfile;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Short-lived reuse of compiled programs (plan A5.3). An entry is keyed by the caller's scope,
 * the language and the source's SHA-256 (the compiler flags are fixed per language), so one
 * user's executable never serves another. Only successful compiles are kept. An entry lives
 * for the configured TTL; expired and evicted entries have their sandbox file deleted, once no
 * run is using it any more. Calls without a scope bypass the cache entirely.
 */
@Component
public class CompileCache {
    private static final Logger log = LoggerFactory.getLogger(CompileCache.class);

    private final SandboxExecutionClient sandbox;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final Counter compiles;
    private final Counter hits;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final ScheduledExecutorService sweeper;

    @Autowired
    public CompileCache(SandboxExecutionClient sandbox, AgentExecutorProperties properties,
                        ObjectProvider<MeterRegistry> meters) {
        this(sandbox, Duration.ofSeconds(Math.max(1, properties.getCompileCacheTtlSeconds())),
                Math.max(0, properties.getCompileCacheMaxEntries()), Clock.systemUTC(), meters.getIfAvailable(), true);
    }

    /** Also for tests: {@code sweep=false} leaves expiry to {@link #sweep()} calls. */
    public CompileCache(SandboxExecutionClient sandbox, Duration ttl, int maxEntries, Clock clock, MeterRegistry meters,
                        boolean sweep) {
        this.sandbox = sandbox;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
        this.compiles = meters == null ? null : Counter.builder("aioj.executor.compile.count")
                .description("Compiles run in the sandbox").register(meters);
        this.hits = meters == null ? null : Counter.builder("aioj.executor.compile.cache.hits")
                .description("Compiles answered from the compile cache").register(meters);
        if (sweep) {
            this.sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "compile-cache-sweeper");
                thread.setDaemon(true);
                return thread;
            });
            this.sweeper.scheduleWithFixedDelay(this::sweep, 60, 60, TimeUnit.SECONDS);
        } else {
            this.sweeper = null;
        }
    }

    /** A compile result; {@code hit} when it came from the cache. A cached executable stays the cache's. */
    public record Lookup(SandboxExecutionClient.CompileOutcome outcome, boolean hit) {
    }

    /** Compiles through the cache; without a scope it is a plain compile whose file the caller owns. */
    public Lookup compile(String scope, String language, String source, long cpuLimitNs, long memoryLimitBytes) {
        if (!cacheable(scope, language)) {
            count(compiles);
            return new Lookup(sandbox.compileSource(language, source, cpuLimitNs, memoryLimitBytes), false);
        }
        Key key = new Key(scope, language, sha256(source));
        Entry cached = acquire(key);
        if (cached != null) {
            release(cached);
            count(hits);
            return new Lookup(cached.outcome, true);
        }
        count(compiles);
        SandboxExecutionClient.CompileOutcome outcome = sandbox.compileSource(language, source, cpuLimitNs, memoryLimitBytes);
        if (!outcome.failed() && StringUtils.hasText(outcome.fileId())) {
            store(key, outcome, false);
        }
        return new Lookup(outcome, false);
    }

    /**
     * Runs a program, compiling it through the cache first when it has a scope and needs
     * compiling; the executable is held for the run so eviction cannot delete it underneath.
     */
    public SandboxExecutionClient.RunOutcome run(String scope, String language, String source, String stdin,
                                                 long cpuLimitNs, long memoryLimitBytes, int stdoutLimitBytes) {
        if (!cacheable(scope, language)) {
            return sandbox.runSource(language, source, null, stdin, cpuLimitNs, memoryLimitBytes, stdoutLimitBytes);
        }
        Key key = new Key(scope, language, sha256(source));
        Entry entry = acquire(key);
        if (entry != null) {
            count(hits);
        } else {
            count(compiles);
            // The same compile limits a plain run uses (SandboxExecutionClient.runSource).
            SandboxExecutionClient.CompileOutcome outcome = sandbox.compileSource(language, source,
                    safeMultiply(cpuLimitNs, 10), safeMultiply(memoryLimitBytes, 2));
            if (outcome.failed() || !StringUtils.hasText(outcome.fileId())) {
                return SandboxExecutionClient.RunOutcome.fromCompileFailure(outcome);
            }
            entry = store(key, outcome, true);
        }
        try {
            return sandbox.runSource(language, source, entry.outcome.fileId(), stdin, cpuLimitNs, memoryLimitBytes,
                    stdoutLimitBytes);
        } finally {
            release(entry);
        }
    }

    /** Deletes the files of expired entries; also runs every minute. */
    public void sweep() {
        List<String> doomed = new ArrayList<>();
        synchronized (this) {
            Instant now = clock.instant();
            Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next().getValue();
                if (!entry.expiresAt.isAfter(now)) {
                    iterator.remove();
                    retire(entry, doomed);
                }
            }
        }
        delete(doomed);
    }

    private boolean cacheable(String scope, String language) {
        return maxEntries > 0 && StringUtils.hasText(scope) && LanguageProfile.of(language).requiresCompile();
    }

    synchronized int size() {
        return entries.size();
    }

    @PreDestroy
    public void close() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        List<String> doomed = new ArrayList<>();
        synchronized (this) {
            for (Entry entry : entries.values()) {
                retire(entry, doomed);
            }
            entries.clear();
        }
        delete(doomed);
    }

    private synchronized Entry acquire(Key key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (!entry.expiresAt.isAfter(clock.instant())) {
            return null;
        }
        entry.inUse++;
        return entry;
    }

    private void release(Entry entry) {
        boolean delete;
        synchronized (this) {
            entry.inUse--;
            delete = entry.retired && entry.inUse == 0 && !entry.deleted;
            if (delete) {
                entry.deleted = true;
            }
        }
        if (delete) {
            delete(List.of(entry.outcome.fileId()));
        }
    }

    /**
     * Stores a fresh compile; if another call stored the same key meanwhile, that one wins and
     * this file is deleted. With {@code hold}, the returned entry is already held for a run.
     */
    private Entry store(Key key, SandboxExecutionClient.CompileOutcome outcome, boolean hold) {
        List<String> doomed = new ArrayList<>();
        Entry stored;
        synchronized (this) {
            Entry existing = entries.get(key);
            if (existing != null && existing.expiresAt.isAfter(clock.instant())) {
                doomed.add(outcome.fileId());
                stored = existing;
            } else {
                if (existing != null) {
                    entries.remove(key);
                    retire(existing, doomed);
                }
                stored = new Entry(outcome, clock.instant().plus(ttl));
                entries.put(key, stored);
                Iterator<Map.Entry<Key, Entry>> eldest = entries.entrySet().iterator();
                while (entries.size() > maxEntries && eldest.hasNext()) {
                    Entry evicted = eldest.next().getValue();
                    eldest.remove();
                    retire(evicted, doomed);
                }
            }
            if (hold) {
                stored.inUse++;
            }
        }
        delete(doomed);
        return stored;
    }

    /** Marks an entry gone; its file is deleted now, or by the last run still using it. */
    private void retire(Entry entry, List<String> doomed) {
        entry.retired = true;
        if (entry.inUse == 0 && !entry.deleted) {
            entry.deleted = true;
            doomed.add(entry.outcome.fileId());
        }
    }

    private void delete(List<String> fileIds) {
        for (String fileId : fileIds) {
            try {
                sandbox.deleteCachedFile(fileId);
            } catch (RuntimeException ex) {
                log.warn("compile cache could not delete sandbox file: {}", SafeErrors.describe(ex));
            }
        }
    }

    private static void count(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    private static long safeMultiply(long value, long factor) {
        try {
            return Math.multiplyExact(value, factor);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((source == null ? "" : source).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Key(String scope, String language, String sourceSha256) {
    }

    private static final class Entry {
        private final SandboxExecutionClient.CompileOutcome outcome;
        private final Instant expiresAt;
        private int inUse;
        private boolean retired;
        private boolean deleted;

        private Entry(SandboxExecutionClient.CompileOutcome outcome, Instant expiresAt) {
            this.outcome = outcome;
            this.expiresAt = expiresAt;
        }
    }
}
