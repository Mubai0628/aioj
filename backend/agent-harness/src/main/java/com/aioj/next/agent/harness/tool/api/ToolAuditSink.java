package com.aioj.next.agent.harness.tool.api;

/**
 * Where tool-call audit records go. {@link #start} runs before execution and
 * returns the sink's own handle; calls rejected before execution (unknown
 * tool, bad arguments, denied) get one {@link #rejected} record instead. A
 * failed audit write must not fail the call.
 */
public interface ToolAuditSink {
    ToolAuditSink NONE = new ToolAuditSink() {
        @Override
        public Object start(ToolExecutionContext context, int callSeq, ToolDescriptor descriptor,
                            ToolCallRequest call, String decisionId) {
            return null;
        }

        @Override
        public void complete(Object handle, ToolOutcome outcome) {
        }

        @Override
        public void rejected(ToolExecutionContext context, int callSeq, ToolCallRequest call, ToolOutcome outcome) {
        }
    };

    Object start(ToolExecutionContext context, int callSeq, ToolDescriptor descriptor, ToolCallRequest call,
                 String decisionId);

    void complete(Object handle, ToolOutcome outcome);

    void rejected(ToolExecutionContext context, int callSeq, ToolCallRequest call, ToolOutcome outcome);
}
