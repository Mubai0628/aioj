# Delivery architecture

[中文](../zh/delivery-architecture.md)

## Goal

AIOJ uses GitHub Actions, private GHCR packages, immutable image digests, and a protected production environment to create a traceable relationship between source, build artifacts, and the version running in production. The production server neither pulls a Git worktree nor builds source in place.

## Delivery flow

```text
reviewed main
   -> formal SemVer GitHub Release
   -> CI tests and repository policy checks
   -> build eight Linux/amd64 images
   -> private GHCR packages + SBOM + provenance
   -> service -> image@digest manifest
   -> production approval
   -> restricted SSH deployment gate
   -> health validation and version record
```

The nine application images are gateway, auth, problem, ai, judge-worker, agent-executor, sandbox, web-user, and web-admin. The Agent tools' agent-sandbox runs the same sandbox image digest with its own configuration and token. Third-party infrastructure images are also pinned to reviewed platform digests.

## Immutable version identity

A production version has three complementary identities:

- a Git release tag for humans;
- a Git commit for source;
- an OCI digest for the exact runtime image content.

Production Compose accepts complete `image@sha256:...` references rather than `latest` or movable tags. The release manifest records one digest per service and has its own checksum.

## CI and supply-chain evidence

CI checks the backend, React applications, Compose, Markdown links, repository hygiene, and secret leakage. Image builds produce SBOM and provenance attestations. Third-party Actions are pinned to reviewed commit SHAs to reduce supply-chain drift.

The backend tests include ai-service architecture tests (ArchUnit, test scope only): the model call package is used only through its `api` subpackage and depends on neither persistence, agent-harness, nor the rest of ai-service; the legacy model call paths and ai-service's own Agent loop and tool pipeline have been deleted, and the tests assert they do not come back; ai-service does not use agent-harness internals; events are published only through the unified channel's interface; the ai-service process starts no child processes, and code runs only in agent-executor.

`backend/agent-harness` holds the domain-free agent loop and tool pipeline. Its architecture tests require that it depends at runtime on the JDK and Jackson only, that each subpackage's `internal` package is used only inside that subpackage, that dependencies point from runtime to tools to context, and that its sources contain no words of the judge domain. Fixtures prove that every rule reports a violation.

## Production boundary

- The production root stores image-only Compose, environment files, current/previous digest sets, and deployment history, but no Git worktree.
- Application secrets and GHCR-read credentials stay in root-only server configuration, never GitHub Releases or image layers.
- The GitHub deploy identity has no Docker group or general sudo; a forced command can invoke only the root-owned deployment entrypoint.
- The entrypoint accepts only a valid release tag and manifest checksum and validates namespace, platform, and digest.

## Data and migration

Production data always originates on the production server; workstation databases, user exports, and local testcase directories are never substitutes. auth-service applies Flyway during a controlled startup stage. Migrations are forward-only; rolling back application images does not reverse the schema automatically.

## Startup dependencies

The production stack forms a dependency graph: MySQL, Redis, and RabbitMQ become healthy first; auth and problem start next, independently; the judge path waits for problem and the judge Sandbox; AI waits for problem and judge-worker; the gateway waits for auth, problem, and AI; web comes last. Health checks pass only when dependencies are actually serviceable, avoiding a process-running-but-application-unavailable state. Flyway runs only in auth and no other service waits for its migrations explicitly: on an empty database (the first cutover) or in a release with new migrations, a service that comes up first can crash once on a missing table and is restarted by `restart: unless-stopped` (seen once on 2026-10-31 with ai-service on an empty database in the local Compose).

## Stopping

The backend images start with `sh -c "exec java $JAVA_OPTS -jar /app/app.jar"`: the shell expands `JAVA_OPTS`, then `exec` hands PID 1 to Java, so `docker stop`'s SIGTERM reaches the JVM. Only then do Spring Boot's graceful shutdown (up to 30 seconds per phase) and `@PreDestroy` hooks run (for example agent-executor deleting its cached compiled artifacts from the sandbox), and a normal stop exits with 143. The entrypoint used to have no `exec`: PID 1 was `sh`, SIGTERM was ignored, and every stop waited the full 10 seconds and ended in a kill (137). Measured locally on the official images, an idle backend service stops in 0.6–2.5 seconds with exit code 143 and graceful-shutdown start and completion lines in its log; with a 2-second run in flight, agent-executor finishes the request and stops in about 3.4 seconds; the cached artifacts in the sandbox are gone after the stop. A shutdown that needs longer than Docker's default 10 seconds is still killed, and the production Compose sets no `stop_grace_period`; whether to add one, and how long, is decided when deployment resumes, from the real duration of in-flight requests.

## Rollback model

Routine rollback restores the previous image-digest set while retaining current data volumes. A failed deployment may roll back images automatically; database structure and newly written data are not reversed automatically. During an initial cutover, the complete old stack remains recoverable only before writes reopen. After the new version writes data, old volumes are stale recovery points and must not be put back online directly.

## Single-node impact

The selected delivery target combines application, data, and judging on one node. Release acceptance therefore observes container health, restart/OOM state, host memory and disk, broker state, the judge path, and the privileged-Sandbox boundary together. Capacity results from the former split-node topology do not establish a single-node SLA.
