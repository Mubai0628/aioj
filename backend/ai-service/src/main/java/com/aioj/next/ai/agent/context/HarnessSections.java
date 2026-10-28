package com.aioj.next.ai.agent.context;

import com.aioj.next.agent.harness.context.api.ContextSource;
import com.aioj.next.agent.harness.context.api.ContextTurn;
import com.aioj.next.ai.agent.tool.SourceRef;
import com.aioj.next.ai.model.api.ChatMessage;
import com.aioj.next.ai.model.api.ChatRole;

import java.util.List;

/**
 * Carries ai-service's context sections through the Harness context assembly and back, field
 * for field: type, priority, atomicity, the token estimate as computed here, trust, sources,
 * text and conversational turns. A conversational section comes back without text, as it went.
 */
final class HarnessSections {
    private HarnessSections() {
    }

    static com.aioj.next.agent.harness.context.api.ContextSection toHarness(ContextSection section) {
        List<ContextTurn> turns = section.messages().stream()
                .map(message -> new ContextTurn(message.role() == ChatRole.USER
                        ? ContextTurn.Role.USER : ContextTurn.Role.ASSISTANT, message.content()))
                .toList();
        List<ContextSource> sources = section.sources().stream()
                .map(source -> new ContextSource(source.type(), source.id()))
                .toList();
        return new com.aioj.next.agent.harness.context.api.ContextSection(section.type().name(),
                com.aioj.next.agent.harness.context.api.TrustLevel.valueOf(section.trustLevel().name()),
                section.priority(), section.tokenEstimate(), section.atomic(), section.content(), turns, sources, null);
    }

    static List<ContextSection> fromHarness(List<com.aioj.next.agent.harness.context.api.ContextSection> sections) {
        return sections.stream().map(HarnessSections::fromHarness).toList();
    }

    static ContextSection fromHarness(com.aioj.next.agent.harness.context.api.ContextSection section) {
        List<ChatMessage> messages = section.turns().stream()
                .map(turn -> turn.role() == ContextTurn.Role.USER
                        ? ChatMessage.user(turn.content())
                        : ChatMessage.assistant(turn.content(), List.of()))
                .toList();
        List<SourceRef> sources = section.sources().stream()
                .map(source -> new SourceRef(source.type(), source.id()))
                .toList();
        return new ContextSection(ContextSectionType.valueOf(section.type()), section.priority(), section.atomic(),
                section.tokenEstimate(), TrustLevel.valueOf(section.trust().name()), sources,
                messages.isEmpty() ? section.content() : null, messages);
    }
}
