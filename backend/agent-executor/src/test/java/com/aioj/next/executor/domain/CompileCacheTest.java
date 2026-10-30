package com.aioj.next.executor.domain;

import com.aioj.next.sandbox.client.SandboxException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompileCacheTest {
    private static final String SOURCE = "int main(){return 0;}";

    private final SandboxExecutionClient sandbox = mock(SandboxExecutionClient.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-28T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void theSameScopeAndSourceCompileOnce() {
        compiles("file-1");
        CompileCache cache = cache(256);

        CompileCache.Lookup first = cache.compile("user:1", "cpp", SOURCE, 1L, 1L);
        CompileCache.Lookup second = cache.compile("user:1", "cpp", SOURCE, 1L, 1L);

        assertThat(first.hit()).isFalse();
        assertThat(second.hit()).isTrue();
        assertThat(second.outcome().fileId()).isEqualTo("file-1");
        verify(sandbox, times(1)).compileSource(eq("cpp"), eq(SOURCE), anyLong(), anyLong());
        assertThat(meters.get("aioj.executor.compile.count").counter().count()).isEqualTo(1);
        assertThat(meters.get("aioj.executor.compile.cache.hits").counter().count()).isEqualTo(1);
    }

    @Test
    void scopesNeverShareAnExecutable() {
        compiles("file-1", "file-2");
        CompileCache cache = cache(256);

        cache.compile("user:1", "cpp", SOURCE, 1L, 1L);
        CompileCache.Lookup other = cache.compile("user:2", "cpp", SOURCE, 1L, 1L);

        assertThat(other.hit()).isFalse();
        assertThat(other.outcome().fileId()).isEqualTo("file-2");
        verify(sandbox, times(2)).compileSource(eq("cpp"), eq(SOURCE), anyLong(), anyLong());
    }

    @Test
    void anExpiredEntryLosesItsFileAndCompilesAgain() {
        compiles("file-1", "file-2");
        CompileCache cache = cache(256);
        cache.compile("user:1", "cpp", SOURCE, 1L, 1L);

        now.set(now.get().plus(Duration.ofMinutes(11)));
        cache.sweep();

        verify(sandbox).deleteCachedFile("file-1");
        assertThat(cache.size()).isZero();
        assertThat(cache.compile("user:1", "cpp", SOURCE, 1L, 1L).hit()).isFalse();
    }

    @Test
    void theEldestEntryIsEvictedAndDeletedPastTheLimit() {
        compiles("file-1", "file-2");
        CompileCache cache = cache(1);

        cache.compile("user:1", "cpp", SOURCE, 1L, 1L);
        cache.compile("user:1", "cpp", SOURCE + " ", 1L, 1L);

        verify(sandbox).deleteCachedFile("file-1");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void runsReuseTheCachedExecutableAndLeaveItInPlace() {
        compiles("file-1");
        SandboxExecutionClient.RunOutcome ran = SandboxExecutionClient.RunOutcome.fromCompileFailure(
                SandboxExecutionClient.CompileOutcome.failed("unused", 0L, 0L, "", 0, 0L, 0));
        when(sandbox.runSource(eq("cpp"), eq(SOURCE), eq("file-1"), anyString(), anyLong(), anyLong(), anyInt()))
                .thenReturn(ran);
        CompileCache cache = cache(256);

        cache.run("user:1", "cpp", SOURCE, "1 2\n", 1_000_000_000L, 256L << 20, 65_536);
        cache.run("user:1", "cpp", SOURCE, "3 4\n", 1_000_000_000L, 256L << 20, 65_536);

        verify(sandbox, times(1)).compileSource(eq("cpp"), eq(SOURCE), anyLong(), anyLong());
        verify(sandbox, times(2)).runSource(eq("cpp"), eq(SOURCE), eq("file-1"), anyString(), anyLong(), anyLong(), anyInt());
        verify(sandbox, never()).deleteCachedFile(anyString());
    }

    @Test
    void aFailedCompileIsNotKept() {
        when(sandbox.compileSource(anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(SandboxExecutionClient.CompileOutcome.failed("error: expected ';'", 5L, 1024L,
                        "main.cpp:1:5: error: expected ';'", 1, 5L, 1));
        CompileCache cache = cache(256);

        SandboxExecutionClient.RunOutcome first = cache.run("user:1", "cpp", SOURCE, "", 1L, 1L, 1);
        cache.run("user:1", "cpp", SOURCE, "", 1L, 1L, 1);

        assertThat(first.status()).isNotBlank();
        verify(sandbox, times(2)).compileSource(anyString(), anyString(), anyLong(), anyLong());
        verify(sandbox, never()).runSource(anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyInt());
        assertThat(cache.size()).isZero();
    }

    @Test
    void withoutAScopeNothingIsCached() {
        compiles("file-1", "file-2");
        CompileCache cache = cache(256);

        cache.compile(null, "cpp", SOURCE, 1L, 1L);
        CompileCache.Lookup second = cache.compile(" ", "cpp", SOURCE, 1L, 1L);

        assertThat(second.hit()).isFalse();
        assertThat(cache.size()).isZero();
        cache.run(null, "cpp", SOURCE, "", 1L, 1L, 1);
        verify(sandbox).runSource(eq("cpp"), eq(SOURCE), isNull(), anyString(), anyLong(), anyLong(), anyInt());
    }

    @Test
    void anEntryThatExpiresDuringARunIsDeletedOnlyAfterIt() {
        compiles("file-1");
        CompileCache cache = cache(256);
        when(sandbox.runSource(eq("cpp"), eq(SOURCE), eq("file-1"), anyString(), anyLong(), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    now.set(now.get().plus(Duration.ofMinutes(11)));
                    cache.sweep();
                    verify(sandbox, never()).deleteCachedFile(anyString());
                    return SandboxExecutionClient.RunOutcome.fromCompileFailure(
                            SandboxExecutionClient.CompileOutcome.failed("unused", 0L, 0L, "", 0, 0L, 0));
                });

        cache.run("user:1", "cpp", SOURCE, "", 1L, 1L, 1);

        InOrder order = inOrder(sandbox);
        order.verify(sandbox).runSource(eq("cpp"), eq(SOURCE), eq("file-1"), anyString(), anyLong(), anyLong(), anyInt());
        order.verify(sandbox).deleteCachedFile("file-1");
    }

    @Test
    void aFileTheSandboxAlreadyExpiredIsNotADeleteFailure() {
        compiles("file-1", "file-2");
        SandboxException gone = mock(SandboxException.class);
        when(gone.httpStatus()).thenReturn(404);
        doThrow(gone).when(sandbox).deleteCachedFile("file-1");
        CompileCache cache = cache(256);
        cache.compile("user:1", "cpp", SOURCE, 1L, 1L);
        cache.compile("user:2", "cpp", SOURCE, 1L, 1L);

        cache.close();

        // T2.7: go-judge's file timeout got there first; the other files are still deleted.
        verify(sandbox).deleteCachedFile("file-1");
        verify(sandbox).deleteCachedFile("file-2");
    }

    @Test
    void closingDeletesEveryCachedFile() {
        compiles("file-1", "file-2");
        CompileCache cache = cache(256);
        cache.compile("user:1", "cpp", SOURCE, 1L, 1L);
        cache.compile("user:2", "cpp", SOURCE, 1L, 1L);

        cache.close();

        verify(sandbox).deleteCachedFile("file-1");
        verify(sandbox).deleteCachedFile("file-2");
    }

    private void compiles(String... fileIds) {
        var stub = when(sandbox.compileSource(anyString(), any(), anyLong(), anyLong()));
        for (String fileId : fileIds) {
            stub = stub.thenReturn(SandboxExecutionClient.CompileOutcome.success(fileId, 10L, 2048L, "", 0, 12L, 1));
        }
    }

    private CompileCache cache(int maxEntries) {
        return new CompileCache(sandbox, Duration.ofMinutes(10), maxEntries, clock, meters, false);
    }
}
