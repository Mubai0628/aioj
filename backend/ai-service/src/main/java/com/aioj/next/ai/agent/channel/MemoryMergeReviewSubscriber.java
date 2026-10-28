package com.aioj.next.ai.agent.channel;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.memory.AiMemoryMergeJobHandler;
import org.springframework.stereotype.Component;

/** MEMORY_CANDIDATE_PROPOSED → the memory merge review (a PAID model call), formerly an ai_memory_jobs job. */
@Component
public class MemoryMergeReviewSubscriber extends LegacyMemoryJobSubscriber {
    public MemoryMergeReviewSubscriber(AiMemoryMergeJobHandler delegate, AiProperties properties) {
        super(delegate, ChannelEventTypes.MEMORY_CANDIDATE_PROPOSED, properties);
    }
}
