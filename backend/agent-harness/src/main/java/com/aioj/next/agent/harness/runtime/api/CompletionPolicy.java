package com.aioj.next.agent.harness.runtime.api;

/**
 * When a model answer may end the run. A stateful workflow can require that
 * its authoritative final tool succeeded first, so that model prose saying
 * "done" cannot end it early.
 */
public interface CompletionPolicy {
    /** Whether an answer without tool calls may end the run now; if not, the instruction is appended. */
    Verdict onAnswer(LoopContext context, String answer);

    /** Whether the run met its goal; asked once more when the budget ends the run. */
    boolean satisfied(LoopContext context);

    /**
     * What the model is told when the budget ends before the goal was met; null keeps
     * the loop's own wording.
     */
    default String budgetEndInstruction() {
        return null;
    }

    record Verdict(boolean accept, String instruction) {
        public static Verdict accepted() {
            return new Verdict(true, null);
        }

        public static Verdict continueWith(String instruction) {
            return new Verdict(false, instruction);
        }
    }

    static CompletionPolicy anyAnswer() {
        return new CompletionPolicy() {
            @Override
            public Verdict onAnswer(LoopContext context, String answer) {
                return Verdict.accepted();
            }

            @Override
            public boolean satisfied(LoopContext context) {
                return true;
            }
        };
    }

    static CompletionPolicy requireSuccessfulTool(String toolName, String instruction) {
        if (toolName == null || toolName.isBlank() || instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("tool name and instruction are required");
        }
        return new CompletionPolicy() {
            @Override
            public Verdict onAnswer(LoopContext context, String answer) {
                return satisfied(context) ? Verdict.accepted() : Verdict.continueWith(instruction);
            }

            @Override
            public boolean satisfied(LoopContext context) {
                return context.succeededTools().contains(toolName);
            }
        };
    }
}
