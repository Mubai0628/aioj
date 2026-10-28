package com.aioj.next.ai.config;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan T1.6 / §6.3: Harness work that fills its quota does not take the last connection of the
 * pool; a request thread still gets one. Measured here on a pool of four: at most three
 * Harness connections at once, however many Harness threads ask.
 */
class HarnessDbQuotaDataSourceTest {

    @Test
    void harnessThreadsShareThePoolMinusOneAndRequestThreadsStillGetAConnection() throws Exception {
        DataSource pool = mock(DataSource.class);
        when(pool.getConnection()).thenAnswer(invocation -> mock(Connection.class));
        HarnessDbQuota quota = new HarnessDbQuota(HarnessDbQuota.permitsFor(4), Duration.ofMillis(200));
        HarnessDbQuotaDataSource dataSource = new HarnessDbQuotaDataSource(pool, quota);

        CountDownLatch holding = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger refused = new AtomicInteger();
        List<Thread> harnessThreads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Thread thread = new Thread(() -> {
                try (Connection ignored = dataSource.getConnection()) {
                    holding.countDown();
                    release.await();
                } catch (SQLTransientConnectionException ex) {
                    refused.incrementAndGet();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }, HarnessDbQuota.THREAD_PREFIX + "tool-" + i);
            harnessThreads.add(thread);
            thread.start();
        }
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(quota.inUse()).isEqualTo(3);

        // The request thread (not a Harness thread) is not counted against the quota.
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection).isNotNull();
        }

        // The three Harness threads beyond the quota waited 200 ms and got no connection.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (refused.get() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(refused.get()).isEqualTo(3);
        release.countDown();
        for (Thread thread : harnessThreads) {
            thread.join(5_000);
        }
        assertThat(quota.inUse()).isZero();
    }

    @Test
    void closingTwiceReturnsThePermitOnceAndAFailedOpenReturnsItToo() throws Exception {
        DataSource pool = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(pool.getConnection()).thenReturn(connection).thenThrow(new java.sql.SQLException("pool down"));
        HarnessDbQuota quota = new HarnessDbQuota(1, Duration.ofMillis(100));
        HarnessDbQuotaDataSource dataSource = new HarnessDbQuotaDataSource(pool, quota);

        Connection held = HarnessDbQuota.runAsHarness(() -> {
            try {
                return dataSource.getConnection();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(quota.inUse()).isEqualTo(1);
        held.close();
        held.close();
        verify(connection, org.mockito.Mockito.times(2)).close();
        assertThat(quota.inUse()).isZero();

        CompletableFuture<Throwable> failed = CompletableFuture.supplyAsync(() -> {
            try {
                dataSource.getConnection();
                return null;
            } catch (Exception ex) {
                return ex;
            }
        }, runnable -> new Thread(runnable, HarnessDbQuota.THREAD_PREFIX + "context-1").start());
        assertThat(failed.get(5, TimeUnit.SECONDS)).hasMessage("pool down");
        assertThat(quota.inUse()).isZero();
    }

    @Test
    void aHarnessThreadWithoutAPermitGetsATransientError() throws Exception {
        DataSource pool = mock(DataSource.class);
        when(pool.getConnection()).thenAnswer(invocation -> mock(Connection.class));
        HarnessDbQuota quota = new HarnessDbQuota(1, Duration.ofMillis(50));
        HarnessDbQuotaDataSource dataSource = new HarnessDbQuotaDataSource(pool, quota);

        HarnessDbQuota.runAsHarness(() -> {
            try (Connection first = dataSource.getConnection()) {
                assertThatThrownBy(dataSource::getConnection)
                        .isInstanceOf(SQLTransientConnectionException.class)
                        .hasMessageContaining("Harness database quota exhausted");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(quota.inUse()).isZero();
    }
}
