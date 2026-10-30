package com.aioj.next.ai.agent.runtime;

import com.aioj.next.common.error.RateLimitedException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * Answers each user has running on this instance (T2.2): the chat pool is shared by everyone, so one
 * account must not be able to take all of it. A slot is held from the turn's start until its result
 * completes, a timeout included.
 */
final class UserTurnSlots {
    static final String MESSAGE = "Too many AI answers are in progress for this account";

    private final Map<Long, Integer> running = new ConcurrentHashMap<>();
    private final IntSupplier limit;

    UserTurnSlots(IntSupplier limit) {
        this.limit = limit;
    }

    void reserve(Long userId) {
        int max = Math.max(1, limit.getAsInt());
        boolean[] granted = {false};
        running.compute(userId, (id, current) -> {
            int count = current == null ? 0 : current;
            if (count >= max) {
                return current;
            }
            granted[0] = true;
            return count + 1;
        });
        if (!granted[0]) {
            throw new RateLimitedException("ai.turns", "user", 5, MESSAGE);
        }
    }

    void release(Long userId) {
        running.computeIfPresent(userId, (id, current) -> current <= 1 ? null : current - 1);
    }

    int running(Long userId) {
        return running.getOrDefault(userId, 0);
    }
}
