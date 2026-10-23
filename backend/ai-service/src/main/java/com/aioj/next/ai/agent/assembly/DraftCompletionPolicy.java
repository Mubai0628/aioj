package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.runtime.api.CompletionPolicy;
import com.aioj.next.agent.harness.runtime.api.LoopContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stateful workflow's server-owned terminal gate for one run: model prose saying "done"
 * cannot end it before the authoritative tool (for problem drafts {@code draft.finalize})
 * has succeeded. An answer before the gate moves the rest of the run to standard tool
 * mode, where the model no longer spends its output budget on reasoning: always after an
 * empty answer, and after prose when the run may fall back.
 */
final class DraftCompletionPolicy implements CompletionPolicy {
    private static final Logger log = LoggerFactory.getLogger(DraftCompletionPolicy.class);

    private final String requiredTool;
    private final ModelClientPort port;
    private final boolean fallbackAllowed;
    private final String turnId;
    private int emptyAnswersBeforeGate;

    DraftCompletionPolicy(String requiredTool, ModelClientPort port, boolean fallbackAllowed, String turnId) {
        this.requiredTool = requiredTool;
        this.port = port;
        this.fallbackAllowed = fallbackAllowed;
        this.turnId = turnId;
    }

    @Override
    public Verdict onAnswer(LoopContext context, String answer) {
        if (satisfied(context)) {
            return Verdict.accepted();
        }
        boolean empty = answer == null || answer.isBlank();
        if (empty) {
            emptyAnswersBeforeGate++;
            log.warn("Agent run got an empty pre-finalization completion turn={} run={} step={}", turnId,
                    context.runId(), context.step());
            switchToStandardTools(context);
        } else if (fallbackAllowed) {
            switchToStandardTools(context);
        }
        return Verdict.continueWith(recoveryInstruction(empty));
    }

    @Override
    public boolean satisfied(LoopContext context) {
        return context.succeededTools().contains(requiredTool);
    }

    @Override
    public String budgetEndInstruction() {
        return "The controlled run budget ended before the required tool " + requiredTool
                + " succeeded. Do not continue designing the problem and do not expose private reasoning. "
                + "Return only a short, factual progress summary naming the remaining authoritative gate.";
    }

    /** A second empty answer before the gate ends the run; it is reported as the gate's failure. */
    boolean emptyTwiceBeforeGate() {
        return emptyAnswersBeforeGate >= 2;
    }

    private void switchToStandardTools(LoopContext context) {
        port.switchToStandardTools();
        context.warn(ModelClientPort.STANDARD_TOOL_FALLBACK);
    }

    private String recoveryInstruction(boolean emptyResponse) {
        String prefix = emptyResponse
                ? "The previous model response exhausted its answer without a tool call. "
                : "The previous response was only an unfinished progress note. ";
        return prefix
                + "Server policy does not allow this workflow to end until " + requiredTool
                + " succeeds. Continue the controlled tool loop now: inspect the latest authoritative tool result, "
                + "call the next missing draft tool, repair failed gates, and call " + requiredTool
                + " only after every gate passes. Do not answer with more design prose before using a tool.";
    }
}
