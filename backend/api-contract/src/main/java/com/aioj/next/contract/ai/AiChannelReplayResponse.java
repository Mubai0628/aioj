package com.aioj.next.contract.ai;

public record AiChannelReplayResponse(
        String consumer,
        Long eventId,
        int requeued
) {
}
