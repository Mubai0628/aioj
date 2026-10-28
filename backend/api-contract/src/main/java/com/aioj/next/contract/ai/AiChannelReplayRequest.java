package com.aioj.next.contract.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Replay a channel consumer's dead letters: all of them (up to {@code limit}) or one event's. */
public record AiChannelReplayRequest(
        @NotBlank @Size(max = 64) String consumer,
        Long eventId,
        Integer limit
) {
}
