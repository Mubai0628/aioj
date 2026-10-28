package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.agent.channel.HarnessEventChannel;
import com.aioj.next.ai.agent.channel.LegacyMemoryJobMigration;
import com.aioj.next.ai.persistence.mapper.AiAsyncJobMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryJobMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AiMemorySpringConstructorTest {
    @Test
    void servicesWithTestingConstructorsDeclareAutowiredProductionConstructors() throws NoSuchMethodException {
        assertThat(AiMemoryEventService.class.getConstructor(AiMemoryEventPayloadSanitizer.class,
                ObjectMapper.class, HarnessEventChannel.class).isAnnotationPresent(Autowired.class)).isTrue();
        assertThat(LegacyMemoryJobMigration.class.getConstructor(AiMemoryJobMapper.class, AiAsyncJobMapper.class,
                HarnessEventChannel.class, ObjectMapper.class, TransactionOperations.class)
                .isAnnotationPresent(Autowired.class)).isTrue();
    }
}
