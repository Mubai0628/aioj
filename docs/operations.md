# AIOJ operations

Last reviewed: 2026-08-11

Production is an image-only single-node stack rooted at `/opt/aioj`. Use the
restricted release path for deployment; do not SSH in to run `git pull`, build
source, edit image tags, or invoke bare `docker compose` from an unrelated
directory.

> [!CAUTION]
> The privileged Sandbox shares the host with application and data services.
> A successful escape can compromise the entire host and dataset. This is an
> accepted security downgrade, not equivalent to an isolated judge node. See
> [../SECURITY.md](../SECURITY.md).

## Default authority

Read-only health inspection is the normal Agent authority. Deployment,
restart, rollback, backup, restore, Flyway handling, data change, secret
rotation, network/firewall change, and cleanup each require explicit current
authorization. Never print environment values, tokens, private keys, database
rows, hidden tests, participant source, or complete judge output.

## Explicit Compose invocation

On the host, the root-owned helper uses:

```bash
docker compose \
  --env-file /opt/aioj/env/app.env \
  --env-file /opt/aioj/deploy.env \
  -f /opt/aioj/compose.production.yml ps
```

The environment files are root-only. An operator may run the command but must
not display their contents.

## Read-only preflight

Collect only aggregates:

```bash
docker compose --env-file /opt/aioj/env/app.env \
  --env-file /opt/aioj/deploy.env \
  -f /opt/aioj/compose.production.yml ps
docker stats --no-stream
df -h /opt /var/lib/docker
free -m
vmstat 1 5
```

Also verify aggregate contest/job state, RabbitMQ ready/unacked/DLQ counts,
Flyway version, and current release identity. Do not continue if activity or
resource pressure makes a maintenance window unsafe.

## Routine release

A formal GitHub Release is the only normal release trigger. After CI and image
builds pass, review the immutable manifest and approve the protected
`production` environment. The forced command accepts only:

```text
deploy vX.Y.Z <64-character-manifest-sha256>
```

The server validates the manifest/bundle, snapshots the old digest set, pulls,
recreates, waits for health, and rolls images back on failure. Do not bypass the
gate with manual tag edits or `latest`.

## Health and evidence

`/usr/local/sbin/aioj-health-check` verifies all eleven containers, gateway
health, and both web entries. Supplement it with:

- container restart/OOM state and cgroup CPU throttling;
- host memory/swap and filesystem growth;
- RabbitMQ ready/unacked/DLQ counts;
- one explicitly authorized AC judge smoke after first cutover;
- browser login, problems, contests, notifications, and reports without direct
  calls to service ports.

Read only the minimal bounded logs needed for a diagnosed service. Redact
secrets and user/private content before sharing evidence.

## Sandbox controls

The production Compose must retain all of these:

- `privileged: true` documented as a risk, never as a security guarantee;
- no `ports`, host network, Docker socket, or application/data directories;
- only the internal `judge-net` network;
- read-only testcase cache and an isolated temporary volume;
- only the Sandbox token and execution limits, no JWT/database/AI/deploy secret;
- PID, CPU, memory, output, copy-out, timeout, and bounded-log limits;
- judge concurrency/prefetch/max concurrency fixed at one until new evidence
  supports a separately approved change.

## Rollback

The deployment gate copies the old `deploy.env` to `deploy.previous.env` before
activation. A normal image rollback uses those previous digests with current
volumes. It does not undo Flyway. If schema or data migration fails, stop and
preserve evidence/backups; do not invent reverse SQL.

Before writes reopen during the first cutover, a full old-stack restore remains
available. Once new writes exist, old volumes are stale and must not be started.

## Backup and recovery

Backups require an explicit recovery point and root-only destination. Verify
checksums, encrypt the off-host copy, and perform a disposable restore rehearsal
before relying on a backup. Required scopes and the first-cutover order are in
[deployment.md](deployment.md). Sandbox temp is excluded; testcase packages,
operation artifacts, AI draft artifacts, and judge cache are included.

Do not delete old volumes, images, project directories, or the former judge
host as part of a deployment. Cleanup is a separate destructive operation.

## Capacity truth

The merged 4-core/8-GiB host has approximate container memory limits of
6.25 GiB, leaving host reserve, but CPU limits intentionally oversubscribe the
four cores. Previous split-node results are historical evidence only. The
single-node 50-user target remains `IMPLEMENTED_UNVERIFIED`; monitor combined
login, API, judge, database, queue, AI-adjacent, memory, swap, throttling, and
disk behavior before claiming capacity or SLA.
