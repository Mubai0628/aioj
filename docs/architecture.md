# AIOJ architecture

Last reviewed: 2026-08-11

## Product surfaces

- Student: practice, contests, submissions, audited AI tutoring, notifications,
  and personal postmortems.
- Teacher/admin: users/groups, problem/testcase management, contest runs,
  source audit, plagiarism evidence, AI drafts/models, operations, and reports.
- AI: Agent Core V3 context, tools, layered recall, contest guard, draft review,
  and advisory analysis.

## Components

| Component | Responsibility |
| --- | --- |
| gateway-service | Only browser API entry; routes `/api/v1/**` |
| auth-service | Login, JWT/refresh tokens, users, roles, groups, Flyway |
| problem-service | Problems, testcases, contests, submissions, notifications, operation jobs |
| judge-worker | Consumes RabbitMQ jobs and calls Sandbox; never runs code itself |
| ai-service | Agent runtime, memory, drafts, reports, provider calls |
| web-user | React student SPA |
| web-admin | React admin SPA |
| MySQL / Redis / RabbitMQ | durable business data, cache/state, judge queue |
| go-judge Sandbox | privileged isolated execution runtime |

Cross-service contracts and migrations live in `backend/api-contract`; shared
errors/security live in `backend/common-lib`. Frontend network access is
centralized in `packages/api-client`.

## Trust boundaries

- Browser input and client-sent contest/problem context are untrusted.
- Service authorization is enforced server-side; internal calls use a distinct
  token and minimal DTOs.
- 16+ digit IDs remain strings in browsers.
- Testcase contents, participant source, provider secrets, and complete judge
  output do not cross unauthorized APIs.
- Judge jobs flow problem-service -> RabbitMQ -> judge-worker -> Sandbox.
- AI drafts follow draft -> review/approve -> import.
- AI plagiarism/postmortem outputs are advisory, not final discipline/grades.
- Weakness candidates enter long-term memory only after student acceptance.

## Production topology and risk

The selected production topology places every component on `aioj_a`, using an
application network plus an internal judge-only network. Sandbox has no public
port, host network, Docker socket, business mounts, or application/database/AI
secrets. Judge-worker bridges the application and judge networks; no other
business container joins the judge network.

Sandbox remains `privileged`. A successful escape can compromise the host and
all colocated services/data. This is an explicitly accepted security downgrade
from split-node judging, as detailed in [../SECURITY.md](../SECURITY.md).

## Persistence

- MySQL: authoritative business data and Flyway history.
- Redis: cache/ephemeral state with persistence configured for recovery needs.
- RabbitMQ: judge queues and broker state.
- Volumes: testcase packages, operation artifacts, AI draft artifacts, judge
  cache, and Sandbox temporary workspace.
- Hidden testcase packages remain file/blob storage, never large MySQL rows.

## Delivery

CI tests Maven/React and repository policy. Formal GitHub Releases build eight
private GHCR images with SBOM/provenance attestations. Production selects
immutable digests from a signed-by-process manifest and deploys through a
forced-command SSH gate. The server stores no Git worktree and no application
secret exists in GitHub.
