# AIOJ

AIOJ is a campus-oriented online judge for programming practice, contests,
audited AI tutoring, AI-assisted problem drafting, plagiarism review, and
post-contest learning reports. The active UI is React-only and all browser
traffic goes through the gateway.

> [!CAUTION]
> **Accepted privileged-Sandbox risk.** The production design in this
> repository colocates the application, database, judge-worker, and a
> `privileged` go-judge Sandbox on one host. Normal submissions remain subject
> to go-judge, namespace, cgroup, timeout, and output limits, but a successful
> Sandbox, container-runtime, Docker, or Linux-kernel escape can yield
> host-root-equivalent control. That can expose or alter the database, user
> data, tokens, environment secrets, images, results, RabbitMQ, Redis, internal
> services, and the host itself; it can also cause resource exhaustion or
> persistent compromise. Network isolation, resource limits, read-only
> testcase mounts, and secret separation reduce ordinary abuse but do **not**
> eliminate escape risk. The maintainer has explicitly accepted this security
> downgrade from the former split judge node. See [SECURITY.md](SECURITY.md).

## Architecture

```text
Browser -> web-user / web-admin -> gateway
                                  |-> auth-service -> MySQL
                                  |-> problem-service -> MySQL / Redis / RabbitMQ
                                  `-> ai-service -> DeepSeek-compatible provider

RabbitMQ -> judge-worker -> privileged go-judge Sandbox
```

Judging always flows through RabbitMQ and Sandbox; the judge-worker never
executes user code in-process. AI problem generation remains
draft -> review/approve -> import.

## Repository layout

```text
backend/                    Maven multi-module Spring backend
  common-lib/               responses, errors, tracing, security
  api-contract/             shared contracts and Flyway migrations
  gateway-service/          public /api/v1 gateway
  auth-service/             authentication, users, roles, groups
  problem-service/          problems, submissions, contests, notifications
  judge-worker/             RabbitMQ consumer and Sandbox client
  ai-service/               Agent V3, AI drafts, reports, memory
apps/web-user-react/        student React application
apps/web-admin-react/       teacher/admin React application
packages/                   shared API client, i18n, and React UI
deploy/                     Dockerfiles and image-only production Compose
scripts/                    evaluation, load-test, CI, and deployment tooling
docs/                       current public architecture and runbooks
archive/                    local-only historical material; Git-ignored
```

The retired Vue applications are intentionally absent from this repository.

## Requirements

- JDK 17 and Maven 3.9+
- Node.js 24 and npm 11+
- Docker Engine 29+ with Docker Compose v2/v5 compatibility
- MySQL 8.4, Redis 7.4, and RabbitMQ 3.13 for a complete local stack

Local secrets, ports, accounts, and service startup choices belong in a local,
untracked context file. Never copy a workstation `.env` to a server.

## Local development

```powershell
npm ci --include=optional
npm.cmd run dev:user       # defaults to the project-configured user port
npm.cmd run dev:admin      # defaults to the project-configured admin port

mvn -f backend/pom.xml -pl auth-service -am spring-boot:run
mvn -f backend/pom.xml -pl problem-service -am spring-boot:run
```

Ports `5173` and `5174` may be reserved by other local projects. Use the
documented local overrides rather than changing production CORS or Compose.
See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Build and test

```powershell
npm ci --include=optional
npm.cmd run typecheck:react
npm.cmd run test:auth
npm.cmd run build:user:react
npm.cmd run build:admin:react
mvn -f backend/pom.xml test
git diff --check
```

Pull requests and `main` pushes run the same core checks plus Compose
validation, local Markdown-link checks, repository-hygiene checks, and a
Gitleaks scan.

## Docker

`deploy/compose.yml` remains the source-build Compose file for deliberate
local smoke testing. Production uses
[`deploy/compose.production.yml`](deploy/compose.production.yml), which is
image-only: application images are supplied as immutable GHCR digests and
third-party images are pinned to audited Linux/amd64 digests.

```powershell
Copy-Item deploy/env/production.env.example .env
docker compose --env-file .env --profile core --profile app --profile judge `
  -f deploy/compose.yml config
```

Replace every placeholder before starting a stack. Never commit `.env`.

## Release and deployment

1. Merge reviewed changes to protected `main`.
2. Create a formal SemVer GitHub Release such as `v1.0.0`.
3. GitHub Actions builds eight private GHCR images, emits SBOM/provenance
   attestations, and publishes `image-manifest.json` plus its SHA-256.
4. The protected `production` environment requires manual approval.
5. A restricted SSH identity invokes the root-owned deployment gate with only
   the release tag and manifest SHA.
6. The server validates namespace and digests, pulls images, preserves the
   previous digest set, applies the image-only Compose file, and checks health.

The production root is `/opt/aioj`; it is not a Git checkout. Application
secrets exist only in `/opt/aioj/env/app.env`. Full procedures and first-host
bootstrap are in [docs/deployment.md](docs/deployment.md) and
[docs/operations.md](docs/operations.md).

## Rollback

Normal rollback restores `deploy.previous.env`, which contains the prior image
digests, and recreates the stack against the **current** data volumes. A
successful Flyway migration is never automatically rolled back. After writes
resume on the new stack, starting stale old volumes is forbidden.

## Capacity status

The prior split-node deployment passed limited 50-user tests, including stable
50/50 login bursts with P95 around 5.80-6.26 seconds under the tested resource
allocation. Those results do not transfer to the merged single-node topology.
Single-node 50-user mixed capacity is **IMPLEMENTED_UNVERIFIED** until login,
API, queue, judge, AI-adjacent, resource, OOM, swap, throttling, and disk-growth
tests are repeated on the new topology.

## Documentation

- [Project architecture](docs/architecture.md)
- [Development runbook](docs/DEVELOPMENT.md)
- [Deployment](docs/deployment.md)
- [Operations and recovery](docs/operations.md)
- [Agent Core V3 blueprint](docs/AIOJ%20Agent%20Core%20V3.md)
- [Project history and roadmap](docs/PROJECT_HISTORY_AND_FUTURE.md)

## License and reporting

Licensed under [Apache License 2.0](LICENSE). See
[CONTRIBUTING.md](CONTRIBUTING.md) before proposing changes. Report security
issues privately as described in [SECURITY.md](SECURITY.md); do not publish
credentials, hidden tests, private source, or exploit details in an issue.
