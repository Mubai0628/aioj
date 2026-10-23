package com.aioj.next.sandbox.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitsAndCodesTest {

    @Test
    void conversionsRoundUpAndSaturate() {
        assertThat(Limits.nanosToMillis(1L)).isEqualTo(1L);
        assertThat(Limits.nanosToMillis(2_000_000L)).isEqualTo(2L);
        assertThat(Limits.nanosToMillis(-5L)).isZero();
        assertThat(Limits.nanosToMillis(null)).isNull();
        assertThat(Limits.bytesToKb(1025L)).isEqualTo(2L);
        assertThat(Limits.millisToNanos(Long.MAX_VALUE / 2)).isEqualTo(Long.MAX_VALUE);
        assertThat(Limits.safeAdd(Long.MAX_VALUE - 1, 5)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void wallClockAndProcessLimits() {
        assertThat(Limits.wallClockLimit(1_000_000_000L)).as("1 s gets the 2 s grace").isEqualTo(3_000_000_000L);
        assertThat(Limits.wallClockLimit(5_000_000_000L)).as("5 s gets twice the time").isEqualTo(10_000_000_000L);
        assertThat(Limits.wallClockLimit(0L)).isEqualTo(2_000_000_001L);
        assertThat(Limits.processLimit(50, 0)).isEqualTo(50);
        assertThat(Limits.processLimit(120, 0)).isEqualTo(80);
        assertThat(Limits.processLimit(50, 30)).isEqualTo(30);
        assertThat(Limits.processLimit(0, 30)).isEqualTo(1);
    }

    @Test
    void stdoutCollectorsGrowWithTheExpectedOutputUpToTheMaximum() {
        assertThat(Limits.stdoutCollectLimitBytes(10, 64 * 1024, 64 * 1024, 4 * 1024 * 1024)).isEqualTo(64 * 1024);
        assertThat(Limits.stdoutCollectLimitBytes(1_000_000, 64 * 1024, 64 * 1024, 4 * 1024 * 1024))
                .isEqualTo(1_000_000 + 64 * 1024);
        assertThat(Limits.stdoutCollectLimitBytes(10_000_000, 64 * 1024, 64 * 1024, 4 * 1024 * 1024))
                .isEqualTo(4 * 1024 * 1024);
        assertThat(Limits.stderrCollectLimitBytes(0)).isEqualTo(64 * 1024);
    }

    @Test
    void goJudgeStatusesBecomeCodes() {
        assertThat(ResultCode.of("Accepted", null)).isEqualTo(ResultCode.ACCEPTED);
        assertThat(ResultCode.of("Signalled", null)).isEqualTo(ResultCode.RUNTIME_ERROR);
        assertThat(ResultCode.of("Non Zero Exit Status", null)).isEqualTo(ResultCode.RUNTIME_ERROR);
        assertThat(ResultCode.of("File Error", null)).isEqualTo(ResultCode.SYSTEM_ERROR);
        assertThat(ResultCode.of(null, null)).isEqualTo(ResultCode.SYSTEM_ERROR);
        List<RunResult.FileError> overCap = List.of(new RunResult.FileError("stdout", "CollectSizeExceeded", null));
        assertThat(ResultCode.of("Accepted", overCap)).isEqualTo(ResultCode.OUTPUT_LIMIT_EXCEEDED);
        RunResult result = new RunResult("Accepted", 0, 1L, 1L, 1L, 1, null, null, overCap, null);
        assertThat(result.reportedStatus()).isEqualTo("Output Limit Exceeded");
        assertThat(result.file("stdout")).isNull();
    }

    @Test
    void endpointsMayNameTheRunPathAndTimeoutsHaveAFloor() {
        assertThat(new SandboxConfig("http://sandbox:5050/run", null, null, null).baseUrl()).isEqualTo("http://sandbox:5050");
        assertThat(new SandboxConfig("http://sandbox:5050/execute", null, null, null).baseUrl()).isEqualTo("http://sandbox:5050");
        assertThat(new SandboxConfig("http://sandbox:5050/", null, null, null).baseUrl()).isEqualTo("http://sandbox:5050");
        assertThat(new SandboxConfig(" ", null, null, null).baseUrl()).isEqualTo("http://localhost:8090");
        assertThat(new SandboxConfig("x", null, Duration.ZERO, Duration.ofSeconds(-1)).readTimeout())
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theLanguageTableKnowsThreeLanguages() {
        assertThat(LanguageProfile.of("cpp").compileArgs()).containsExactly("/usr/bin/g++", "-O2", "-std=c++17",
                "main.cpp", "-o", "main");
        assertThat(LanguageProfile.of("java").executableName()).isEqualTo("Main.class");
        assertThat(LanguageProfile.of("python").requiresCompile()).isFalse();
        assertThatThrownBy(() -> LanguageProfile.of("go")).hasMessage("Unsupported language: go");
    }
}
