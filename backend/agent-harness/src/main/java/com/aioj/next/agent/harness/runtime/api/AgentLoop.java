package com.aioj.next.agent.harness.runtime.api;

/** Runs one agent loop to its typed end; see {@link RunSpec} for what a run needs. */
public interface AgentLoop {
    RunResult run(RunSpec spec);
}
