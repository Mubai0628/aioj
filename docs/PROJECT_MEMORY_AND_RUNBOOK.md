# AIOJ project memory and runbook

Last reviewed: 2026-08-11

This living document captures durable engineering lessons. Historical detail is
preserved under local Git-ignored `archive/`; current deployment commands live
in [deployment.md](deployment.md) and [operations.md](operations.md).

## Non-negotiable boundaries

- Discover real files/runtime before planning or changing code.
- Preserve unrelated dirty work and use the smallest coherent change.
- Browser -> gateway -> service; no direct browser calls to backend ports.
- Frontend entity IDs are strings.
- Contracts/migrations live in `api-contract`; historical Flyway files do not
  change.
- Judge work is RabbitMQ -> judge-worker -> Sandbox, never in-process code.
- Testcase content stays bounded file/blob storage and is not exposed.
- AI problem generation is draft -> review/approve -> import.
- AI plagiarism/postmortem evidence is advisory.
- A student must accept a weakness candidate before long-term memory changes.
- Student errors are safe summaries; authorized operations views retain useful
  diagnostic evidence.

## Local workflow

1. Read `AGENTS.md`, relevant living docs, Git status, and the untracked local
   runtime context.
2. Define scope, risk, acceptance, and exact verification commands.
3. Implement narrowly; keep local ports/CORS/secrets separate from production.
4. Run targeted tests, then broader Maven/React checks for shared contracts.
5. Record a changed product/deployment rule in living docs.

The user commonly runs backend services from an IDE and React through Vite.
Check whether infrastructure is already running before starting Docker. Keep
local frontend ports away from `5173`/`5174` when those are reserved.

## Frontend lessons

- Use `@aioj/api-client`; do not scatter custom fetch logic through pages.
- Full `PageResponse.records` surfaces need pagination and totals.
- Keep tables, audits, code, transcripts, and AI evidence out of narrow drawers.
- Finite evidence text should be readable without tiny nested scrollboxes.
- Destructive/reset actions require confirmation.
- Loading, empty, error, and auth-expired states are product behavior.
- Contest code drafts are keyed by run + contest problem + language.
- Notification SSE carries only wake-up metadata; persistent REST records are
  the source of truth and reconnect compensation is mandatory.

## Contest lessons

- `contest` is a reusable blueprint; `contest_run` is the operational unit.
- DRAFT/ARCHIVED/deleted lifecycle flags outrank time-derived status.
- Published run problems and participants are snapshotted.
- Students cannot view run problems before start.
- Staff source access is audited and exports avoid full-source leakage.
- Private invitations remain invisible/unacceptable before publish; dispatch is
  versioned/idempotent after publish.
- Run-scoped plagiarism, replay, postmortem, and exports require a concrete
  `runId`.
- ACM and IOI/OI report vocabulary/scoring semantics must not be mixed.

## AI and memory lessons

- DeepSeek is the default text provider; other compatible providers are
  optional configuration, not the product default.
- Client problem/contest context is advisory; contest guard attribution is
  established by server-owned run/snapshot data.
- Agent Core V3 design documents are authoritative over superseded context and
  guard plans.
- Provider calls record safe usage/intent evidence without prompts, hidden
  tests, secrets, or full private transcripts.
- AI draft audit is a single current-state row per job. Generic asynchronous
  operations remain in `operation_jobs`.
- Legacy memory migration scans production server data only. Normal mode is
  `off`; `dry-run -> reviewed apply -> dry-run` requires a current backup and
  explicit authorization. Never infer production users/counts from local data.

## Judge and Sandbox lessons

- Dynamic bounded stdout collection avoids treating legitimate large expected
  output as an arbitrary fixed-cap OLE.
- Sandbox tokens and limits come from configuration, never code.
- The merged production Sandbox is privileged and shares the application/data
  host. Escape can become total host/data compromise; this accepted downgrade
  must remain prominent in README, SECURITY, deployment, and operations docs.
- Mandatory controls: no public Sandbox port, host network, Docker socket,
  business mounts, or app/data/provider/deploy secrets; internal judge network;
  read-only testcase cache; isolated temp; resource/PID/time/output/log bounds;
  judge concurrency one at initial acceptance.

## Production delivery lessons

- `/opt/aioj` is image-only deployment state, not source.
- Formal SemVer Releases build private GHCR images and deploy immutable digests.
- Third-party Actions use full commit SHAs; third-party images use reviewed
  digests.
- The deploy user is locked, forced-command-only, not in Docker group, and has
  one sudo entry to a root-owned validation gate.
- Secrets stay root-only on the server; GitHub receives only restricted SSH
  material, host, and fixed host key.
- Preflight stops on active contests/jobs, nonempty judge queues, unhealthy
  containers, OOM/swap, or insufficient disk.
- Production backups come from the server and are restored to disposable
  database/volumes before cutover.
- Image rollback does not reverse Flyway. After new writes resume, stale old
  volumes must not be started.
- Former root, volumes, images, repository, and judge host remain until a
  separate cleanup after at least 24 stable hours.

## Capacity truth

Historical split-node evidence includes stable 50/50 login bursts with P95
about 5.80-6.26 seconds and limited judge bursts. The merged single-node design
cannot inherit those results. Its memory caps total about 6.25 GiB and CPU is
oversubscribed; mixed 50-user acceptance must measure API latency, queue drain,
judge wall time, DB/broker pressure, OOM/restart, swap, throttling, and disk.
Status remains `IMPLEMENTED_UNVERIFIED`.

## Verification baseline

```powershell
mvn -f backend/pom.xml test
npm ci --include=optional
npm.cmd run typecheck:react
npm.cmd run test:auth
npm.cmd run build:user:react
npm.cmd run build:admin:react
node scripts/ci/check-markdown-links.mjs
node scripts/ci/check-repository-hygiene.mjs
git diff --check
```

Do not claim manual browser, provider, production, Flyway, backup restore, or
capacity acceptance unless it actually occurred and evidence was recorded.
