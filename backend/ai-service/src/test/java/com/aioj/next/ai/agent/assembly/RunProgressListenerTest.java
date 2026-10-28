package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.HarnessMessage;
import com.aioj.next.agent.harness.runtime.api.LoopContext;
import com.aioj.next.agent.harness.runtime.api.ModelCall;
import com.aioj.next.ai.agent.context.ContextManifestService;
import com.aioj.next.ai.agent.context.ContextSection;
import com.aioj.next.ai.agent.context.ContextSectionType;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.runtime.AgentRunStateMachine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunProgressListenerTest {
    private final AgentRunStateMachine stateMachine = mock(AgentRunStateMachine.class);
    private final ContextManifestService manifestService = mock(ContextManifestService.class);

    @Test
    void everyManifestListsHowTheContextWasAssembledBeforeTheLoopWarnings() {
        List<ContextSection> sections = List.of(ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true,
                TrustLevel.USER_PROVIDED, "继续"));
        RunProgressListener listener = new RunProgressListener(stateMachine, manifestService, 42L, "t-1", "m",
                "ps-1", sections, List.of("contributor_degraded:recent_turns:error", "conversation_focus_dropped"));
        LoopContext context = mock(LoopContext.class);
        when(context.warnings()).thenReturn(List.of("empty_completion_recovery"));
        ModelCall call = new ModelCall(List.of(HarnessMessage.user("继续")), List.of(), null, "chat",
                Duration.ofSeconds(30), null);

        listener.onModelCall(context, 1, call);

        verify(manifestService).record(eq("t-1"), eq(42L), eq(1), eq("m"), anyString(), eq("ps-1"), eq(sections),
                anyString(), anyString(), isNull(), isNull(),
                eq(List.of("contributor_degraded:recent_turns:error", "conversation_focus_dropped",
                        "empty_completion_recovery")));
    }

    @Test
    void withoutContextWarningsTheLoopWarningsGoThroughAsBefore() {
        RunProgressListener listener = new RunProgressListener(stateMachine, manifestService, 42L, "t-1", "m",
                "ps-1", List.of(), List.of());
        LoopContext context = mock(LoopContext.class);
        when(context.warnings()).thenReturn(List.of("STANDARD_TOOL_FALLBACK"));

        listener.onModelCall(context, 2, new ModelCall(List.of(), List.of(), null, "chat", Duration.ofSeconds(30), null));

        verify(manifestService).record(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(),
                eq(List.of("STANDARD_TOOL_FALLBACK")));
    }
}
