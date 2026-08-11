# AIOJ development history and future

Last reviewed: 2026-08-11

This is a compressed history, not an executable deployment runbook. Historical
source documents are preserved locally under the Git-ignored `archive/` tree.

## Product direction

AIOJ closes a teaching loop: practice -> judge feedback -> guided AI help ->
contest -> audited review -> personalized learning. Students, teachers, and
platform administrators have separate surfaces and authorization boundaries.
AI output remains governed, auditable, and advisory where the product requires
human judgment.

## Foundation

- Spring Boot multi-module backend: gateway, auth, problem, judge-worker, AI,
  shared contracts, security, errors, tracing, and Flyway.
- Browser traffic is gateway-only; service contracts live in `api-contract`.
- Testcase packages use bounded file/blob storage and judge retrieval.
- Judging moved from in-process assumptions to RabbitMQ -> judge-worker ->
  go-judge Sandbox, including dynamic bounded stdout collection.
- React 19, TypeScript, Vite, TanStack, Tailwind, and project-owned primitives
  replaced the retired UI stack for both student and admin applications.

## Contest and teaching operations

The contest system evolved into reusable blueprints plus run-scoped operational
truth: schedules, registration/invitations, participant and problem snapshots,
submissions, ACM/IOI scoreboards, freeze/resolver, source audit, exports,
plagiarism evidence, clarifications/announcements, postmortems, replay, and
operation jobs. User groups are the only maintained teaching-organization
model. Study subgroups, gradebook/training reports, output-only tasks, and team
contest mainline remain removed/deferred.

Verified product phases include personal postmortems with student-confirmed
weakness memory, plagiarism relationship/fairness evidence, clarifications and
announcements, IOI subtasks/custom checkers, and the core contest lifecycle.
Phase 15 scale/audit/governance remains `IMPLEMENTED_UNVERIFIED` pending broad
manual and load acceptance.

## Agent Core V3

Live evidence showed that patching the old context/memory/contest guard was not
sufficient. The backend AI foundation was rebuilt around Agent Core V3:

- explicit turn/session/context ownership;
- provider adapters with DeepSeek as the default text provider;
- tool execution and layered memory/recall;
- four-layer contest defense and audited degraded paths;
- persistent usage/contest-assistance statistics;
- draft -> review/approve -> import for generated problems.

The blueprint is [AIOJ Agent Core V3.md](AIOJ%20Agent%20Core%20V3.md) and the
implementation contract is
[AIOJ_AGENT_CORE_V3_IMPLEMENTATION_DESIGN.md](AIOJ_AGENT_CORE_V3_IMPLEMENTATION_DESIGN.md).
Superseded AI designs are local historical references only.

Legacy memory data was mapped to the new claims/profile model through a
server-data-only, idempotent migration mode. Normal runtime is `off`. A future
operator must inspect production source/mapping counts and backups rather than
replaying a local dataset or blindly rerunning `apply`.

## Notifications and asynchronous work

Persistent REST notification records are authoritative; authenticated SSE is a
wake-up signal with reconnect/REST recovery. Contest invitations are invisible
until their run is published and are dispatched idempotently. Student-created
postmortems expose safe progress/failure wording to students while detailed
operation errors remain in authorized admin audit views.

AI draft, plagiarism, export, replay, and postmortem operations use bounded
asynchronous jobs. AI draft audit uses one current row per job; the generic
operation-job page remains scoped to operation jobs.

## Repository and delivery reset (2026-08-11)

- Created a clean React-only repository with a new `main` history.
- Public `docs/` contains current architecture/product/runbooks; historical
  documents remain in local ignored `archive/`.
- CI pins third-party Actions by commit and runs Maven, React, Compose, link,
  hygiene, and secret checks.
- Formal releases build eight private GHCR images with SBOM/provenance and an
  immutable digest manifest.
- Production moved to an image-only `/opt/aioj` layout with a restricted
  forced-command deployment identity and digest rollback.
- The maintainer selected a single host for app/data/judge/Sandbox and accepted
  the documented privileged-Sandbox blast-radius downgrade.

## Current priorities

1. Complete first single-node restore rehearsal, cutover, and acceptance
   without deleting the former stack or judge host.
2. Repeat 50-user mixed capacity testing on the merged host; status remains
   `IMPLEMENTED_UNVERIFIED` until then.
3. Continue Agent Core V3 evaluation, contest-safety evidence, AI report
   robustness, and privacy-safe observability.
4. Keep Flyway/data recovery and legacy-memory migration as independently
   reviewed operations.
5. Archive the former GitHub repository only after the new production release
   remains stable for at least 24 hours.

## Decisions not to reopen casually

- React-only frontend and gateway-only browser APIs.
- Contest blueprint/run split and snapshot-based historical truth.
- RabbitMQ/Sandbox judge boundary.
- Human review for AI problem imports and advisory AI governance.
- Student confirmation before weakness candidates enter long-term memory.
- User groups as the only maintained organization model.
- Readable dialogs/routes rather than narrow drawers for dense evidence.
