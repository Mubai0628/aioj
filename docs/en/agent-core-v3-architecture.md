# Agent Core V3 architecture

[中文](../zh/agent-core-v3-architecture.md)

## Goal

Agent Core V3 provides one auditable, constrained, and recoverable runtime for both the student AI tutor and teacher-facing AI problem drafting. The model handles language understanding and bounded planning; the server owns identity, authorization, data scope, tool execution, contest policy, budgets, and final-output validation.

The core assumption is: **the model is an untrusted planner; the server is the trusted control plane**. Model output cannot directly change permissions, read arbitrary data, execute arbitrary tools, or activate long-term memory.

## Online turn

```text
user request
  -> server creates turn and identity/policy snapshot
  -> build minimal bootstrap context
  -> model proposes an answer or tool request
  -> server authorizes and executes each tool call
  -> combine trusted data and model output
  -> output safety check
  -> persist answer, usage, tool, and audit evidence
  -> asynchronously create searchable summaries
```

Every turn has a stable ID and terminal state. Retries are idempotent by turn, preventing duplicate billing, audit, or memory candidates. Provider failure, tool failure, or delayed summarization never bypasses server policy.

### Stream events

Besides `meta`, `context`, `message`, `done`, and `error`, `/ai/chat/stream` has two event types:

- `delta`: `{"text","step"}`, a text increment and the model call it belongs to;
- `tool`: `{"phase","step","name","status","durationMs"}`, a tool starting and finishing, with neither its arguments nor its result.

Practice turns call the provider in streaming mode, and reasoning is never forwarded. Once a step produces a character that may start the internal protocol (`{`, a double quote, or a backtick), the rest of that step's text reaches the client only through the final `message`. The server sends the buffered events only after the pre-delivery contest-state recheck passes, so the client receives no increment before the whole turn has finished; `message` is authoritative. Contest-restricted turns do not stream; they are replayed in slices after the output check passes. Resuming a turn does not resend past increments.

## Layered context

Raw conversations and business records remain authoritative. Summaries, indexes, vectors, and memory are derived data.

1. **Bootstrap context:** a minimal trusted summary of user, conversation, problem, contest, and capability boundaries.
2. **Turn digest:** structured topic, entity references, decisions, and unresolved questions for one turn.
3. **Episode summary:** phase-level context across multiple turns.
4. **Source retrieval:** recover authorized original text or business evidence before asserting critical facts.
5. **Exact fallback:** bounded keyword or entity retrieval when summary recall is insufficient.

Retrieved data carries source, scope, and trust labels. Unverified model statements cannot be promoted to user facts.

## Tool control plane

The model sees only tools allowed for the current scenario. The server rechecks every call against:

- current user and role;
- conversation, problem, contest run, and time window;
- tool parameter schema;
- data visibility and minimum response fields;
- call budget, timeout, and loop limits;
- audit and privacy requirements.

Tool results use structured data with trusted provenance. Web content, statements, user text, and model text cannot become system instructions that expand tool authority.

### Tutor code tools

The tutor scenario has three code tools: `code.compile_check`, `code.run_samples`, and `code.run_custom`. The code comes only from the turn's selected code, the code the problem page's editor sends with the turn, or the student's own submission; the server binds it and the model only names the source, never supplying code itself; execution goes through agent-executor into agent-sandbox, and compile results are cached per caller. Results use the shared execution feedback: verdict, time, memory, process peak, the first difference from the expected output, and compile diagnostics or a stderr summary. Contest-restricted turns never see these tools.

### AI problem-draft scenario

