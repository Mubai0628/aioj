# Contest and User Group Research and Architecture

Last reviewed: 2026-08-04

## External Rule Baseline

- ICPC/ACM style contests use solved count, penalty, freeze/pending semantics,
  resolver reveal, and clear public/jury scoreboard separation.
- IOI/OI style contests use score-based ranking and case/subtask information.
- DOMjudge-style systems cache scoreboards and separate clarifications,
  announcements, scoreboard, and judging operations.

These references inform architecture, but AIOJ remains a teaching-oriented OJ
with audited AI assistance and user-group scoped contest operations.

## Organization Architecture

Current maintained model:

- User groups are the only active organization model.
- User groups are used for membership management, run registration eligibility,
  participant import, and teacher/admin permission checks.

Removed or not maintained:

- Study subgroup functionality is removed from UI/API.
- Team contest functionality is not planned in the current roadmap.
- The cancelled Phase 12 training-report implementation must not drive future
  feature work.

Database note:

- Historical rows or migrations may still contain old subgroup/report artifacts
  for compatibility. They are not active product surfaces.

## Contest Architecture

### Blueprint

`contest` stores reusable blueprint data:

- title and description,
- contest mode,
- scoring rules,
- problem arrangement.

### Run

`contest_run` stores one concrete opening:

- start/end/freeze time,
- registration access and allowed user groups,
- participants and snapshots,
- submissions,
- scoreboards and timelines,
- source audit,
- plagiarism jobs,
- resolver sessions,
- teacher/student postmortem reports.

Run-scoped operations must pass `runId`. Aggregating multiple runs is not a
default behavior.

## Snapshot Rules

- Participants are snapshotted when added/approved for a run.
- Problem statements and rule snapshots are created when a run is published.
- Historical run data must not change when a user profile or problem changes
  later.

## Scoring Rules

- ACM ranking: solved desc, penalty asc, last accepted time asc, display name.
- ACM public freeze: freeze-after submissions are pending until unfreeze.
- IOI/OI ranking: total score desc, last improvement time asc, display name.
- IOI/OI case results are visible as score evidence but hidden testcase data is
  not exposed.

## AI and Audit Rules

- AI-generated operational text is advisory and auditable.
- Plagiarism and fairness alerts are risk signals, not misconduct decisions.
- Teacher/admin source access must use audited contest source APIs.
- Student weakness candidates from personal postmortem require explicit student
  acceptance before long-term memory or weakness-profile writes.

## Contest Communication Architecture

Phase 13 adds run-scoped announcements and clarifications:

- Announcements belong to one `contest_run`, can be pinned, and can be archived
  or restored by staff.
- Clarifications belong to one `contest_run` and may optionally reference a
  contest problem.
- A clarification is not a chat thread; the first version is one question plus
  one official reply.
- Public clarification replies are visible to eligible run students, but the
  asker identity is hidden.
- Private clarification replies are visible only to staff and the asking
  student.
- Students can ask only while the run is active and they have an active
  participant record.

## Removed Phase 12 Architecture

The cancelled training-report design previously proposed report tables and
gradebook rows. Current architecture removes the active API/service/UI and adds a
cleanup migration for report tables while preserving user-group base data.

If user-group analytics returns later, it should be redesigned around current
requirements instead of restoring the removed implementation.

## Future Architecture Questions

- Phase 13: verified run-scoped clarification/announcement storage, staff reply
  workflow, public versus private audience rules, and announcement pin/archive
  behavior.
- Phase 14: verified full IOI/OI subtask aggregation, C++ `AIOJ_JSON`
  checker metadata/execution, and checker-based partial scoring. Output-only
  tasks are not on the current roadmap.
- Phase 15: restart-safe async jobs, a global audit center, and non-contest
  archive / restore / soft-delete governance for user groups, problems,
  testcase packages, and AI drafts. The implementation remains
  `IMPLEMENTED_UNVERIFIED` pending whole-project acceptance. Old contest-only
  migration tooling is not on the current roadmap.
