package com.aioj.next.ai.agent.port;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortCallsTest {

    @Test
    void aReadThatAnswersInTimeComesBack() {
        try (PortCalls calls = PortCalls.withTimeout(Duration.ofSeconds(2))) {
            assertThat(calls.read("memory", () -> "claims")).isEqualTo("claims");
        }
    }

    @Test
    void aSlowReadTimesOutAndIsInterrupted() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        try (PortCalls calls = PortCalls.withTimeout(Duration.ofMillis(100))) {
            assertThatThrownBy(() -> calls.read("memory", () -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                }
                return "late";
            })).isInstanceOfSatisfying(PortUnavailableException.class, ex -> {
                assertThat(ex.port()).isEqualTo("memory");
                assertThat(ex.reason()).isEqualTo("timeout");
            });
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aFailingReadIsUnavailableWithItsCause() {
        try (PortCalls calls = PortCalls.withTimeout(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> calls.read("profile", () -> {
                throw new IllegalStateException("db down");
            })).isInstanceOfSatisfying(PortUnavailableException.class, ex -> {
                assertThat(ex.reason()).isEqualTo("error");
                assertThat(ex.getCause()).hasMessage("db down");
            });
        }
    }

    @Test
    void aFullPoolRejectsInsteadOfQueueingWithoutLimit() throws Exception {
        try (PortCalls calls = new PortCalls(1, 0, Duration.ofSeconds(5), UnaryOperator.identity())) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> calls.read("memory", () -> {
                started.countDown();
                release.await();
                return "held";
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> calls.read("memory", () -> "extra"))
                    .isInstanceOfSatisfying(PortUnavailableException.class, ex -> assertThat(ex.reason()).isEqualTo("rejected"));

            release.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held");
        }
    }
}
