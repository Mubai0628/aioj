package com.aioj.next.judge.consumer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.judge.domain.SandboxClient;
import com.aioj.next.judge.domain.SubmissionJudgingService;
import com.aioj.next.judge.domain.TestcasePackageCache;
import com.aioj.next.judge.monitor.JudgeRuntimeMetrics;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S5 log redaction in the judge (T2.5): a sandbox failure whose text quotes the submission's code
 * or a hidden test is logged by type and location only.
 */
class JudgeLogRedactionTest {
    private static final String CODE = "int canaryJudgeCode5e = 1;";
    private static final String HIDDEN = "CANARY-HIDDEN-INPUT-2b";

    @Test
    void aFailingSandboxCallDoesNotPutTheCodeOrTheTestDataInTheLog() throws Exception {
        SandboxClient sandbox = mock(SandboxClient.class);
        SubmissionJudgingService judging = mock(SubmissionJudgingService.class);
        TestcasePackageCache packages = mock(TestcasePackageCache.class);
        when(judging.startRunning(any())).thenReturn(true);
        when(packages.prepareActivePackage(any())).thenReturn(Optional.empty());
        when(sandbox.judge(any())).thenThrow(new IllegalStateException("compile failed: " + CODE,
                new IllegalArgumentException("expected output for " + HIDDEN)));
        JudgeTaskListener listener = new JudgeTaskListener(sandbox, judging, packages, mock(JudgeRuntimeMetrics.class));

        String logged = captureInfoAndAbove(() -> listener.onMessage(
                new JudgeTaskMessage(11L, 22L, 7L, null, null, null, null, null, "cpp", "trace-1", 1000, 65536L),
                new Message(new byte[0], new MessageProperties()), mock(Channel.class)));

        assertThat(logged).contains("Judge failed for submission=11", "IllegalStateException",
                "Caused by: IllegalArgumentException");
        assertThat(logged).doesNotContain("canaryJudgeCode5e", HIDDEN);
    }

    interface Work {
        void run() throws Exception;
    }

    private static String captureInfoAndAbove(Work work) throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            work.run();
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            if (event.getLevel().isGreaterOrEqual(Level.INFO)) {
                text.append(event.getFormattedMessage()).append('\n');
                if (event.getThrowableProxy() != null) {
                    text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
                }
            }
        }
        return text.toString();
    }
}
