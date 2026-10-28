package com.aioj.next.ai.agent.channel;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.memory.AiJudgedSubmissionAnalysisJobHandler;
import org.springframework.stereotype.Component;

/** SUBMISSION_JUDGED → the judged-submission analysis (a PAID model call), formerly an ai_memory_jobs job. */
@Component
public class JudgedSubmissionAnalysisSubscriber extends LegacyMemoryJobSubscriber {
    public JudgedSubmissionAnalysisSubscriber(AiJudgedSubmissionAnalysisJobHandler delegate, AiProperties properties) {
        super(delegate, ChannelEventTypes.SUBMISSION_JUDGED, properties);
    }
}
