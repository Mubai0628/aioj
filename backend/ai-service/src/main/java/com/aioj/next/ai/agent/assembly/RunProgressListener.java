package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.LoopContext;
import com.aioj.next.agent.harness.runtime.api.ModelCall;
import com.aioj.next.agent.harness.runtime.api.ModelTool;
import com.aioj.next.agent.harness.runtime.api.ModelTurn;
import com.aioj.next.agent.harness.runtime.api.RunListener;
import com.aioj.next.ai.agent.context.BootstrapContextBuilder;
import com.aioj.next.ai.agent.context.ContextManifestService;
import com.aioj.next.ai.agent.context.ContextSection;
import com.aioj.next.ai.agent.runtime.AgentRunStateMachine;
import com.aioj.next.ai.model.api.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes one run's progress the way the old loop did: the ai_agent_runs status before
 * every model call and during tool calls, the progress after a step's tools, and one
 * ai_context_manifests row per model call. The tool-definition hash is taken from the
 * tools of the run's first call, so the answer-only call at the end shares it.
 */
final class RunProgressListener implements RunListener {
    private final AgentRunStateMachine stateMachine;
    private final ContextManifestService manifestService;
    private final long runId;
    private final String turnId;
    private final String model;
    private final String policySnapshotId;
    private final List<ContextSection> sections;
    private final List<String> contextWarnings;
    private String toolDefinitionsHash;
    private int pendingProgressStep;

    RunProgressListener(AgentRunStateMachine stateMachine, ContextManifestService manifestService, long runId,
                        String turnId, String model, String policySnapshotId, List<ContextSection> sections,
                        List<String> contextWarnings) {
        this.stateMachine = stateMachine;
        this.manifestService = manifestService;
        this.runId = runId;
        this.turnId = turnId;
        this.model = model;
        this.policySnapshotId = policySnapshotId;
        this.sections = sections;
        this.contextWarnings = contextWarnings == null ? List.of() : List.copyOf(contextWarnings);
    }

    @Override
    public void onModelCall(LoopContext context, int callSeq, ModelCall call) {
        if (pendingProgressStep > 0) {
            stateMachine.recordProgress(runId, pendingProgressStep, context.toolCallCount());
            pendingProgressStep = 0;
        }
        stateMachine.advance(runId, AgentRunStateMachine.STATUS_GENERATING);
        if (toolDefinitionsHash == null) {
            toolDefinitionsHash = ContextManifestService.sha256(
                    call.tools().stream().map(ModelTool::name).collect(Collectors.joining(",")));
        }
        manifestService.record(turnId, runId, callSeq, model, BootstrapContextBuilder.PROMPT_VERSION,
                policySnapshotId, sections, toolDefinitionsHash, ContextManifestService.sha256(contextMaterial(call)),
                null, null, warnings(context));
    }

    /** How the context was assembled (T1.6: degraded contributors, budget trims), then the loop's own warnings. */
    private List<String> warnings(LoopContext context) {
        if (contextWarnings.isEmpty()) {
            return context.warnings();
        }
        List<String> warnings = new ArrayList<>(contextWarnings);
        warnings.addAll(context.warnings());
        return warnings;
    }

    @Override
    public void onModelTurn(LoopContext context, int callSeq, ModelTurn turn) {
        if (turn.hasToolCalls()) {
            stateMachine.advance(runId, AgentRunStateMachine.STATUS_TOOL_CALLING);
            pendingProgressStep = context.step();
        }
    }

    private static String contextMaterial(ModelCall call) {
        StringBuilder material = new StringBuilder();
        for (ChatMessage message : HarnessMessages.toChat(call.messages())) {
            material.append(message.role().wireName()).append('\n');
            if (message.content() != null) {
                material.append(message.content());
            }
            material.append('\n');
        }
        return material.toString();
    }
}
