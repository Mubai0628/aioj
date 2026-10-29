package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.contract.ai.AiChatRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T1.11: the turn binds what the code tools may run; the model only names a source. */
class CodeToolBindingsTest {

    @Test
    void theSelectionTheProblemAndTheSubmissionAreBound() {
        AiChatRequest request = mock(AiChatRequest.class);
        AiChatRequest.SelectionContext selection = mock(AiChatRequest.SelectionContext.class);
        when(selection.selectedText()).thenReturn("int main(){}");
        when(request.selectionContext()).thenReturn(selection);
        when(request.submissionContext()).thenReturn(new AiChatRequest.SubmissionContext(501L, "debug", true, null));
        when(request.problemId()).thenReturn(11L);

        Map<String, String> bindings = TurnCoordinator.codeToolBindings(request, new AiConversationEntity());

        assertThat(bindings).containsEntry("turn.selection", "int main(){}")
                .containsEntry("turn.problemId", "11")
                .containsEntry("turn.submissionId", "501");
    }

    @Test
    void theConversationsProblemIsTheFallbackAndNothingElseIsInvented() {
        AiChatRequest request = mock(AiChatRequest.class);
        // A mocked Long getter answers 0, not null: say "no problem" explicitly.
        when(request.problemId()).thenReturn(null);
        AiConversationEntity conversation = mock(AiConversationEntity.class);
        when(conversation.getProblemId()).thenReturn(12L);

        assertThat(TurnCoordinator.codeToolBindings(request, conversation)).isEqualTo(Map.of("turn.problemId", "12"));
    }

    /** Plan 15.5: the editor's code is bound whole, with its language; cut code would not compile. */
    @Test
    void theEditorsCodeIsBoundWholeWithItsLanguage() {
        AiChatRequest request = mock(AiChatRequest.class);
        when(request.problemId()).thenReturn(null);
        String code = "y".repeat(100_000);
        when(request.codeContext()).thenReturn(new AiChatRequest.CodeContext(" cpp ", code));

        Map<String, String> bindings = TurnCoordinator.codeToolBindings(request, new AiConversationEntity());

        assertThat(bindings).containsEntry("turn.editorCode", code).containsEntry("turn.editorLanguage", "cpp");
    }

    @Test
    void anEmptyEditorBindsNothing() {
        AiChatRequest request = mock(AiChatRequest.class);
        when(request.problemId()).thenReturn(null);
        when(request.codeContext()).thenReturn(new AiChatRequest.CodeContext("cpp", "  "));

        assertThat(TurnCoordinator.codeToolBindings(request, new AiConversationEntity())).isEmpty();
    }

    @Test
    void aHugeSelectionIsCut() {
        AiChatRequest request = mock(AiChatRequest.class);
        AiChatRequest.SelectionContext selection = mock(AiChatRequest.SelectionContext.class);
        when(selection.selectedText()).thenReturn("x".repeat(100_000));
        when(request.selectionContext()).thenReturn(selection);

        assertThat(TurnCoordinator.codeToolBindings(request, new AiConversationEntity()).get("turn.selection"))
                .hasSize(65_536);
    }
}
