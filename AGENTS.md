# AIOJ Project Guide

This is the single authoritative project guide for Codex, Claude, and future
automation sessions in this repository root.

Read this file first. Then read:

- [docs/PROJECT_HISTORY_AND_FUTURE.md](docs/PROJECT_HISTORY_AND_FUTURE.md)
- [docs/PROJECT_MEMORY_AND_RUNBOOK.md](docs/PROJECT_MEMORY_AND_RUNBOOK.md)
- [docs/CONTEST_CLASS_GROUP_PROGRESS_TRACKER.md](docs/CONTEST_CLASS_GROUP_PROGRESS_TRACKER.md)
- [docs/CONTEST_CLASS_GROUP_DEVELOPMENT_PLAN.md](docs/CONTEST_CLASS_GROUP_DEVELOPMENT_PLAN.md)
- [docs/CONTEST_CLASS_GROUP_RESEARCH_AND_ARCHITECTURE.md](docs/CONTEST_CLASS_GROUP_RESEARCH_AND_ARCHITECTURE.md)

## 1. Operating Rule

For development work, follow this sequence:

1. **Discover**: inspect the real files, current git state, docs, tests, and runtime context.
2. **Plan**: define the exact scope, risks, acceptance criteria, and verification commands.
3. **Implement**: make the smallest coherent change that satisfies the plan.
4. **Verify**: run relevant tests/builds/checks; do not claim success without evidence.
5. **Record**: update living docs when a phase, rule, or important lesson changes.

Do not rely on memory or old conversation context when local files can answer the question.
Do not refactor adjacent code, rename concepts, or introduce dependencies unless required.

## 2. Current Project Shape

AIOJ is a teaching-oriented online judge with:

- student practice and contest participation,
- teacher/admin problem and testcase management,
- audited AI tutoring and AI problem drafts,
- contest operations from user groups through run-level audits, postmortems, and operational reports.

Current frontend target:

- React 19 + TypeScript + Vite 8
- TanStack Router + TanStack Query
- Tailwind CSS v4
- Radix/shadcn-style project-owned components
- Motion where short state-driven animation helps

Current frontend directories:

- `apps/web-user-react`: student app
- `apps/web-admin-react`: teacher/admin app
- `packages/ui-react`: shared React primitives
- `packages/api-client`: shared browser API client
- `packages/i18n`: shared messages

The retired Vue applications are not part of this clean repository. Do not
reintroduce them without a separately approved migration decision.

Backend modules:

- `backend/common-lib`: shared response, errors, trace, JWT/security filters
- `backend/api-contract`: DTOs, enums, Flyway migrations
- `backend/gateway-service`: public `/api/v1/**` gateway
- `backend/auth-service`: users, roles, user groups, auth
- `backend/problem-service`: problems, submissions, testcase packages, contests
- `backend/judge-worker`: RabbitMQ consumer, sandbox execution client
- `backend/ai-service`: AI chat, memory, drafts, reports, provider calls

## 3. Local Runtime Context

Before local debugging that needs accounts, ports, DB credentials, test data, or service startup mode, read:

```text
%USERPROFILE%\.codex\LOCAL_DEBUG_CONTEXT.md
```

Rules:

- Do not commit that file.
- Do not quote secrets from it in reports.
- Redact credentials if you need to mention them.
- Prefer the user's established local split: backend services often run from IntelliJ IDEA, frontend from VS Code/Vite, and sandbox from Docker. Verify the file before assuming.

Browser/UI verification:

- Prefer the user's actual local browser or the Codex in-app Browser when available.
- Do not silently substitute Playwright when the user asked for Chrome/Computer Use.
- If browser tooling is unavailable, state the gap and give manual acceptance steps.

Deployment target rule:

- Production is the image-only single-node stack on `aioj_a`. It contains
  application/data services plus judge-worker and the privileged Sandbox.
  Read [docs/deployment.md](docs/deployment.md),
  [docs/operations.md](docs/operations.md), and [SECURITY.md](SECURITY.md)
  before any server action.
- `/opt/aioj` is deployment state, not a Git worktree. Do not use `git pull`,
  build source, or edit mutable image tags there.
- Application secrets and the private GHCR read credential remain root-only on
  the server. GitHub stores only the restricted deployment SSH identity, host,
  and fixed host key.
- Formal SemVer GitHub Releases select eight application images by immutable
  digest. The protected `production` environment requires current approval;
  successful Flyway migrations are never automatically rolled back.
- The privileged Sandbox is a documented and accepted security downgrade from
  an isolated judge host. A runtime/kernel escape can compromise the entire
  colocated host and data. Compensating controls reduce ordinary abuse but do
  not eliminate escape risk or make the topologies equivalent.
- Sandbox must have no public port, host network, Docker socket, business
  mounts, or application/database/AI/deployment secrets. Only judge-worker may
  bridge the application network and the internal judge network. Initial judge
  prefetch/concurrency/max concurrency remain `1`.
