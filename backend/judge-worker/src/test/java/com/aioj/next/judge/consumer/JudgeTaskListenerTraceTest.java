package com.aioj.next.judge.consumer;

import com.aioj.next.common.api.TraceIds;
import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.judge.domain.JudgeResult;
import com.aioj.next.judge.domain.SandboxClient;
import com.aioj.next.judge.domain.SubmissionJudgingService;
import com.aioj.next.judge.domain.TestcasePackageCache;
import com.aioj.next.judge.monitor.JudgeRuntimeMetrics;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The judge's log lines and its judged-event push carry the submitting request's trace id (T2.5). */
class JudgeTaskListenerTraceTest {
    private final SandboxClient sandbox = mock(SandboxClient.class);
    private final SubmissionJudgingService judging = mock(SubmissionJudgingService.class);
    private final TestcasePackageCache packages = mock(TestcasePackageCache.class);
    private final JudgeTaskListener listener = new JudgeTaskListener(sandbox, judging, packages,
            mock(JudgeRuntimeMetrics.class));

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void theMessagesTraceIdIsInTheMdcWhileJudgingAndGoneAfterwards() throws Exception {
        AtomicReference<String> seen = judgeAndCapture("submit-trace-1");

        assertThat(seen.get()).isEqualTo("submit-trace-1");
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
    }

    @Test
    void aMessageWithoutAUsableTraceIdGetsAFreshOne() throws Exception {
        AtomicReference<String> seen = judgeAndCapture("n/a");

        assertThat(seen.get()).isNotEqualTo("n/a");
        assertThat(TraceIds.isValid(seen.get())).isTrue();
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
    }

    private AtomicReference<String> judgeAndCapture(String traceId) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        when(judging.startRunning(any())).thenReturn(true);
        when(packages.prepareActivePackage(any())).thenReturn(Optional.empty());
        when(sandbox.judge(any())).thenAnswer(invocation -> {
            seen.set(MDC.get(TraceIds.MDC_KEY));
            return JudgeResult.systemError("stub");
        });
        listener.onMessage(new JudgeTaskMessage(11L, 22L, 7L, null, null, null, null, null, "cpp", traceId, 1000, 65536L),
                new Message(new byte[0], new MessageProperties()), mock(Channel.class));
        return seen;
    }
}
