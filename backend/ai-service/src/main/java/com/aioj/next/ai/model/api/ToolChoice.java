package com.aioj.next.ai.model.api;

/**
 * AUTO: the model decides. REQUIRED: the turn must include at least one tool call;
 * only providers whose capabilities say so get it natively, elsewhere the Agent
 * loop simulates it.
 */
public enum ToolChoice {
    AUTO,
    REQUIRED
}
