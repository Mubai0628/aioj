# System architecture

[中文](../zh/system-architecture.md)

## Overview

```text
Browser -> web-user / web-admin -> gateway
                                  |-> auth-service -> MySQL
                                  |-> problem-service -> MySQL / Redis / RabbitMQ
                                  `-> ai-service -> model call package -> DeepSeek / embeddings
                                         `-> agent-executor -> agent-sandbox

RabbitMQ -> judge-worker -> go-judge Sandbox
```

Browsers access only `/api/v1/**` through the gateway. Cross-service contracts and Flyway migrations are centralized in `backend/api-contract`; shared responses, errors, tracing, and security filters live in `backend/common-lib`.

Internal HTTP clients are created by the common-lib factory, using the direction's `X-Internal-Token` for each target. The four independent business-direction credentials and executor-only credential are not interchangeable. Targets allow only prefixes registered for that caller and accept current/NEXT inbound values during rotation. The gateway and auth-service hold no internal credentials. See [security architecture](security-architecture.md).

## Components

| Component | Responsibility |
| --- | --- |
| gateway-service | only browser API entry, routing, and CORS boundary |
| auth-service | login, JWT/refresh tokens, users, roles, groups, and Flyway |
| problem-service | problems, testcases, contests, submissions, notifications, and asynchronous jobs |
| judge-worker | consumes RabbitMQ judge messages and calls Sandbox; never executes code itself |
| ai-service | agent runtime, model call package, unified data channel, conversations, memory, drafts, plagiarism review, and postmortems |
| agent-executor | the only execution entry for Agent tools and the problem page's self-test run: compiles, runs, generators, and packages; calls only agent-sandbox |
| web-user / web-admin | student and administration React SPAs |
| MySQL / Redis / RabbitMQ | authoritative business data, cache/short-lived state, and judge queue |
| go-judge Sandbox | privileged runtime for isolated execution of untrusted code; formal judging only |
| agent-sandbox | go-judge instance dedicated to Agent tools and self-test runs, called only by agent-executor |

`backend/agent-harness` is the domain-neutral library for the Agent loop, the tool pipeline, and context assembly; ai-service builds the tutor and AI problem-draft scenarios on it. `backend/sandbox-client` holds the go-judge protocol, language configuration, and output comparison shared by judging and the Agent.

## Main data flows

### Authentication

The browser logs in through the gateway to auth-service. Passwords are verified only during login, then replaced by short-lived access tokens and controlled refresh tokens. Other services use shared security filters for identity and role checks.

### Judging

problem-service persists a submission and publishes a RabbitMQ job. judge-worker consumes the job, resolves authorized testcase references, and calls Sandbox. Results are written back to problem-owned data; browsers never connect directly to judge-worker or Sandbox.

### AI

ai-service calculates permission and policy on the server, builds minimal context, invokes the provider and authorized tools, and persists usage and audit evidence. AI accesses problem-service through minimal service contracts rather than reading another service's database tables.

Every model call goes through the model call package inside ai-service, which owns timeouts, retries, circuit breaking, and one observation record per call; architecture tests forbid any other path. Text models are DeepSeek only: Kimi and the plain OpenAI-compatible text option were retired on 2026-10-29, and a stored configuration that names one still shows but can no longer be saved or used for a call. Embeddings use DashScope or a custom OpenAI-compatible endpoint. Student chat returns an event stream through `/ai/chat/stream`; text deltas and tool progress are described in the [Agent Core V3 architecture](agent-core-v3-architecture.md).

Work after a turn goes through the unified data channel: digest curation, embedding, learning-profile aggregation, judged-submission analysis, and memory merge review are its subscribers. An event and one job per subscriber are written in the same transaction; a failed job retries with backoff and moves to the dead-letter state once its attempts are spent, and an administrator can replay it per subscriber.

AI problem drafting follows `ProblemDraftAgentCoordinator → AgentRuntime → agent-harness loop`. ai-service sends code, sample, generator, and package execution through a protected internal API to agent-executor, which calls agent-sandbox; ai-service never accesses Docker or a sandbox directly, and the judge Sandbox serves formal judging only. After human approval, a recoverable import saga commits the problem content and a `READY` package through problem-service. See [AI problem-draft Agent architecture](problem-draft-agent-architecture.md).

### Self-test runs

The Run button beside the problem page's editor sends the code to ai-service (`/api/v1/ai/code-runs`). problem-service checks that this user may see the problem (a private problem only for an active participant inside its contest run, with the contest context checked on the server) and returns the public samples and the time limit for the language; the code then runs through agent-executor in agent-sandbox, the tutor code tools' path, never through the judge Sandbox. A run makes no submission, no analysis, and no score, and uses only the public samples and the student's own input (at most 1 MB). The server enforces a gap between two runs of one person, one run in progress per person, a site-wide number of runs at once, and per-minute and per-day counts per person; administrators set these limits in the admin console.

### Notifications

Persistent notification records are the source of truth; SSE is only a real-time wake-up signal. After disconnect or refresh, the client uses REST recovery to restore unread state.

## Trust boundaries

- Browser input, client-supplied contest context, and model output are untrusted.
- Authorization, contest visibility, tool permission, and data minimization are enforced on the server.
- Frontends preserve 16+ digit entity IDs as strings.
- Hidden tests, participant source, provider secrets, and complete judge output cannot cross unauthorized APIs.
- AI output and tool calls never gain more authority than the current user and scenario policy.

## Persistence

- MySQL stores authoritative business data and Flyway history.
- Redis stores rebuildable cache and short-lived state.
- RabbitMQ stores asynchronous judge jobs and bounded retry state.
- Testcase packages, operation artifacts, AI-draft artifacts, and judge cache use file/volume storage; large hidden tests do not enter MySQL.
- V66 adds AI-draft workspaces, bounded execution metadata, chunk-upload sessions, and recoverable import sagas, and extends existing draft/artifact/Agent-audit/problem records with provenance and fingerprints. Unwired draft job/attempt/artifact tables from V49–V51 remain historical structures and do not enter the active path.
- Model call records (V70), the unified channel's event envelope (V72), and subscriber jobs live in MySQL. The old memory job table is kept but no longer written; on startup ai-service turns its unfinished judged-submission analysis and memory merge review jobs into channel jobs.

## Production topology

The selected production topology combines application services, data services, judge-worker, and Sandbox on one host. The application network carries business traffic, while an internal judge network connects only judge-worker and Sandbox. Sandbox has no public port, host network, Docker socket, business mount, or database/JWT/AI/deployment secret.

Sandbox remains `privileged`, so a successful escape could compromise the host and all colocated data. Network and resource controls cannot fully remove that risk; see the [security architecture](security-architecture.md).

The Agent system, AI problem drafting included, has been accepted only locally against a real provider and remains `IMPLEMENTED_UNVERIFIED`; production node `aioj_a` has not been updated with these changes.
