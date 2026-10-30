package com.aioj.next.judge.monitor;

import com.aioj.next.judge.config.JudgeQueueConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** S5 queue depth: what waits in the judge queue and its dead-letter queue (T2.5). */
class JudgeQueueDepthMetricsTest {
    private final AmqpAdmin admin = mock(AmqpAdmin.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JudgeQueueDepthMetrics metrics = new JudgeQueueDepthMetrics(admin, registry, false);

    @Test
    void bothQueuesAreGaugedAndUnknownUntilTheFirstAnswer() {
        assertThat(depth("judge")).isNaN();
        assertThat(depth("dead")).isNaN();
    }

    @Test
    void aRefreshReadsTheWaitingMessagesOfEachQueue() {
        when(admin.getQueueInfo(JudgeQueueConfig.JUDGE_QUEUE)).thenReturn(new QueueInformation(JudgeQueueConfig.JUDGE_QUEUE, 7, 1));
        when(admin.getQueueInfo(JudgeQueueConfig.JUDGE_DEAD_LETTER_QUEUE))
                .thenReturn(new QueueInformation(JudgeQueueConfig.JUDGE_DEAD_LETTER_QUEUE, 2, 0));

        metrics.refresh();

        assertThat(depth("judge")).isEqualTo(7.0);
        assertThat(depth("dead")).isEqualTo(2.0);
    }

    @Test
    void aBrokerThatDoesNotAnswerShowsAsUnknownRatherThanZero() {
        when(admin.getQueueInfo(JudgeQueueConfig.JUDGE_QUEUE)).thenReturn(new QueueInformation(JudgeQueueConfig.JUDGE_QUEUE, 7, 1));
        metrics.refresh();
        when(admin.getQueueInfo(JudgeQueueConfig.JUDGE_QUEUE)).thenThrow(new AmqpConnectException(new RuntimeException("down")));

        metrics.refresh();

        assertThat(depth("judge")).isNaN();
    }

    private double depth(String queue) {
        return registry.get("aioj.judge.queue.depth").tag("queue", queue).gauge().value();
    }
}