The AI problem-draft path reuses the same `AgentRuntime` and agent-harness tool runtime; it no longer runs a fixed seven-stage model pipeline or duplicates the Agent loop. Each problem has an isolated durable workspace and dedicated `problem_draft_agent` conversation. The model uses nine draft tools to read and patch state, validate statically, execute code, run samples, generate and accept testcase packages, and request finalization, while the server binds resources and enforces every gate. Legacy synchronous, SSE, and regenerate entrypoints now delegate to durable Agent jobs. The real DeepSeek-plus-Docker path has completed local CF 800, 1400, and 1800 acceptance (on 2026-08-15, and again on 2026-10-29 on the agent-executor path), each producing a `PENDING_REVIEW`, `AGENT_VERIFIED` draft; production remains `IMPLEMENTED_UNVERIFIED`. See [AI problem-draft Agent architecture](problem-draft-agent-architecture.md) for the complete design.

DeepSeek thinking-mode `reasoning_content` is echoed completely in memory within the current tool loop only. It does not enter the database, logs, audit, or frontend. When thinking is unavailable, the draft scenario may explicitly fall back to the standard tool loop and records `STANDARD_TOOL_FALLBACK`.

## Memory and learning profile

Long-term memory stores stable, reusable, evidenced preferences or facts. Learning profiles store ability state derived from submissions, postmortems, and behavioral signals. They remain separate so one-off problem knowledge or AI advice is not mistaken for a durable preference.

```text
evidence -> candidate -> quality/conflict checks -> user confirmation -> active claim
```

The model may only propose a candidate. The server retains evidence, provenance, and state; users may accept, reject, or revoke. Conflicting claims are not silently overwritten, and profile signals retain time and source.

After a turn, digest curation, embedding, profile aggregation, judged-submission analysis, and memory merge review run asynchronously through the unified data channel. Each subscriber has its own jobs, retries, and dead letters, so one failing subscriber affects neither the others nor the answer already delivered. When a student denies, distrusts, or re-enables a profile entry, the change is a synchronous write through the profile port.

## Contest safety

Contest protection uses four server-side layers:

1. **Participation decision:** the server evaluates current runs, participant state, and time window.
2. **Policy snapshot:** the AI policy frozen at run publication is attached to the turn.
3. **Problem matching:** bounded fingerprints from run snapshots determine whether the question concerns a contest problem.
4. **Output validation:** complete code, private-problem information, and other restricted content are checked before return.

Client-supplied `problemId` or contest context supports attribution and experience but is never trusted for authorization. Private statements, hidden tests, and other participants' source are not sent to the model for classification.

## Prompt-injection protection

- Instruction authority comes only from trusted server configuration and controlled policy.
- User text, history, statements, tool results, and retrieved content are marked as data and cannot change system authority.
- Tools return structured fields rather than executable instruction text.
- Memory writes use a separate candidate and confirmation flow.
- Final answers pass content and policy validation before return.

## Audit and usage

Each turn retains the necessary model calls, token usage, tool calls, policy decisions, degraded state, and trace links. Audit stores safe summaries rather than secrets, hidden tests, complete internal errors, raw model reasoning, executable source, or unrelated personal data.

Contest AI usage is attributed by trusted server-side run context and aggregates turns, input/output tokens, sessions, and interceptions. Observation does not alter the answer, quota, or agent plan.

## Failure and degradation

- Provider unavailable: return a safe traceable error without inventing tokens or facts. The model call package owns timeouts, retries, and circuit breaking; a streamed call that fails after producing output is not retried.
- Tool timeout or executor unavailable: stop the call and retain audit, hand the result back to the model as data, and continue the turn; never bypass authorization with direct database access.
- Memory, profile, or another optional context section fails or overruns: only that section is missing and the turn still answers; a failing contest-policy section refuses the turn.
- Degradation record: each model call's context manifest lists degraded context sections (`contributor_degraded:<section>:<reason>`) and tools whose infrastructure failed (`tool_degraded:<tool>:<status>`).
- Digest or index delay: fall back to authorized recent context or exact retrieval.
- Contest-policy dependency failure: use the safe default and record degraded evidence.
- Output-check failure: refuse or constrain output rather than returning unchecked content.
