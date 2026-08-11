# Contest and User Group Progress Tracker

Last reviewed: 2026-08-04

## Current Status

Phase 1-11 and Phase 13-14 are marked `VERIFIED` by user acceptance. Phase 12
was cancelled, removed, and verified after the product direction was narrowed
to a single user-group model. Phase 15 is implemented and awaiting manual
acceptance.

The 2026-08-04 review found no new contest acceptance evidence that changes
these states. In particular, Phase 15 remains `IMPLEMENTED_UNVERIFIED`; recent
AI draft and deployment work does not promote it to `VERIFIED`.

## Phase Status

| Phase | Scope | Status | Notes |
|---|---|---|---|
| Phase 1 | User group foundation | VERIFIED | User groups and members remain. Study subgroup features are removed from UI/API. |
| Phase 2 | Contest blueprint and problem arrangement | VERIFIED | `contest` is reusable blueprint data. |
| Phase 3 | Run participants and submission context | VERIFIED | Submissions carry explicit contest/run/problem/participant context. |
| Phase 4 | ACM scoreboard, freeze, snapshots, time views | VERIFIED | Public/private scoreboard and snapshot flow are run-scoped. |
| Phase 5 | Staff source viewing and audit | VERIFIED | Teacher/admin source access is audited. |
| Phase 6 | Scoreboard and submission exports | VERIFIED | CSV/XLSX exports do not include source code. |
| Phase 7 | AI plagiarism report | VERIFIED | Similarity evidence and AI risk text are advisory. |
| Phase 8 | IOI/OI partial score | VERIFIED | Case-level score and case result display are available. |
| Phase 9A | Registration, run history, minute timeline | VERIFIED | New run flow writes `contest_run_id`. |
| Phase 9B | Blueprint/run split and pre-start access control | VERIFIED | Run owns time, registration, and access windows. |
| Phase 9C | Resolver/replay reveal | VERIFIED | ACM resolver sessions are run-scoped. |
| Phase 9C+ | Public unfreeze, archive restore, soft delete | VERIFIED | Deleted contest objects are hidden from normal APIs. |
| Phase 9D | Teacher run-level AI postmortem | VERIFIED | Deterministic stats plus AI Markdown; no source leakage. |
| Phase 10 | Student personal postmortem and memory confirmation | VERIFIED | Weakness candidates require student acceptance. |
| Phase 11 | Plagiarism graph and fairness alerts | VERIFIED | Alerts are risk signals, not cheating decisions. |
| Phase 12 | Training-report and gradebook feature | CANCELLED_REMOVED_VERIFIED | Entry, API, service, DTOs, and report tables removed/deprecated. |
| Phase 13 | Contest clarifications and announcements | VERIFIED | Run-scoped announcements, student questions, private/public staff replies. |
| Phase 14 | Advanced IOI/OI subtasks and C++ checker | VERIFIED | Subtask metadata, subtask best-over-submissions scoring, and AIOJ_JSON C++ checker support. |
| Phase 15 | Scale, audit, and governance | IMPLEMENTED_UNVERIFIED | Async exports, plagiarism checks, AI postmortems, artifacts, audit center, admin operations page, and non-contest archive/restore/soft-delete governance. |
| Team contest mainline | Teams, captains, substitutes, team scoreboard | DEFERRED_NOT_PLANNED | Not part of the current roadmap. |

## Current Product Decisions

- User group is the only maintained organization model.
- Study subgroup functionality is not exposed or maintained.
- Training reports / gradebook from the removed Phase 12 implementation are not part of the product; removal is verified.
- Future user-group statistics, if needed, must be planned as a new feature.
- Scoreboard, plagiarism, source audit, resolver, postmortem, and exports must stay run-scoped.
- Non-contest archive/restore/soft-delete governance now covers user groups,
  problems, testcase packages, and AI drafts; manual acceptance is still pending.

## Active Verification Commands

Backend:

```powershell
mvn -pl auth-service -am test
mvn -pl problem-service -am test
```

Frontend:

```powershell
npm.cmd run typecheck:react
npm.cmd run build:admin:react
npm.cmd run build:user:react
```

Hygiene:

```powershell
git diff --check
rg -n "System\.out\.println|console\.log\(" backend apps packages -S
rg -n "change-me|replace-" backend apps packages deploy docs -S
```

## Phase 13 Verification Evidence

Commands run on 2026-06-09:

```powershell
mvn -pl problem-service -am test
npm.cmd run typecheck:react
npm.cmd run build:user:react
npm.cmd run build:admin:react
```

Results:

- `problem-service`: PASS, 72 tests, 0 failures, 0 errors.
- React typecheck: PASS.
- User app build: PASS, Vite chunk size warning only.
- Admin app build: PASS, Vite chunk size warning only.

## Manual Acceptance Focus After Phase 13

1. Teacher publishes a pinned announcement; students can see it on the run
   detail before start.
2. Student asks a clarification during an active run.
3. Staff private reply is visible only to the asking student.
4. Staff public reply is visible to other eligible students and hides the
   questioner's identity.
5. Archived announcements disappear from the student view and reappear after
   restore.

## Phase 14 Verification Evidence

Commands run on 2026-06-09:

```powershell
mvn -pl judge-worker -am test
mvn -pl problem-service -am test
npm.cmd run typecheck:react
npm.cmd run build:admin:react
npm.cmd run build:user:react
git diff --check
rg -n "System\\.out\\.println|console\\.log\\(" backend apps packages -S
rg -n "change-me|replace-" backend apps packages deploy docs -S
```

Results:

