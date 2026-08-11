# AIOJ development runbook

Project rules live in [../AGENTS.md](../AGENTS.md); durable lessons live in
[PROJECT_MEMORY_AND_RUNBOOK.md](PROJECT_MEMORY_AND_RUNBOOK.md).

## Local boundary

Keep workstation secrets and account details in an untracked local context.
Never commit or quote it. Local ports, CORS origins, credentials, service
addresses, and databases do not describe production and must not be copied to
the server.

Ports `5173` and `5174` may belong to other local projects. The repository
example uses `5175` and `5176`; verify availability before starting Vite.

## Frontend

Only the React applications are maintained:

- `apps/web-user-react`
- `apps/web-admin-react`

```powershell
npm ci --include=optional
npm.cmd run dev:user
npm.cmd run dev:admin
npm.cmd run typecheck:react
npm.cmd run test:auth
npm.cmd run build:user:react
npm.cmd run build:admin:react
```

Browser APIs use `@aioj/api-client` and go through the gateway. Entity IDs stay
strings in frontend code.

## Backend

```powershell
mvn -f backend/pom.xml -pl auth-service -am test
mvn -f backend/pom.xml -pl problem-service -am test
mvn -f backend/pom.xml -pl judge-worker -am test
mvn -f backend/pom.xml -pl ai-service -am test
mvn -f backend/pom.xml test
```

When `api-contract` or `common-lib` changes, test all dependent services. Add a
new Flyway migration; never edit a historical migration.

## Local Docker smoke

`deploy/compose.yml` builds from source for deliberate local smoke testing.
Copy `.env.example` to `.env`, replace placeholders, and start only the needed
profiles. Do not assume Docker should own infrastructure already running as a
Windows service.

```powershell
docker compose --env-file .env -f deploy/compose.yml config
docker compose --env-file .env --profile judge -f deploy/compose.yml up -d --build sandbox
```

The privileged Sandbox warning in [../SECURITY.md](../SECURITY.md) applies to
local Docker too. Never mount the Docker socket or a real home/project/data
directory into Sandbox.

## Hygiene

```powershell
git status --short
git diff --check
node scripts/ci/check-markdown-links.mjs
node scripts/ci/check-repository-hygiene.mjs
rg -n "System\.out\.println|console\.log\(" backend apps packages -S
```

Do not commit generated output, logs, screenshots, exports, backups, `.env`,
private keys, hidden tests, local debug context, or `archive/`.

## Server boundary

Server work follows [deployment.md](deployment.md) and
[operations.md](operations.md). `/opt/aioj` is not a Git worktree. Local
development success does not authorize a production release, Flyway run,
provider call, backup, data mutation, firewall change, or cleanup.
