# AIOJ production deployment

Last reviewed: 2026-08-11

This is the executable deployment authority for the image-only single-node
production topology. Production data always comes from the production server;
never substitute a workstation database, user export, testcase directory, or
secret file.

> [!CAUTION]
> The selected topology colocates a privileged go-judge Sandbox with the
> application and data services. A container/runtime/kernel escape can become
> host-root-equivalent compromise of every service and dataset. The maintainer
> has accepted this downgrade from an isolated judge host. Compensating
> controls reduce ordinary abuse but do not make the designs equivalent. Read
> [../SECURITY.md](../SECURITY.md) before operating the stack.

## Deployment contract

- Host role: `aioj_a` runs MySQL, Redis, RabbitMQ, gateway, auth, problem, AI,
  judge-worker, Sandbox, and both React web containers.
- Root: `/opt/aioj` is deployment state, not a Git worktree.
- Application images: private `ghcr.io/mubai0628/aioj-*` packages selected by
  immutable digest from a formal GitHub Release manifest.
- Third-party images: fixed Linux/amd64 digests in
  `deploy/compose.production.yml`.
- Secrets: `/opt/aioj/env/app.env`, root-owned mode `0600`; never stored in
  GitHub, image layers, release assets, command lines, or chat.
- Flyway: only auth-service enables Flyway. A successful schema migration is
  forward-only and never automatically rolled back.
- Legacy memory migration: `AI_LEGACY_MEMORY_MIGRATION_MODE=off` during normal
  operation. Do not rerun `apply` because a workstation or old note suggests
  it; verify the production server's own mapping state first.

```text
/opt/aioj/
  compose.production.yml
  env/app.env
  deploy.env
  deploy.previous.env
  deploy-history/
  backups/
  CURRENT_RELEASE
```

## GitHub release path

A published stable SemVer release (`vX.Y.Z`) triggers the pinned Actions in
`.github/workflows/release.yml`:

1. Verify the tagged commit is on `main`.
2. Build eight Linux/amd64 images.
3. Push private GHCR packages and attach SBOM/provenance attestations.
4. Assemble `service -> image@digest` in `image-manifest.json`.
5. Publish the manifest, manifest checksum, and deployment bundle as release
   assets.
6. Wait for approval in the protected GitHub `production` environment.
7. Invoke the restricted server gate with only the tag and manifest SHA-256.

The server gate downloads and validates both release assets, rejects an
unexpected namespace, tag-only image, missing service, wrong platform, or bad
digest, stores the previous digest set, pulls images, and performs health
checks. Failed health checks trigger an image/Compose rollback. Database state
is not rolled back.

## One-time host bootstrap

Run bootstrap only from the cloud console as root after reviewing the script:

```bash
bash scripts/deploy/bootstrap-aioj-host.sh /path/to/release-tree /root/aioj-deploy.pub
```

It creates locked user `aioj-deploy`, installs a `restrict` forced command,
keeps the user out of Docker and deployment groups, and grants only one sudo
entry: the root-owned deployment gate. It does not configure application
secrets or a GHCR token.

Then, as root:

1. Create `/opt/aioj/env/app.env` from the public schema and reuse only values
   verified on the production host. Keep mode `0600`.
2. Log Docker into GHCR using a dedicated classic PAT or fine-grained token
   with package-read permission supplied through `--password-stdin`; do not
   place the token in shell history.
3. Configure GitHub `production` secrets: deployment host, a dedicated
   non-interactive automation private key, and the fixed host-key line keyed
   by alias `aioj_a`. This key is intentionally separate from passphrase-
   protected human/Codex identities; its risk is contained by the server-side
   forced command, disabled forwarding/PTY, and single-command sudo policy.
4. Configure a required reviewer, protected `main`, and the deployment
   concurrency lock before publishing a release.

The deploy account has no PTY, password, agent forwarding, X11, local/remote
port forwarding, general sudo, or Docker-group membership.

## Mandatory preflight

Immediately before backup or cutover, stop if any of these are nonzero or
unhealthy:

- running/scheduled contest activity that would be disrupted;
- queued or unacknowledged judge messages, including DLQ;
- running AI draft, postmortem, plagiarism, export, replay, or other operation
  jobs;
- container restarts, OOM state, failed health, swap-in/out, disk pressure, or
  insufficient backup space.

Record the old release identity, Compose files, image IDs/digests, volume list,
and Flyway version without printing environment values.

## Backup and restore rehearsal

All backup inputs come from the production host. Create a root-only local
backup and a separately encrypted off-host copy. At minimum capture and verify:

- MySQL full logical dump with routines, triggers, events, single-transaction,
  and binary-safe output;
- Redis persistence and RabbitMQ definitions/data required by the chosen
  recovery point;
- testcase packages, operation artifacts, AI draft artifacts, and judge cache;
- old Compose/environment structure, current image IDs, release identity, and
  the former judge-host configuration/cache required for rollback.

Do not treat Sandbox temporary files as business data.

Restore the MySQL dump into a disposable temporary MySQL instance/volume and
run the release auth image against that clone. Verify Flyway reaches the target
version, source table counts are preserved, and the expected migration rows are
reasonable. Restore file/volume backups into disposable volumes and verify
checksums/readability. Any rehearsal failure stops the release.

## First single-node cutover

The first release differs from a routine digest update because it uses new
volumes and must avoid port conflicts with the old stack:

1. Complete and verify all backups and the restore rehearsal.
2. Confirm legacy-memory migration mode is `off` and inspect production mapping
   counts; do not import or reapply local data.
3. Pull all release images while the old stack is still serving, if resource
   headroom permits.
4. Enter a maintenance/write-closed window and recheck queues/jobs.
5. Stop the old stack without deleting volumes or images.
6. Create the new `aioj_*` volumes and restore the production database,
   Redis/RabbitMQ state as required, testcases, artifacts, and cache.
7. As root, create the one-use file
   `/opt/aioj/initial-cutover.approved` only after verifying the restored data.
8. Approve the GitHub production deployment. The root deployment gate removes
   the one-use approval file after success.
9. Start order enforced by Compose dependencies is infrastructure -> auth
   (Flyway) -> problem -> Sandbox/judge -> AI -> gateway -> web.
10. Keep writes closed until all acceptance checks pass.

Do not change DNS, firewall, security groups, CORS, public ports, or business
domains during this first cutover. Change one failure domain at a time.

## Acceptance

- every container is running and healthy with unchanged restart/OOM counters;
- Flyway is at the release target and reports no failed migration;
- gateway health is `UP`; both web entries return HTTP 200;
- login, problem list, contest list, and notification SSE pass through the
  gateway;
- one controlled AC submission is queued, executed by Sandbox, written back,
  and leaves the judge queue empty;
- Sandbox has no host/public port, host network, Docker socket, business mount,
  or application/database/provider/deployment secret;
- `JUDGE_CONCURRENCY`, prefetch, and max concurrency are all `1`;
- host memory reserve, swap, CPU throttling, OOM, restarts, and disk growth stay
  within the acceptance window.

Do not make a real DeepSeek call during default deployment acceptance. A paid
provider smoke test requires separate authorization.

## Rollback boundary

Before writes reopen, a failed first cutover may restore the complete old stack
and old volumes. After writes reopen, rollback means previous image digests
against the new current volumes. Never start stale old volumes after new writes
exist. Preserve the old project root, old volumes, old images, and former judge
host until a separate cleanup is authorized after the new release remains
stable.