- `judge-worker`: PASS.
- `problem-service`: PASS, 72 tests, 0 failures, 0 errors.
- React typecheck: PASS for user and admin apps.
- Admin React build: PASS, with only existing Vite chunk-size warnings.
- User React build: PASS, with only existing Vite chunk-size warnings.
- `git diff --check`: PASS; only line-ending warnings from Git.
- Debug-log scan: PASS; no `System.out.println` or `console.log(` hits.
- Placeholder scan: existing config defaults and documented hygiene commands only.

Manual acceptance focus:

1. Upload a testcase package with `subtasks[]` and a C++ `AIOJ_JSON` checker.
2. Create an IOI run and set a contest problem to `SUBTASK_MIN_CASE_MAX_OVER_SUBMISSIONS`.
3. Submit partial and full solutions; confirm case detail, subtask score, scoreboard, snapshot, and export behavior.
4. Confirm old ACM and standard stdout-comparator packages still judge normally.

User acceptance:

- Phase 14 verification passed; do not enter Phase 15 until this baseline is
  treated as verified.

## Phase 15 Verification Evidence

Commands run on 2026-06-09:

```powershell
mvn -pl problem-service -am test        # PASS, 75 tests
mvn -pl auth-service -am test           # PASS, 7 tests
mvn -pl ai-service -am test             # PASS, 93 tests
npm.cmd run typecheck:react             # PASS
npm.cmd run build:admin:react           # PASS, Vite chunk-size warning only
npm.cmd run build:user:react            # PASS, Vite chunk-size warning only
git diff --check                        # PASS, line-ending warnings only
rg -n "System\\.out\\.println|console\\.log\\(" backend apps packages -S
                                        # PASS, no matches
rg -n "change-me|replace-" backend apps packages deploy docs -S
                                        # REVIEWED, existing env defaults and documented hygiene commands only
```

Manual acceptance focus:

1. Operation jobs page lists queued/running/completed/failed export jobs.
2. Failed jobs can be retried; completed jobs can download artifacts.
3. Audit events page lists source access and job lifecycle events.
4. Artifacts and audit summaries do not include source code, secrets,
   stdout/stderr, or hidden testcase data.

Non-contest archive governance added on 2026-06-10:

- `V35__non_contest_archive_governance.sql` adds lifecycle fields for user
  groups, problems, testcase packages, and AI drafts.
- Admin user groups, problem library, testcase packages, and AI drafts expose
  archive / restore / soft delete controls.
- Soft delete is allowed only after archive and is not recoverable through
  frontend/API; database-only recovery remains possible.
- Archive / restore / soft delete operations write operation audit events.

## Manual Acceptance Focus After Phase 12 Removal

1. Admin sidebar has no training-report entry.
2. Admin user-group page shows only user groups and user-group members.
3. Old subgroup API paths are not exposed.
4. Contest run registration by allowed user groups still works.
5. Plagiarism, postmortem, scoreboard, exports, resolver, and source audit are unaffected.

## Contest AI Guard Redesign (2026-08-06)

Status: `IMPLEMENTED_UNVERIFIED`. Current design authority:
[AIOJ_AGENT_CORE_V3_IMPLEMENTATION_DESIGN.md](AIOJ_AGENT_CORE_V3_IMPLEMENTATION_DESIGN.md).

Scope landed:

- V58 migration: snapshot `visibility`, blueprint `ai_policy_mode`/`ai_policy_notes`,
  run policy snapshot columns.
- ai-service `ContestTurnGuard` replaces the old policy guard, participant leak
  interceptor, and the dead global `ContestProblemLeakGuard`; per-turn server-side
  pipeline with embedding match, gray-zone LLM judge (relatedness only), and
  deterministic PRIVATE/STRICT refuse vs PUBLIC constrain.
- Every participant evaluation (`AI_CONTEST_GUARD_EVALUATED`) and degraded pass
  (`AI_CONTEST_GUARD_DEGRADED`) is audited; admin AI-usage panel aggregates the
  new actions.
- Teacher invites stop at `INVITED` until the student accepts; student app has a
  "my invitations" section; staff in the participant list are equally restricted.
- Guard window is `[startAt, endAt + grace)` with grace default 600s.

Verification evidence:

- `mvn -f backend/pom.xml -pl problem-service -am test` PASS (176 tests).
- `mvn -f backend/pom.xml -pl ai-service -am test` PASS (429 tests; one
  pre-existing embedding test requires `DASHSCOPE_API_KEY`/`AI_EMBEDDING_API_KEY`
  to be unset locally).
- `npm.cmd run typecheck:react`, `build:user:react`, `build:admin:react` PASS.

Manual acceptance focus:

1. During a running run, a participant asking about a PRIVATE problem is refused
   in any conversation; a PUBLIC problem gets hints-only answers with code
   responses replaced.
2. Non-participants are unaffected by design; degraded passes and all decisions
   appear in the admin AI-usage panel per run.
3. Invited students see the invitation, cannot enter before accepting, and join
   as participants after accepting.
4. Threshold calibration: paraphrased Chinese references to contest problems
   land in the gray zone and are judged correctly; tune
   `matchThreshold`/`recallThreshold` with real data.

Live-test findings (2026-08-06): statement-pasting turns are intercepted
correctly, but short follow-up questions, problem-page-originated chats without
pasted statements, and missing conversation-level stickiness bypass the guard;
blocked message content also leaks back into later prompts via conversation
history. The original evidence is preserved in local `archive/`; the resulting
context, rule-constraint, and problem-matching redesign is governed by Agent
Core V3. This tracker does not supersede the V3 implementation contract.
