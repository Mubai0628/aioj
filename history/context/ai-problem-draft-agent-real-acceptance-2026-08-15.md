# AI problem-draft Agent real acceptance — 2026-08-15

## Scope

- Local-only diagnosis and repair of the problem-draft Agent stopping after one tool call.
- Real DeepSeek plus Docker Sandbox acceptance for isolated CF 800, 1400, and 1800 workspaces.
- No production connection, approval, import, publication, commit, push, or release.

## Decisions and fixes

- A problem-draft Agent run cannot complete until `draft.finalize` succeeds. Ordinary prose after an earlier tool call is treated as premature completion; the runtime preserves state and continues or uses `STANDARD_TOOL_FALLBACK`.
- `ES_OUTPUT_LIMIT` and `ES_COPY_OUT_LIMIT` default to 64 MiB so the Sandbox can return artifacts up to the Agent's 50 MiB package ceiling.
- A successful package-generation response without a cached package handle becomes structured `PACKAGE_CACHE_FILE_MISSING`, never a false success.
- Workspace responses expose the current working-state title, and continuation jobs retain that title instead of the generic “Continue problem draft”.

## Acceptance evidence

| CF rating | Samples | Full package | Artifact | Outcome |
| ---: | ---: | ---: | ---: | --- |
| 800 | 3/3 | 8/8 | 8.1 MiB | `PENDING_REVIEW`, `AGENT_VERIFIED` |
| 1400 | 3/3 | 12/12 | 1.2 MiB | `PENDING_REVIEW`, `AGENT_VERIFIED`, fallback exercised |
| 1800 | 3/3 | 16/16 | 20.4 MiB | `PENDING_REVIEW`, `AGENT_VERIFIED`, same-workspace recovery exercised |

Real Chrome verified desktop, 400×858, and 440×956 layouts without horizontal overflow. The high-difficulty warning and safe tool trace were visible; raw reasoning was not.

## Verification snapshot

- ai-service final full suite: 973 tests passed, 0 failures, 0 errors, 6 skipped.
- judge-worker: 33/33 passed.
- problem-service: 227/227 passed.
- React typecheck and both user/admin production builds passed.

## Traceability

Keywords: `problem_draft_agent`, `draft.finalize`, `PACKAGE_CACHE_FILE_MISSING`, `ES_COPY_OUT_LIMIT`, `STANDARD_TOOL_FALLBACK`, CF 800, CF 1400, CF 1800.

Primary docs: `docs/zh/problem-draft-agent-architecture.md`, `docs/en/problem-draft-agent-architecture.md`.
