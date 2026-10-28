package com.aioj.next.ai.agent.profile;

import com.aioj.next.agent.harness.profile.api.ProfilePort;
import com.aioj.next.ai.agent.port.PortCalls;
import com.aioj.next.ai.agent.port.PortToolResults;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;

/**
 * The Harness profile port over ai_learning_profiles (plan T1.6, A3.1): read-only, through
 * {@link PortCalls}. Only ACTIVE profiles that are neither disabled nor deleted are visible
 * (frozen decision Q4); each comes back as one signal (topic = category, signal = label).
 * Writing signals moves onto the event channel with T1.7 (A3.5).
 */
@Component
public class HarnessProfilePort implements ProfilePort {
    private static final int MAX_SIGNALS = 10;

    private final AiLearningProfileMapper profileMapper;
    private final PortCalls portCalls;

    public HarnessProfilePort(AiLearningProfileMapper profileMapper, PortCalls portCalls) {
        this.profileMapper = profileMapper;
        this.portCalls = portCalls;
    }

    @Override
    public List<ProfileSignal> search(ProfileQuery query) {
        long userId = Long.parseLong(query.subjectId());
        QueryWrapper<AiLearningProfileEntity> wrapper = new QueryWrapper<AiLearningProfileEntity>()
                .eq("user_id", userId)
                .eq("state", "ACTIVE")
                .isNull("deleted_at")
                .isNull("disabled_at");
        if (query.topic() != null && !query.topic().isBlank()) {
            wrapper.eq("category", query.topic().trim());
        }
        wrapper.orderByDesc("updated_at").last("LIMIT " + Math.min(MAX_SIGNALS, Math.max(1, query.limit())));
        List<AiLearningProfileEntity> profiles = portCalls.read(PortToolResults.PROFILE, () -> profileMapper.selectList(wrapper));
        return profiles.stream()
                .map(profile -> new ProfileSignal(query.subjectId(), profile.category, profile.label,
                        profile.confidence == null ? 0.0 : profile.confidence.doubleValue(),
                        "LEARNING_PROFILE:" + profile.id,
                        profile.lastEvidenceAt == null ? null
                                : profile.lastEvidenceAt.atZone(ZoneId.systemDefault()).toInstant()))
                .toList();
    }

    @Override
    public void submit(ProfileSignal signal) {
        throw new UnsupportedOperationException("profile signals are not written through the port yet (T1.7, A3.5)");
    }
}
