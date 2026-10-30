# Security architecture

[中文](../zh/security-architecture.md)

## Goal

AIOJ handles untrusted user code, credentials, hidden tests, contest problems, participant source, and AI-provider data. The security architecture combines least privilege, server-side authorization, data minimization, asynchronous isolation, auditability, and defense in depth.

## Data classification

| Data | Protection |
| --- | --- |
| secrets, tokens, database credentials | server secret storage only; never Git, image layers, logs, or clients |
| hidden tests and testcase packages | file/volume storage, authorized by problem and judge job, never public download |
| participant source | visible to its student; teacher access uses audited APIs; plagiarism exports omit full source by default |
| private contest statements | visible only to requests satisfying participant and time policy |
| AI conversations, memory, and profiles | minimal collection, user-scoped authorization, traceable provenance, user-confirmed candidates |
| judge output | clients receive product-level summaries; complete internal output remains bounded and restricted |

## Identity and authorization

The gateway is the browser entry, but target services enforce final business authorization. JWT, role, and resource ownership jointly determine access; an internal service token never replaces user-level resource checks. Hiding a frontend button is not a security control.

Internal APIs use directional `X-Internal-Token` credentials: ai → problem, judge → problem, problem → ai, and problem → judge each have an independent random token; ai → agent-executor retains the executor-only token. The gateway and auth-service hold none of them. Targets identify callers with fixed-length digest comparisons, then enforce a path-segment prefix allowlist: missing or unknown credentials return 401; recognized callers without prefix permission return 403. Unregistered prefixes are denied by default. Prefixes are matched on the path as the server normalizes it, so `..` or `;` forms cannot slip past the check; judge-worker and agent-executor, which have no Spring Security, check the token again in their controllers. Ordinary JWTs cannot replace internal credentials. The unused, retired contests ai-policy HTTP entry has been removed.

Only services that need a credential receive it; the old shared `AIOJ_INTERNAL_API_TOKEN` is no longer used for application traffic. Inbound authentication accepts current and optional `*_NEXT` values, while outbound calls use only current. Rotate by installing NEXT on the target, updating the caller, then promoting the target's current value and clearing NEXT, restarting one service at each step. Production uses independent high-entropy random values, never derivatives of a shared secret. Production Compose rejects missing required variables; empty local values fail closed. Logs contain only caller, path, and denial reason, never credentials.

## Rate limits

- The gateway reads `X-Forwarded-For` only when the connection comes from a trusted proxy, and only its N-th entry from the right (N trusted proxies in front of the gateway, default 1: the web nginx); entries a client wrote further left do not count. The result goes downstream as `X-Aioj-Client-Ip`, after any client-sent header of that name is dropped.
- Per address: login/refresh/register, submissions, AI chat, code runs and problem-draft jobs each have a Redis token bucket keyed by kind and client address, IPv6 by its /64. Defaults assume a few people behind one address; a shared egress address such as a lab's can be listed as a shared network that skips the per-address limits. A Redis outage lets requests through and is counted.
- Per user: failed logins are counted per account and address (5 in 15 minutes lock for 15 minutes) with an account-wide ceiling (50 in 15 minutes), so wrong passwords elsewhere cannot lock a student out; 30 submissions a minute; at most 2 AI answers running at once, on top of the AI quota; code-run per-minute, per-day and spacing limits; at most 3 queued or running problem-draft jobs per admin.
- Every refusal is HTTP 429 with code 42900, `Retry-After` and an error key (the seconds also in `errorParams.retryAfterSeconds`); the page says which limit applied and roughly when to try again. Every threshold is an environment variable.

## Judge isolation

problem-service publishes jobs, while judge-worker only orchestrates and calls Sandbox. Sandbox has no Docker socket, host network, business directory, database, JWT, AI, or deployment secret. Testcases are read-only; temporary workspace is isolated; CPU, memory, PID, time, output, and logs are bounded.

### Privileged-Sandbox risk

The current go-judge integration uses a `privileged` container. Normal code remains constrained by namespaces, cgroups, and execution limits, but a successful exploit against Sandbox, Docker, the container runtime, or the Linux kernel could still:

- obtain host-root-equivalent control;
- read or alter databases, user data, secrets, and tokens;
- modify judge results, images, containers, or internal traffic;
- access MySQL, Redis, RabbitMQ, and business services;
- exhaust CPU, memory, processes, disk, or network;
- establish host-level persistence.

Single-node deployment increases blast radius and single-point-of-failure impact. Internal networks, read-only mounts, resource limits, and secret isolation are mandatory compensating controls, but they cannot make the single-node topology security-equivalent to an isolated judge node.

### agent-sandbox

agent-sandbox, added for Agent tool execution, reuses the judge Sandbox image and also runs `privileged`, so the consequences listed above apply to it as well. Compensating controls:

