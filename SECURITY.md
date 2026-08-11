# Security policy

## Reporting

Do not open a public issue containing credentials, personal data, hidden test
material, participant source, provider prompts, private infrastructure details,
or exploitation steps. Contact the repository maintainer privately through the
verified contact channel on the maintainer's GitHub profile. Include only the
minimum evidence required to reproduce the issue.

## Supported version

Security fixes target the current `main` branch and the latest formal SemVer
release. Older releases may be unsupported unless a notice states otherwise.

## Privileged Sandbox risk and accepted downgrade

The single-node production topology intentionally runs go-judge in a
`privileged` container because that is required by the currently integrated
Sandbox runtime. Normal submissions are still constrained by go-judge,
namespaces, cgroups, execution time, output, process, and memory limits. That
is useful isolation for ordinary untrusted code; it is not a proof against a
Sandbox, container-runtime, Docker, or kernel vulnerability.

A successful escape may allow an attacker to:

- obtain host-root-equivalent control;
- read or modify the database, user data, tokens, environment secrets, and
  provider/deployment credentials;
- alter judge results, application images, containers, or internal traffic;
- access RabbitMQ, MySQL, Redis, and business services;
- exhaust CPU, memory, processes, disk, or network and make the entire site
  unavailable;
- establish host-level persistence.

Colocating Sandbox, application services, and databases on `aioj_a` increases
both blast radius and single-point-of-failure impact compared with an isolated
judge node. The maintainer explicitly selected and accepted this downgrade.
Documentation and UI claims must never describe the merged topology as
security-equivalent to a dedicated judge host.

Compensating controls are mandatory but incomplete: no public Sandbox port, no
host networking, no Docker socket, no business mounts, no database/JWT/AI or
deployment secrets, read-only testcase data, an isolated temporary volume and
judge network, bounded resources and logs, one judge worker at first, prompt
host/runtime updates, and monitoring for OOM, swap, throttling, restarts, and
disk growth. These reduce probability and ordinary abuse; they cannot remove
escape risk.

## Secrets and production data

- GitHub stores only the restricted deployment SSH identity, host value, user,
  and fixed known-host entry.
- Application and GHCR read credentials remain root-only on the server.
- Production data is backed up from the server itself. Local databases and
  local user data are never imported as a production substitute.
- Never put secrets in image layers, Compose files, release manifests, build
  logs, commands, issues, or documentation.
- Rotate a secret immediately if exposure is suspected; do not merely delete
  it from the newest commit because Git history and caches may retain it.

## Deployment security boundary

Production deploys immutable GHCR digests through a forced-command SSH user and
a root-owned validation entrypoint. Production does not run `git pull`, does
not use `latest`, and does not grant the deploy user Docker-group or general
sudo access. Database schema changes are forward-only and are not automatically
rolled back with application images.
