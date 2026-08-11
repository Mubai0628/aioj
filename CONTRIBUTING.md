# Contributing to AIOJ

## Before changing code

1. Read `AGENTS.md` and the current design/runbook documents it references.
2. Inspect the real Git status and preserve unrelated work.
3. Define scope, risks, acceptance criteria, and verification commands.
4. Keep changes minimal and do not revive removed Vue or study-subgroup code.

## Engineering rules

- Browser requests go through `/api/v1/**` on the gateway.
- Frontend entity IDs are strings; do not coerce 16+ digit IDs to JavaScript
  numbers.
- Cross-service contracts belong in `backend/api-contract`.
- Add a new Flyway migration; never edit a historical migration.
- Judging goes through RabbitMQ and the external Sandbox.
- AI drafts require review/approval before import, and AI outputs remain
  advisory where the product says so.
- Do not expose credentials, hidden tests, full judge output, private source,
  prompts containing private material, or personal data.

## Verification

Run the narrowest relevant tests, then the shared checks when contracts change:

```powershell
mvn -f backend/pom.xml test
npm ci --include=optional
npm.cmd run typecheck:react
npm.cmd run test:auth
npm.cmd run build:react
git diff --check
```

Generated `target/`, `dist/`, logs, reports, exports, backups, `.env`, and local
history under `archive/` must not be committed.

## Pull requests

Explain the problem, product decision, security/privacy impact, migrations,
tests, manual verification, and rollback considerations. Mark work
`IMPLEMENTED_UNVERIFIED` when manual or production validation is still pending.