- Single-node 50-user capacity is `IMPLEMENTED_UNVERIFIED`; historical
  split-node results cannot be inherited.

## 4. Backend Rules

API and errors:

- Controllers return `ApiResponse<T>`.
- Business errors use `DomainException(ErrorCode, message)`.
- Do not throw bare `RuntimeException` for business cases.
- Do not return raw `ResponseEntity.status(...)` from normal controllers.
- Add new `ErrorCode` values and i18n error messages together.
- Do not expose raw exception messages to clients.

Contracts:

- Cross-service DTOs and enums go in `backend/api-contract`.
- Keep frontend API types in `packages/api-client` synchronized.
- 16+ digit IDs are strings in frontend code. Use `EntityId = string` and preserve large integer parsing.

Gateway and service boundaries:

- Browser traffic goes through gateway `/api/v1/**`.
- Frontend must not call `:8201/:8202/:8203/:8204` directly.
- Problem-service triggers judging through RabbitMQ, not HTTP to judge-worker.
- Ai-service calls problem-service through service clients; do not directly read another service's tables.

Database:

- Flyway migrations live in `backend/api-contract/src/main/resources/db/migration`.
- Only add new `V{N}__*.sql`; never edit historical migrations.
- Use snake_case columns and explicit `@TableName`.
- If a DDL migration partially failed in MySQL, handle Flyway history carefully before rerunning.

Judge/sandbox:

- Judge-worker must call `SANDBOX_ENDPOINT`; it never executes user code in-process.
- Sandbox credentials come from config/secrets, never hardcoded.
- Keep testcase package storage as file/blob storage; do not put large testcase contents in MySQL.
- Large stdout collection is dynamic and bounded; do not regress to a fixed 64 KiB cap for answer comparison.

AI:

- AI problem generation remains draft -> review/approve -> import.
- AI calls must be auditable via usage records.
- AI plagiarism and postmortem text are advisory, not final misconduct or grading decisions.
- Do not send full source code to AI unless the feature explicitly requires it and the privacy boundary is documented.
- Student weakness candidates do not enter long-term memory until the student accepts them.

## 5. Frontend Rules

Data:

- Views call `@aioj/api-client`; do not handwrite `fetch` in pages.
- i18n text goes in `packages/i18n/src/messages.ts`; avoid hardcoded Chinese/English in components.
- Shared UI belongs in `packages/ui-react` when reuse is real.
- Any full browsing surface backed by `PageResponse.records` must render pagination and total feedback. Fixed small `pageSize` requests are only acceptable for intentional summaries such as dashboard cards, recommendations, or detail-page recent items.

Layout:

- Do not put tables, audit lists, review workspaces, code panes, or AI analysis panels in narrow drawers or cramped split panes.
- Keep parent lists/tables in stable browsing surfaces. Open only selected item details in centered dialogs or dedicated routes.
- In detail dialogs, let finite AI analysis/evidence text expand naturally; avoid tiny nested scroll boxes.
- Compact controls can stay small; notes/comments/analysis inputs need full-width readable space.
- Prevent badges and buttons from wrapping into vertical text; use content-aware column widths and truncation with `title` for long values.

Interaction:

- Auth expiration must redirect correctly, not leave users on failed data pages.
- Loading, empty, and error states are required for lists and async panels.
- Dialogs should close when the user clicks the surrounding overlay by default, unless a specific flow explicitly requires a blocking modal.
- Destructive actions need confirmation, especially archive/delete/restore/unfreeze/refreeze.
- Contest code drafts are isolated by `contestRunId + contestProblemId + language`; do not load ordinary practice drafts in contest mode.

## 6. Contest Domain Rules

Organization:

- User group is the only maintained teaching organization model.
- Do not develop, expose, or maintain study subgroup / learning subgroup features.
- Phase 12 training reports and gradebook features were removed; do not reuse or revive that implementation without a new explicit plan.

Terminology:

- `contest` is a reusable blueprint: title, description, mode, scoring rules, and problem arrangement.
- `contest_run` is one concrete opening: start/end/freeze time, registration policy, allowed groups, participants, submissions, scoreboard, audit, exports, plagiarism, replay, reports.
- A run can represent first contest, rematch, practice run, simulation, or replay.

State:

- For run display/business status, lifecycle flags (`DRAFT`, `ARCHIVED`, `deleted_at`) have priority; otherwise derive `SCHEDULED/RUNNING/ENDED` from `startAt/endAt`.
- Run-based scoreboard/report/plagiarism operations must pass a concrete `runId`; do not aggregate "all runs" unless a new explicit product design exists.
- Deleted objects are hidden from normal APIs; restoring deleted rows is database-only.

Snapshots:

- Contest participants are snapshotted.
- Run problem statements/rules are snapshotted when a run is published.
- Historical runs must not be affected by later user profile or problem edits.

Scoring:

