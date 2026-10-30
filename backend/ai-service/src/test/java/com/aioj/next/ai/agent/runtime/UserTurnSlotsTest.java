package com.aioj.next.ai.agent.runtime;

import com.aioj.next.common.error.RateLimitedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One account cannot take the whole chat pool (T2.2). */
class UserTurnSlotsTest {
    private final UserTurnSlots slots = new UserTurnSlots(() -> 2);

    @Test
    void aThirdAnswerWaitsWhileTwoAreRunningAndOtherUsersAreNotAffected() {
        slots.reserve(7L);
        slots.reserve(7L);

        assertThatThrownBy(() -> slots.reserve(7L)).isInstanceOfSatisfying(RateLimitedException.class, refused -> {
            assertThat(refused.getMessage()).isEqualTo(UserTurnSlots.MESSAGE);
            assertThat(refused.retryAfterSeconds()).isEqualTo(5);
            assertThat(refused.limiter()).isEqualTo("ai.turns");
        });
        slots.reserve(8L);
        assertThat(slots.running(7L)).isEqualTo(2);
    }

    @Test
    void aFinishedAnswerFreesItsSlotAndTheCounterDisappearsAtZero() {
        slots.reserve(7L);
        slots.reserve(7L);
        slots.release(7L);
        slots.reserve(7L);
        slots.release(7L);
        slots.release(7L);
        slots.release(7L);

        assertThat(slots.running(7L)).isZero();
    }
}
