package com.aioj.next.ai.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexAfterCommitTest {

    @Test
    void indexingQueuedInsideTheScopeRunsAfterTheWork() {
        List<String> order = new ArrayList<>();

        String result = IndexAfterCommit.run(() -> {
            assertThat(IndexAfterCommit.deferIfOpen(() -> order.add("index"))).isTrue();
            order.add("write");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(order).containsExactly("write", "index");
        assertThat(IndexAfterCommit.deferIfOpen(() -> order.add("late"))).as("no scope is open any more").isFalse();
    }

    @Test
    void whenTheWorkFailsTheQueueIsDropped() {
        List<String> order = new ArrayList<>();

        assertThatThrownBy(() -> IndexAfterCommit.run(() -> {
            IndexAfterCommit.deferIfOpen(() -> order.add("index"));
            throw new IllegalStateException("rolled back");
        })).hasMessage("rolled back");

        assertThat(order).isEmpty();
    }

    @Test
    void aNestedScopeFlushesOnceAtTheOuterEnd() {
        List<String> order = new ArrayList<>();

        IndexAfterCommit.run(() -> {
            IndexAfterCommit.run(() -> {
                IndexAfterCommit.deferIfOpen(() -> order.add("inner index"));
                return null;
            });
            order.add("outer write");
            return null;
        });

        assertThat(order).containsExactly("outer write", "inner index");
    }
}
