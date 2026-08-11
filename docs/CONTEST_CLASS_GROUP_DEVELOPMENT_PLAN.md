# Contest and User Group Development Plan

Last reviewed: 2026-08-04

## Product Direction

AI-OJ keeps a single organization model: user groups. User groups support
membership management, contest registration scopes, participant import, and
teacher/admin permissions.

Removed or deferred directions:

- Study subgroup features: removed from UI/API and no longer maintained.
- Phase 12 training-report / gradebook implementation: cancelled and removed.
- Team contests, team captains, substitutes, withdrawals, and team scoreboards:
  `DEFERRED_NOT_PLANNED`.

## Stable Contest Model

- `contest`: reusable blueprint with title, description, mode, scoring rules,
  and problem arrangement.
- `contest_run`: concrete opening with start/end/freeze time, registration
  access, allowed user groups, participants, submissions, scoreboard, audit,
  plagiarism, resolver, and postmortem scope.
- Run problem and participant snapshots preserve historical truth.
- New contest operations must pass a concrete `runId`; do not invent "all runs"
  aggregation without a new product design.

## Verified Capabilities

| Area | Capability |
|---|---|
| User groups | CRUD, member add/remove by account or ID, archive/restore/soft-delete governance |
| Contest setup | Blueprint creation, problem arrangement, run creation, allowed user groups |
| Registration | Public/user-group/private access, approval flow, cancellation before start |
| Submissions | Explicit contest/run/problem/participant context |
| Scoreboard | ACM and IOI/OI modes, freeze/unfreeze, snapshots, minute timeline |
| Source audit | Staff source viewing uses audited contest APIs |
| Export | Scoreboard and submission metadata CSV/XLSX, no source export |
| Plagiarism | Similarity jobs, pair evidence, AI risk explanation, graph, fairness alerts |
| Resolver | ACM reveal replay for ended runs |
| Postmortem | Teacher run-level AI postmortem and student personal postmortem |
| Contest communication | Run-level announcements and clarification workflow |

## Cancelled Phase 12 Handling

The first Phase 12 implementation created training-report DTOs, service, UI, and
tables. The feature has been cancelled. Current cleanup policy:

- Keep user-group base tables because they are core product data.
- Do not expose study subgroup APIs or UI.
- Do not keep training-report frontend entry, API, service, or DTOs.
- Keep historical migration compatibility, then clean report tables with a later
  migration.
- Future user-group analytics must be planned from scratch and must not reuse the
  removed training-report implementation by default.

## Next Roadmap

### Phase 13: Contest Clarifications and Announcements

Status: `VERIFIED`

- Run-level announcements are implemented with create/edit/pin/archive/restore.
- Run-level clarifications are implemented as one student question plus one
  official staff reply.
- Staff can answer privately to the asking participant or publicly to all
  eligible run participants.
- Public clarification views hide the questioner's identity.
- Student questions are allowed only during the active run window for active
  participants.
- The detailed manual-browser checklist below is retained as historical
  acceptance evidence; it is not a current blocker for the verified phase.

### Phase 14: Advanced Judging and IOI/OI

Status: `VERIFIED`

- Full IOI/OI subtask aggregation through `SUBTASK_MIN_CASE_MAX_OVER_SUBMISSIONS`.
- C++ custom checker with the `AIOJ_JSON` protocol.
- Checker-based single-case partial scoring through `score/maxScore`; no new `PARTIAL_ACCEPTED` status.
- Better testcase manifest authoring and validation for checker and subtask metadata.
- Output-only tasks remain outside the current roadmap.

### Phase 15: Scale, Audit, and Governance

Status: `IMPLEMENTED_UNVERIFIED`

- Restart-safe async jobs for export, plagiarism, and postmortem.
- Global audit center.
- Non-contest archive/restore/soft-delete governance for user groups, problems,
  testcase packages, and AI drafts.
- Soft-deleted objects are hidden from ordinary maintenance APIs; frontend/API
  recovery is intentionally not provided.

## Implementation Rules

- Do not reintroduce study subgroup features without a new explicit user request.
- Do not add AI summaries to deterministic reports unless the scope and cost are
  explicitly approved.
- Do not expose hidden testcase data, full stdout/stderr, or other participants'
  private details in student-facing reports.
- Keep all new UI text in `packages/i18n/src/messages.ts`.
- Keep 16+ digit IDs as frontend strings.
