package com.aioj.next.judge.domain;

import com.aioj.next.common.metrics.SandboxRequestMeter;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.sandbox.client.Command;
import com.aioj.next.sandbox.client.GoJudgeClient;
import com.aioj.next.sandbox.client.LanguageProfile;
import com.aioj.next.sandbox.client.Limits;
import com.aioj.next.sandbox.client.RunResult;
import com.aioj.next.sandbox.client.SandboxConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Judging's side of go-judge beyond the test runs: compiling a submission or checker
 * and dropping its cached executable. Agent tool execution left judge-worker in plan
 * T1.10 (it goes to agent-executor), so this sandbox runs formal judging only. The
 * protocol, language table and limit rules come from sandbox-client (plan T1.8); the
 * requests sent are pinned by SandboxRequestCharacterizationTest.
 */
@Component
public class SandboxExecutionClient {

    private final JudgeWorkerProperties properties;

    private final GoJudgeClient sandbox;

    /** Without meters, for tests that build the client by hand. */
    public SandboxExecutionClient(JudgeWorkerProperties properties) {
        this(properties, new SandboxRequestMeter(null));
    }

    @Autowired
    public SandboxExecutionClient(JudgeWorkerProperties properties, ObjectProvider<MeterRegistry> meters) {
        this(properties, new SandboxRequestMeter(meters.getIfAvailable()));
    }

    private SandboxExecutionClient(JudgeWorkerProperties properties, SandboxRequestMeter meter) {
        this.properties = properties;
        this.sandbox = new GoJudgeClient(sandboxConfig(properties), meter::record);
    }

    /** go-judge connection settings from judge-worker's properties. */
    static SandboxConfig sandboxConfig(JudgeWorkerProperties properties) {
        return new SandboxConfig(properties.getSandboxEndpoint(), properties.getSandboxToken(),
                properties.getSandboxTimeout(), properties.getSandboxReadTimeout());
    }

    public CompileOutcome compileSource(String language, String sourceCode, long cpuLimitNs, long memoryLimitBytes) {
        LanguageProfile lang = LanguageProfile.of(language);
        if (!lang.requiresCompile()) {
            return CompileOutcome.success(null, 0L, 0L, null, 0, 0L, 0);
        }
        RunResult result = sandbox.run(Command.builder(lang.compileArgs())
                .env(lang.envVars())
                .standardFiles("", stdoutBaseCollectLimitBytes(), stderrCollectLimitBytes())
                .limits(cpuLimitNs, Limits.wallClockLimit(cpuLimitNs), memoryLimitBytes, processLimit(50))
                .copyInContent(lang.sourceFileName(), sourceCode == null ? "" : sourceCode)
                .copyOutCached(List.of(lang.executableName()))
                .build());
        Long timeMs = Limits.nanosToMillis(result.time());
        Long memoryKb = Limits.bytesToKb(result.memory());
        Long runTimeMs = Limits.nanosToMillis(result.runTime());
        String stderr = result.file("stderr");
        if (!result.accepted()) {
            return CompileOutcome.failed(firstText(stderr, result.error(), result.status()), timeMs, memoryKb,
                    stderr, result.exitStatus(), runTimeMs, result.procPeak()).withSandboxStatus(result.status());
        }
        String fileId = result.cachedFileId(lang.executableName());
        if (!StringUtils.hasText(fileId)) {
            return CompileOutcome.failed("Compile succeeded but sandbox did not return cached fileId",
                    timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs, result.procPeak())
                    .withSandboxStatus("Internal Error");
        }
        return CompileOutcome.success(fileId, timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs,
                result.procPeak()).withSandboxStatus(result.status());
    }

    public void deleteCachedFile(String fileId) {
        sandbox.deleteFile(fileId);
    }

    static int stdoutBaseCollectLimitBytes(int configuredLimit) {
        return Limits.stdoutBaseCollectLimitBytes(configuredLimit);
    }

    static int stderrCollectLimitBytes(int configuredLimit) {
        return Limits.stderrCollectLimitBytes(configuredLimit);
    }

    private int stdoutBaseCollectLimitBytes() {
        return stdoutBaseCollectLimitBytes(properties.getStdoutBaseCollectLimitBytes());
    }

    private int stderrCollectLimitBytes() {
        return stderrCollectLimitBytes(properties.getStderrCollectLimitBytes());
    }

    private int processLimit(int requested) {
        return Limits.processLimit(requested, properties.getMaxProcessLimit());
    }

    private static String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return "Sandbox execution failed";
    }

    public record CompileOutcome(boolean failed,
                                 String fileId,
                                 String message,
                                 Long timeMillis,
                                 Long memoryKb,
                                 String stderr,
                                 Integer exitStatus,
                                 Long runTimeMillis,
                                 Integer procPeak,
                                 String sandboxStatus) {
        public CompileOutcome(boolean failed, String fileId, String message, Long timeMillis, Long memoryKb,
                              String stderr, Integer exitStatus, Long runTimeMillis, Integer procPeak) {
            this(failed, fileId, message, timeMillis, memoryKb, stderr, exitStatus, runTimeMillis, procPeak, null);
        }

        public CompileOutcome(boolean failed, String fileId, String message, Long timeMillis, Long memoryKb,
                              String stderr, Integer exitStatus, Long runTimeMillis) {
            this(failed, fileId, message, timeMillis, memoryKb, stderr, exitStatus, runTimeMillis, null);
        }

        CompileOutcome withSandboxStatus(String status) {
            return new CompileOutcome(failed, fileId, message, timeMillis, memoryKb, stderr, exitStatus,
                    runTimeMillis, procPeak, status);
        }

        static CompileOutcome success(String fileId, Long timeMillis, Long memoryKb, String stderr,
                                      Integer exitStatus, Long runTimeMillis, Integer procPeak) {
            return new CompileOutcome(false, fileId, "Accepted", timeMillis, memoryKb, stderr,
                    exitStatus, runTimeMillis, procPeak);
        }

        static CompileOutcome failed(String message, Long timeMillis, Long memoryKb, String stderr,
                                     Integer exitStatus, Long runTimeMillis, Integer procPeak) {
            return new CompileOutcome(true, null, message, timeMillis, memoryKb, stderr,
                    exitStatus, runTimeMillis, procPeak);
        }
    }
}
