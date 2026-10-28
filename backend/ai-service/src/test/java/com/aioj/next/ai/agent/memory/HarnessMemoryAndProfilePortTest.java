package com.aioj.next.ai.agent.memory;

import com.aioj.next.agent.harness.memory.api.MemoryPort;
import com.aioj.next.agent.harness.profile.api.ProfilePort;
import com.aioj.next.ai.agent.port.PortCalls;
import com.aioj.next.ai.agent.port.PortUnavailableException;
import com.aioj.next.ai.agent.profile.HarnessProfilePort;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HarnessMemoryAndProfilePortTest {
    private final AiMemoryClaimMapper claimMapper = mock(AiMemoryClaimMapper.class);
    private final AiMemoryEvidenceMapper evidenceMapper = mock(AiMemoryEvidenceMapper.class);
    private final AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
    private final PortCalls portCalls = PortCalls.withTimeout(Duration.ofMillis(300));
    private final HarnessMemoryPort memory = new HarnessMemoryPort(claimMapper, evidenceMapper, portCalls);
    private final HarnessProfilePort profile = new HarnessProfilePort(profileMapper, portCalls);

    @AfterEach
    void close() {
        portCalls.close();
    }

    @Test
    void memoryReadsActiveClaimsAndTheirEvidence() {
        AiMemoryClaimEntity claim = new AiMemoryClaimEntity();
        claim.id = 11L;
        claim.canonicalText = "讲题先给思路再给代码";
        claim.confidence = new BigDecimal("0.80");
        when(claimMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(claim));
        AiMemoryEvidenceEntity row = new AiMemoryEvidenceEntity();
        row.id = 21L;
        row.evidenceType = "USER_STATEMENT";
        row.evidenceText = "记住：先讲思路";
        when(evidenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(row));

        assertThat(memory.search(new MemoryPort.MemoryQuery("7", null, 5)))
                .containsExactly(new MemoryPort.MemoryHit("11", "讲题先给思路再给代码", 0.8));
        assertThat(memory.evidence("7", "11"))
                .containsExactly(new MemoryPort.MemoryEvidence("USER_STATEMENT", "21", "记住：先讲思路"));
        assertThatThrownBy(() -> memory.propose(new MemoryPort.MemoryCandidate("7", "PREFERENCE", "x", List.of())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void profileReadsActiveEntriesAsSignals() {
        AiLearningProfileEntity entry = new AiLearningProfileEntity();
        entry.id = 31L;
        entry.category = "WEAKNESS";
        entry.label = "二分边界";
        entry.confidence = new BigDecimal("0.60");
        when(profileMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(entry));

        List<ProfilePort.ProfileSignal> signals = profile.search(new ProfilePort.ProfileQuery("7", null, 5));

        assertThat(signals).singleElement().satisfies(signal -> {
            assertThat(signal.topic()).isEqualTo("WEAKNESS");
            assertThat(signal.signal()).isEqualTo("二分边界");
            assertThat(signal.weight()).isEqualTo(0.6);
            assertThat(signal.evidenceRef()).isEqualTo("LEARNING_PROFILE:31");
        });
    }

    @Test
    void aSlowStoreMakesBothPortsUnavailable() {
        when(claimMapper.selectList(any(QueryWrapper.class))).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return List.of();
        });
        when(profileMapper.selectList(any(QueryWrapper.class))).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> memory.search(new MemoryPort.MemoryQuery("7", "二分", 5)))
                .isInstanceOfSatisfying(PortUnavailableException.class, ex -> assertThat(ex.reason()).isEqualTo("timeout"));
        assertThatThrownBy(() -> profile.search(new ProfilePort.ProfileQuery("7", null, 5)))
                .isInstanceOfSatisfying(PortUnavailableException.class, ex -> assertThat(ex.reason()).isEqualTo("error"));
    }
}
