# AI problem-draft Agent architecture

[中文](../zh/problem-draft-agent-architecture.md)

> [!NOTE]
> Current status: the local real-provider-plus-Docker path is end-to-end verified; production remains `IMPLEMENTED_UNVERIFIED`, and production node `aioj_a` has not been updated. Real DeepSeek runs have twice taken isolated CF 800, 1400, and 1800 workspaces through draft generation, samples, package generation, full-package execution, and `draft.finalize`: on 2026-08-15, and again on 2026-10-29 after execution moved to agent-executor and the tools gained the shared feedback. Every draft remains `PENDING_REVIEW`; none was approved, imported, or published.

## Goal and boundary

The target AI problem-draft path no longer lets a fixed seven-stage provider pipeline decide every step. It reuses Agent Core V3: the model analyzes the current draft, selects the next tool, and chooses a repair direction; the server always owns identity, workspace, authorization, budgets, Sandbox orchestration, artifact ownership, validation evidence, and the final gate. Legacy synchronous, SSE, and regenerate compatibility entrypoints now create and wait on the durable Agent job, are marked deprecated, and no longer run an independent generation engine.

```text
administration UI
  -> ProblemDraftAgentCoordinator
  -> AgentRuntime
  -> agent-harness loop and tool runtime
  -> agent-executor
  -> agent-sandbox (go-judge)

ai-service -> problem-service -> problem and testcase package
```

The capability is restricted to teachers and administrators and retains the draft → human review/approval → import lifecycle. `draft.finalize` may create only an immutable `PENDING_REVIEW` draft version. The model cannot approve, import, activate, or publish a problem.

## Workspace and context isolation

Each new problem gets an isolated durable `workspaceId`. Later optimization, human edits, validation, testcase artifacts, and import remain bound to that workspace. The server also binds the creator, dedicated conversation, current working state, state version, current immutable draft, Agent run, and artifact; model arguments cannot override these identities.

- The dedicated conversation source is always `problem_draft_agent`. It cannot enter student-tutor `ai_tutor` conversations, long-term memory, learning profiles, or cross-problem retrieval.
- A new turn loads only safe messages from this workspace, the current user request, and server policy. Internal tool sub-turns and raw reasoning are not restored as later-turn context.
- The working state uses optimistic `stateVersion` concurrency. `draft.state.patch` must carry the returned `expectedStateVersion`; stale writes conflict.
- Every material edit recalculates fingerprints. Changes to the statement, samples, solutions, generator, or limits make prior execution acceptance `STALE`; title-only or tag-only edits do not rerun the package.
- Administration-side human edits also update the same working state and are saved as a new immutable draft version. Edits are rejected while an Agent turn is running so the teacher and model cannot overwrite one another. The affected Agent gates must pass again before the edited version can be imported.
- Failure, timeout, or budget exhaustion preserves the working state and moves the workspace to `NEEDS_ATTENTION`, allowing the teacher to continue in the same problem context.

## Model loop and DeepSeek protocol

DeepSeek thinking-mode `reasoning_content` is an in-memory transport field for the current tool loop only. Subsequent requests in that loop echo the corresponding assistant message's complete `content`, `reasoning_content`, and `tool_calls`; thinking requests omit the ineffective `temperature` parameter. This follows the public [DeepSeek Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode) and [DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls) protocols.

Raw reasoning is never written to the database, logs, general audit, or frontend and is never replayed across user turns. Persistence is limited to safe user messages, final summaries, safe tool summaries, resource metrics, and states.

If thinking is disabled by configuration, unsupported by the model, or explicitly rejected as a capability, the runtime switches to the standard `tools/tool_calls` loop and records `STANDARD_TOOL_FALLBACK`. Server-side JSON Schema validation remains authoritative; the implementation does not depend on `/beta` strict mode.

The default configurable problem-draft budget is:

| Budget | Default |
| --- | ---: |
| model steps | 20 |
| tool calls | 16 |
| Sandbox operations | 8 |
| testcase generations | 2 |
| full-package acceptance runs | 2 |
| total run deadline | 15 minutes |

Code and package operations run sequentially with the current single-Sandbox topology. Reaching any cap never bypasses a gate; it ends the turn and preserves diagnosable state.

## Nine controlled tools

| Tool | Controlled responsibility |
| --- | --- |
| `draft.state.get` | return current working state, version, and authoritative validation summary |
| `draft.state.patch` | apply a typed field patch with the expected state version |
| `draft.static.validate` | run schema, consistency, algorithm-difficulty, and complexity checks |
| `draft.code.execute` | run a standard solution, reference solution, or bounded probe in Sandbox and return an opaque `executionId` |
| `draft.execution.get` | page through bounded output, state, time, memory, process peak, and exit status |
| `draft.samples.run` | execute every public sample with current code and limits |
| `draft.testcase.generate` | run the current Python generator in Sandbox and create a testcase artifact |
| `draft.testcase.run_package` | execute the complete package with formal judge comparison semantics and an independent reference solution for high-risk problems |
| `draft.finalize` | check every gate at the current fingerprint and create an immutable draft for human review |