- it joins only the internal `agent-sandbox-net` network and publishes no port; the only other member of that network is agent-executor, which holds no database, message-queue, JWT, or AI secret;
- MySQL, Redis, RabbitMQ, business services, and the judge Sandbox are not on its network;
- ai-service reaches it only through agent-executor over app-net, with agent-executor's own token; ai-service joins no sandbox network and holds no sandbox token;
- its root filesystem is read-only, `/tmp` is a size-capped tmpfs, and it mounts no business volume; PID, memory, CPU, output, and copy-out are bounded;
- its token is separate from the judge Sandbox's.

Whether gVisor (`runsc`) can host go-judge and so remove `privileged` has not been evaluated yet, because that needs a Linux host. Until then agent-sandbox stays `privileged`, and the maintainer has accepted this risk.

## AI security

- The model and its output are untrusted; authorization and tool execution remain server-side.
- Every model call goes through the model call package, and architecture tests forbid any bypass; each call leaves an observation record.
- Private statements, hidden tests, and unrelated personal data are not sent to the provider.
- Chat stream events carry only text increments and each tool's name, status, and duration, never tool arguments, tool results, or model reasoning. Contest-restricted turns do not stream; they are replayed only after the output check passes.
- The tutor's code tools run only the turn's selected code, the code the problem page's editor sends with the turn, or the student's own submission, never model-supplied source; contest-restricted turns do not have them.
- The problem page's self-test run uses only public samples and the student's own input, never hidden tests, and makes no submission; the server checks problem visibility and the contest context, and limits runs by gap, concurrency, and counts.
- Prompts, tool results, and recalled content distinguish instruction authority from data provenance.
- AI plagiarism and postmortem output is advisory evidence, not an automatic disciplinary decision or final grade.
- Memory writes require a candidate, evidence, conflict checks, and user confirmation.
- Problem-draft workspace, user, draft, execution, and artifact ownership are server-bound; model tool arguments cannot select or override these resources.
- Raw DeepSeek `reasoning_content` exists only in memory for the current tool loop and never enters the database, logs, audit, or frontend.
- Code, generators, packages, and self-test runs reach agent-sandbox only through agent-executor, never the judge Sandbox; hidden cases return aggregate results to the model, and general tool audit excludes raw source and process output.
- Manual ZIPs require SHA, manifest, path, symlink, duplicate-entry, and expanded-size checks. Extraction counts the bytes actually written against per-entry and whole-package limits, which is what stops a decompression bomb; packages are no longer refused by compression ratio, because real max tests (long runs of equal values) compress far beyond any fixed threshold; imports without full-package execution acceptance are explicitly `MANUAL_UNVERIFIED`.

## Data retention

- The AI service cleans up once a night (on in production, off locally by default): a conversation the student deleted goes 30 days later, with its messages, turns, and run traces; one with contest records goes after 365 days, so deleting it cannot erase AI use during a contest.
- Tool calls, context manifests, and run records of student turns are kept 90 days; a draft workspace's runs follow the draft and go 30 days after it is deleted.
- Draft testcase package files: the current one stays; replaced, failed, and deleted drafts' files go after 30 days, and their rows stay as metadata. Draft execution output is kept 24 hours.
- Contest AI guard records, operation audit, and judge audit are not cleaned up. A pass logs only counts per category; every period and the switch are environment variables.
- Audit APIs return ids as strings, and integers of 16 or more digits inside their records become strings too, so a browser never drops the last digits of a snowflake id.

## Error messages

- An error a page receives is an error key, the key's text, and safe parameters (the user's own input, numbers, status codes), plus the traceId; exception text and the answers of downstream services and model providers go only to the log, which itself records only types and status codes. Stored errors are error keys, and older records are turned into keys when read.
- When a tool fails, the model sees a category and the tool's own fixed explanation, never exception text or the executor's response body.
- A judge system error tells the student only what kind of cause it was; a custom checker's compile output and stderr never reach the submission record.

## Secrets and delivery

The four direction variables are `AIOJ_INTERNAL_TOKEN_AI_TO_PROBLEM`, `AIOJ_INTERNAL_TOKEN_JUDGE_TO_PROBLEM`, `AIOJ_INTERNAL_TOKEN_PROBLEM_TO_AI`, and `AIOJ_INTERNAL_TOKEN_PROBLEM_TO_JUDGE`, with optional target-only `_NEXT` values. The executor uses `AGENT_EXECUTOR_INTERNAL_TOKEN` and inbound-only `_NEXT`. Generate each production value independently with an operating-system CSPRNG (at least 32 bytes) and deliver it directly to restricted secret storage. Restart one service at a time in this order: target NEXT → caller current → target promotes current and clears NEXT; never switch both ends simultaneously.

GitHub stores only restricted deployment identity and fixed host-key material required by the release path. Application and GHCR-read credentials remain in root-only server configuration. Production uses immutable image digests rather than `latest`, and the server stores no Git worktree.

## Recovery boundary

Production backups originate only from the production server and protect the database, broker state, testcase packages, and required artifacts. Image rollback uses current data volumes; a successful Flyway migration is not automatically reversed. Restore and cleanup are separately authorized operations, never incidental effects of an application release.

## Responsible disclosure

Report security issues through the maintainer's verified private channel. Never publish credentials, personal data, hidden tests, private source, infrastructure details, or directly actionable exploitation steps in a public issue.
