package com.aioj.next.ai.agent.profile;

import com.aioj.next.agent.harness.profile.api.ProfilePort;
import com.aioj.next.ai.agent.port.PortCalls;
import com.aioj.next.ai.agent.port.PortToolResults;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;

/**
 * The Harness profile port over ai_learning_profile. Reads use {@link PortCalls}; only ACTIVE
 * rows that are neither disabled nor deleted are visible. Explicit changes write synchronously
 * in the caller's transaction and do not publish an event.
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

    @Override
    public void update(ProfileChange change) {
        if (change == null) {
            throw new IllegalArgumentException("profile change is required");
        }
        long userId = Long.parseLong(change.subjectId());
        long itemId = Long.parseLong(change.itemId());
        if (change.clearDisabledAt()) {
            profileMapper.update(null, new UpdateWrapper<AiLearningProfileEntity>()
                    .eq("id", itemId).eq("user_id", userId)
                    .set("label", change.label()).set("state", change.state())
                    .set("confidence", change.confidence()).setSql("disabled_at = NULL")
                    .set("updated_at", change.updatedAt()));
        } else {
            AiLearningProfileEntity profile = new AiLearningProfileEntity();
            profile.id = itemId;
            profile.label = change.label();
            profile.state = change.state();
            profile.confidence = change.confidence();
            profile.disabledAt = change.disabledAt();
            profile.updatedAt = change.updatedAt();
            profileMapper.update(profile, new UpdateWrapper<AiLearningProfileEntity>()
                    .eq("id", itemId).eq("user_id", userId));
        }
    }
}