Tools accept only business arguments the model is allowed to choose. Workspace, user, run, draft, artifact, and authority are server-bound. `executionId` and `artifactId` are checked for workspace ownership and expiry; internal paths, Sandbox file IDs, and credentials are never exposed to the model.

The execution tools (`draft.code.execute`, `draft.samples.run`, `draft.testcase.run_package`, and `draft.testcase.generate`) each return, besides their existing fields, a `feedback` of one shared shape: verdict, time, memory, process peak, and compile diagnostics or a stderr summary; batches of cases report only pass counts, failure kinds, and the largest resource use. Package generation runs no judged program, so its `feedback` has no verdict or resource figures, only the status, error codes, and case counts; the bounded error messages in its existing fields stay, because the model repairs its generator from them.

## Execution and audit

ai-service never talks directly to Docker or go-judge. Every C++17, Java 17, and Python 3 execution passes through agent-executor to agent-sandbox and is bounded by source, input, batch, file, package, time, memory, process, and output limits. A program run gets at most 256 MiB and a compile or generator script at most 384 MiB; a request above either cap is rejected, and a draft whose memory limit is above the run cap is reported as exceeding the draft sandbox's limit rather than as a memory-limit failure. Full-package execution reuses the formal STANDARD comparison semantics. Hidden cases return counts, failure classifications, and aggregate resource data rather than complete hidden input or output.

