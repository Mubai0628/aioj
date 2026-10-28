package com.aioj.next.ai.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnEventBufferTest {
    @Test
    void mergesAdjacentTextAndKeepsToolAndStepOrder() throws Exception {
        TurnEventBuffer buffer = new TurnEventBuffer(5, System.nanoTime());
        buffer.beginStep(1);
        buffer.text("先");
        buffer.text("看样例");
        buffer.toolStarted("code.run_samples");
        buffer.toolFinished("code.run_samples", "SUCCESS", 12);
        buffer.beginStep(2);
        buffer.text("结果正常");
        buffer.close();

        List<TurnEventBuffer.Event> events = buffer.awaitBatch(1);
        assertThat(events).extracting(TurnEventBuffer.Event::type).containsExactly("delta", "tool", "tool", "delta");
        assertThat(events.get(0).text()).isEqualTo("先看样例");
        assertThat(events.get(0).step()).isEqualTo(1);
        assertThat(events.get(1).phase()).isEqualTo("started");
        assertThat(events.get(2).phase()).isEqualTo("finished");
        assertThat(events.get(2).status()).isEqualTo("SUCCESS");
        assertThat(events.get(2).durationMs()).isEqualTo(12);
        assertThat(events.get(3).step()).isEqualTo(2);
        assertThat(buffer.drained()).isTrue();
    }

    @Test
    void suppressesMixedInternalProtocolAcrossChunksAndResetsPerStep() throws Exception {
        TurnEventBuffer buffer = new TurnEventBuffer(4, System.nanoTime());
        buffer.beginStep(1);
        buffer.text("下面是结果：\n");
        buffer.text("{");
        buffer.text("\"teachingDecision\":\"EXPLAIN\",\"content\":\"不应发送\"}");
        buffer.beginStep(2);
        buffer.text("安全前缀");
        buffer.text("\"");
        buffer.text("content\" 和内部字段");
        buffer.beginStep(3);
        buffer.text("示例：");
        buffer.text("`");
        buffer.text("``json\n{\"content\":\"内部\"}");
        buffer.close();

        List<TurnEventBuffer.Event> events = buffer.awaitBatch(1);
        assertThat(events).extracting(TurnEventBuffer.Event::text)
                .containsExactly("下面是结果：\n", "安全前缀", "示例：");
        assertThat(events.toString()).doesNotContain("teachingDecision", "content", "内部", "不应发送");
    }

    @Test
    void mergesWhenTheBoundIsFullWithoutDroppingText() throws Exception {
        TurnEventBuffer buffer = new TurnEventBuffer(1, System.nanoTime());
        buffer.beginStep(1);
        for (int i = 0; i < 1000; i++) {
            buffer.text("甲");
        }
        buffer.close();
        assertThat(buffer.awaitBatch(1)).singleElement().satisfies(event ->
                assertThat(event.text()).isEqualTo("甲".repeat(1000)));
    }

    @Test
    void exceedingTextLimitDiscardsSpeculativeProgressAndMarksFallback() throws Exception {
        TurnEventBuffer buffer = new TurnEventBuffer(4, System.nanoTime());
        buffer.beginStep(1);
        buffer.toolStarted("code.run_samples");
        buffer.text("甲".repeat(TurnEventBuffer.MAX_BUFFERED_TEXT_CHARS));
        buffer.text("乙");
        buffer.toolFinished("code.run_samples", "SUCCESS", 1);
        buffer.close();

        assertThat(buffer.overflowed()).isTrue();
        assertThat(buffer.awaitBatch(1)).isEmpty();
        assertThat(buffer.drained()).isTrue();
    }

    @Test
    void rejectsAnImpossibleBudgetOverflowInsteadOfSilentlyDropping() {
        TurnEventBuffer buffer = new TurnEventBuffer(1, System.nanoTime());
        buffer.beginStep(1);
        buffer.text("文字");
        assertThatThrownBy(() -> buffer.toolStarted("code.run_samples"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void surrogatePairSplitAcrossFragmentsStaysTogether() throws Exception {
        TurnEventBuffer buffer = new TurnEventBuffer(1, System.nanoTime());
        buffer.beginStep(1);
        buffer.text("前\uD83D");
        buffer.text("\uDE00后");
        buffer.close();
        assertThat(buffer.awaitBatch(1)).singleElement().satisfies(event ->
                assertThat(event.text()).isEqualTo("前😀后"));
    }

    @Test
    void detachClearsPendingEventsAndDoesNotAcceptMore() throws Exception {
        TurnEventBuffer buffer = new TurnEventBuffer(2, System.nanoTime());
        buffer.beginStep(1);
        buffer.text("部分");
        buffer.detach();
        buffer.text("后续");
        assertThat(buffer.drained()).isTrue();
        assertThat(buffer.awaitBatch(1)).isEmpty();
    }
}
