package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.aioj.next.ai.persistence.entity.ProblemDraftExecutionEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftExecutionMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan S9: an execution id is read back only inside the workspace the turn is bound to and by the
 * user who ran it; an id from another workspace is not found, like one that never existed.
 */
class ProblemDraftExecutionScopeTest {
    @Test
    void anExecutionIsReadOnlyInItsWorkspaceByItsCreator() {
        ProblemDraftExecutionMapper mapper = mock(ProblemDraftExecutionMapper.class);
        ProblemDraftExecutionService service = new ProblemDraftExecutionService(mapper, mock(DraftSandboxClient.class),
                (MeterRegistry) null);
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.result("workspace-b", 7L, "exec-from-workspace-a", null, null))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<ProblemDraftExecutionEntity>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectOne(query.capture());
        assertThat(query.getValue().getSqlSegment())
                .contains("id =", "workspace_id =", "creator_user_id =", "expires_at >");
        assertThat(query.getValue().getParamNameValuePairs().values())
                .contains("exec-from-workspace-a", "workspace-b", 7L);
    }
}