agent-sandbox defaults both `ES_OUTPUT_LIMIT` and `ES_COPY_OUT_LIMIT` (`AGENT_SANDBOX_OUTPUT_LIMIT` and `AGENT_SANDBOX_COPY_OUT_LIMIT`) to 64 MiB, which must not be lower than the Agent's 50 MiB testcase-package ceiling. These correspond to go-judge's process-output and copy-out limits. If execution reports success without a cached package handle, agent-executor returns the structured `PACKAGE_CACHE_FILE_MISSING` failure instead of treating the missing artifact as success. See the [official go-judge documentation](https://github.com/criyle/go-judge) for the parameter semantics.

The harness tool runtime writes a durable `RUNNING` audit record before real execution, then updates status, result hash, safe summary, and resource metrics. Raw code, generators, stdin/stdout/stderr, package bytes, and provider reasoning do not enter general audit fields. Tools have real timeout and cancellation; execution results are paged, truncated, and expire.

## Two testcase-package paths

### Agent generated

The Agent runs a Python generator to produce an artifact, applies format, safety, manifest, SHA, and size checks, then executes full-package acceptance. Only when static validation, public samples, package format, and package execution all pass at the current fingerprint may the generation job complete and `draft.finalize` create the draft version.

### Manually uploaded

A manual ZIP uses workspace-scoped chunk upload. It validates chunk and whole-package SHA, manifest, path traversal, symlinks, duplicate or case-colliding paths, file and expanded sizes, compression ratio, case count, and referenced files. After format `READY`, the UI exposes two explicit paths:

1. **Agent acceptance test:** execute the entire package and expose the controlled Agent tool trace; success records `AGENT_VERIFIED`.
2. **Direct import:** skip execution acceptance only after a second confirmation and record `MANUAL_UNVERIFIED`, operator, artifact SHA, and existing failure summary.

Both paths still require an approved draft, valid static content, format `READY`, and an artifact fingerprint matching the current draft. Missing, uploading, malformed, expired, failed, or interrupted artifacts return structured blockers.

## Recoverable import

ai-service uses a caller-supplied idempotency key to drive a durable saga:

```text
PREPARING -> PACKAGE_READY -> ACTIVATED -> COMPLETED
```

A protected problem-service contract guarantees `aiGenerated=true` and retains source workspace/draft, review risk, and testcase verification mode. A new problem is prepared as `PRIVATE`. Once its package is `READY`, problem-service atomically commits problem content, activates the package, and applies target visibility. For an existing target, the old statement, visibility, and active package remain effective until the replacement package can be activated.

The update target is never accepted from the browser or model. ai-service derives it only from an imported version in the current workspace or its server-persisted source draft, then binds that target into the idempotency saga. problem-service claims a legacy target with a conditional update and returns a conflict if another workspace already owns it, preventing duplicate creation or a silent overwrite of another problem.

Problem-side workspace provenance is unique, and the import key is unique within the operator's scope. Every retry reuses the existing problem and package so it cannot duplicate a problem or split statement and package versions.

## Data model

Flyway `V66__problem_draft_agent_workspaces.sql` is additive and introduces:

- isolated workspace and working state;
- bounded execution results;
- manual package upload sessions and chunk metadata;
- recoverable import sagas;
- workspace, version, fingerprint, risk, provenance, validation, and safe-audit fields on generation jobs, drafts, artifacts, Agent runs, tool calls, and problems.

The unwired job/attempt/artifact tables from V49–V51 remain historical structures and are not part of the active path. The implementation reuses `ai_conversations/ai_turns/ai_messages` and `ai_agent_runs/ai_tool_calls/ai_context_manifests`; it does not introduce a second conversation store or Agent loop.

## Administration experience

Running, failed, and attention-required tasks open the same Agent workspace. It shows model, thinking/compatibility mode, run time, safe decision summaries, tool status, resource metrics, problem-local conversation, and a continue-optimization input. Raw chain of thought is not shown.

Draft detail exposes preview, validation, testcase, Agent process, and editing. Desktop uses tables and timelines; mobile uses cards and a single-column tool detail. The client polls incrementally for new run/tool state, and every long ID remains a string.

For `HARD`, `CHALLENGE`, or requested CF rating ≥ 1701, administration surfaces persist this warning:

> This problem was generated by AI and is difficult. Its statement, complexity, standard solution, and testcase coverage still require careful human review.

Every `MANUAL_UNVERIFIED` import displays a stronger unaccepted-package warning regardless of difficulty. Student surfaces retain the existing AI label only and do not expose administration risk or tool detail.

## Acceptance and delivery status

The intended local verification scope covers DeepSeek replay and fallback, budget/timeout/cancellation, ABAC, workspace isolation, fingerprint invalidation, all nine tools, three Sandbox languages, hostile ZIPs, generated/manual packages, import-saga crash points, and desktop plus 400×858 and 440×956 administration viewports.

The current real local acceptance evidence is:

| Requested difficulty | Mode and recovery path | Public samples | Full package | Artifact size | Final state |
| --- | --- | ---: | ---: | ---: | --- |
| CF 800 | one `THINKING_TOOL_LOOP` turn; the model found and repaired its generator defect | 3/3 | 8/8 | 8.1 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |
| CF 1400 | one turn with safe `STANDARD_TOOL_FALLBACK` | 3/3 | 12/12 | 1.2 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |
| CF 1800 | two earlier turns preserved the same isolated workspace; after the copy-out correction the final turn ran only state → generate → run-package → finalize | 3/3 | 16/16, including an independent reference | 20.4 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |

The investigation fixed two production-path defects. First, the old runtime accepted ordinary prose after any tool call as completion; draft runs must now successfully call `draft.finalize`, otherwise state is preserved and the loop continues or falls back safely. Second, the former local 16 MiB copy-out limit was below the 50 MiB package ceiling, so the 1800 artifact was generated without a retrievable handle; the limits are now aligned at 64 MiB with structured diagnostics. Real Chrome verified draft content, the complete safe tool trace, the high-difficulty warning, and no horizontal overflow at desktop, 400×858, or 440×956. Raw reasoning was not exposed. This proves only the local single-concurrency path, not production capacity; `aioj_a` was neither connected to nor updated.

On 2026-10-29 the same three requests were accepted again. Execution now goes through agent-executor → agent-sandbox, and the judge Sandbox received no request other than its own healthcheck meanwhile:

| Requested difficulty | Turns and mode | Public samples | Full package | Artifact size | Final state |
| --- | --- | ---: | ---: | ---: | --- |
| CF 800 | three turns: the acceptance side refused the first turn's package for an unsafe compression ratio and the budget ran out; the provider answered the second with HTTP 400; the third completed in `THINKING_TOOL_LOOP` | 3/3 | 8/8 | 0.07 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |
| CF 1400 | two `THINKING_TOOL_LOOP` turns: the first passed the full package but used up its tool budget before finalizing | 3/3 | 12/12 | 2.5 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |
| CF 1800 | two `THINKING_TOOL_LOOP` turns: the first used up its tool budget after generating the package | 3/3 | 16/16, including an independent reference | 10.3 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |

This acceptance fixed two defects. When agent-executor refuses a package, the model now receives the reason instead of an opaque execution error. When the tool budget runs out in the middle of a turn with several tool calls, the remaining calls now get a budget-exhausted result, so the provider no longer rejects the next request with HTTP 400 for missing tool messages. At the time the two sides applied the compression-ratio rule differently: generation checked only entries of 1 MiB or more and acceptance checked every entry, so a package that passed generation could be refused at acceptance. The same day both sides were aligned: each limits the ratio (at most 200:1) only for entries of 1 MiB or more after expansion; smaller entries are bounded by the per-entry and total limits, which count the bytes actually written.