- ACM uses solved count, penalty, first accepted time, freeze/pending semantics.
- IOI/OI uses score and case/subtask information.
- Do not mix ACM score text into ACM personal postmortem prompts; ACM reports focus on AC status, attempts, errors, tags, and representative code.
- IOI/OI reports can use score and case/subtask details.

Security:

- Students cannot see run problems before start time.
- Source viewing by teacher/admin uses audited contest source-access APIs.
- Plagiarism reports export similarity evidence, not full source.
- AI postmortem reports do not expose hidden testcase data, full stdout/stderr, or other participants' private details.

AI guard during runs (design authority:
[docs/AIOJ_AGENT_CORE_V3_IMPLEMENTATION_DESIGN.md](docs/AIOJ_AGENT_CORE_V3_IMPLEMENTATION_DESIGN.md)
§5; superseded guard evidence is preserved only in local `archive/` and feeds
the V3 evaluation set):

- Enforcement lives only in ai-service `ContestTurnGuard`, evaluated per turn on the server. Client-sent `problemId`/`contestContext` are advisory (attribution/stats) and never a trust basis.
- Matching scope is the participant's currently running runs' deduplicated problems; statement and visibility come from `contest_run_problem_snapshots`, never the live problems table.
- Run AI policy modes: `DEFAULT` (private = refuse, public = hints only), `STRICT` (refuse on any match), `DISABLED` (no interception). The blueprint-level config is snapshotted into the run at publish and is DRAFT-only editable.
- Non-participants are deliberately not intercepted (UX decision); post-contest audit/review is the backstop. Every participant guard evaluation (PASS/CONSTRAIN/REFUSE) and every degraded pass is audited.
- Staff listed as run participants are equally restricted when using the student app; teacher invites require student acceptance (`INVITED`) before becoming a participant, while self-registration counts as consent.
- Live-test bypass paths (short follow-ups, problem-page chats without pasted statements, no conversation stickiness, history re-injection of blocked content) are recorded in the design doc section 9; the context/matching redesign is pending the AI context/memory optimization.

## 7. Verification Commands

Use the narrowest relevant checks first, then broader checks when shared contracts changed.

Backend (run from the repository root):

```powershell
mvn -f backend/pom.xml -pl common-lib -am test
mvn -f backend/pom.xml -pl auth-service -am test
mvn -f backend/pom.xml -pl problem-service -am test
mvn -f backend/pom.xml -pl judge-worker -am test
mvn -f backend/pom.xml -pl ai-service -am test
```

Frontend:

```powershell
npm.cmd run typecheck:react
npm.cmd run build:user:react
npm.cmd run build:admin:react
```

Repository hygiene:

```powershell
git diff --check
rg -n "System\.out\.println|console\.log\(" backend apps packages -S
rg -n "change-me|replace-" backend apps packages deploy docs -S
```

Do not treat existing placeholder strings in documented commands as real leaked secrets without checking context.

## 8. Living Documents

Authoritative:

- `AGENTS.md`: rules and red lines.
- `docs/PROJECT_HISTORY_AND_FUTURE.md`: compressed development history and future roadmap.
- `docs/PROJECT_MEMORY_AND_RUNBOOK.md`: high-value lessons, local runbook, debugging habits.
- `docs/CONTEST_CLASS_GROUP_PROGRESS_TRACKER.md`: contest/class phase status and verification evidence.
- `docs/CONTEST_CLASS_GROUP_DEVELOPMENT_PLAN.md`: contest/class implementation roadmap.
- `docs/CONTEST_CLASS_GROUP_RESEARCH_AND_ARCHITECTURE.md`: contest/class architecture and external-rule research.
- `docs/AIOJ Agent Core V3.md`: sole authoritative blueprint for the AI assistant backend rebuild (agent runtime, tool system, layered recall, contest safety, evaluation).
- `docs/AIOJ_AGENT_CORE_V3_IMPLEMENTATION_DESIGN.md`: confirmed implementation contract for the V3 rebuild (frozen decisions, DeepSeek/Kimi provider adapters, four-layer contest defense, V60+ data model, phased plan with exit gates).

Historical / reference only:

- Superseded AI guard/context/memory plans and detailed evidence reports are
  preserved in local Git-ignored `archive/`; do not develop against them. Their
  live-bypass evidence remains part of the V3 evaluation set.
- Detailed historical phase/design reports are reference material, not default
  session inputs. Their current authority is summarized by the living
  documents above.
- Local-only retired documents may exist under `archive/`; that folder is
  intentionally Git-ignored and is not required in a fresh clone.
- Old Claude/Codex exchange files have been summarized into `docs/PROJECT_HISTORY_AND_FUTURE.md` and are no longer part of the active workflow.

## 9. Git and Cleanup

- Never revert user changes without explicit instruction.
- Do not stage or commit generated output: `target/`, `dist/`, logs, screenshots, exported CSV/XLSX/Markdown files, local debug files.
- When deleting docs, first summarize useful content into a current document.
- Keep documentation truthful: if code is implemented but not manually verified, say `IMPLEMENTED_UNVERIFIED`, not `VERIFIED`.
