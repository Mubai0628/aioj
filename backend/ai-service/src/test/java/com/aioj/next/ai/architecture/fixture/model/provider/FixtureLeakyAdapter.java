package com.aioj.next.ai.architecture.fixture.model.provider;

import com.aioj.next.ai.architecture.fixture.model.internal.FixtureModelInternals;
import com.aioj.next.ai.domain.AiModelScope;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Rule fixture: a model-package class that breaks the boundary in two ways. */
public class FixtureLeakyAdapter {
    BaseMapper<Object> mapper;
    AiModelScope scope;
    int retries = FixtureModelInternals.retries();
}
