# AIOJ roadmap

Last reviewed: 2026-08-11

The old Phase 0-5 roadmap was superseded by the React migration and contest
operations rollout. This document now points to the current phase plan.

## Current Baseline

- React student/admin apps are the active frontend.
- Contest and user-group functionality has progressed through Phase 1-11 and was marked verified by user acceptance.
- Phase 12 training reports and gradebook were cancelled and removed.
- Team contest and study subgroup features are no longer on the current mainline roadmap; user groups are the only maintained organization model.

For detailed evidence and phase status, use:

- [CONTEST_CLASS_GROUP_PROGRESS_TRACKER.md](CONTEST_CLASS_GROUP_PROGRESS_TRACKER.md)
- [CONTEST_CLASS_GROUP_DEVELOPMENT_PLAN.md](CONTEST_CLASS_GROUP_DEVELOPMENT_PLAN.md)
- [CONTEST_CLASS_GROUP_RESEARCH_AND_ARCHITECTURE.md](CONTEST_CLASS_GROUP_RESEARCH_AND_ARCHITECTURE.md)
- [PROJECT_HISTORY_AND_FUTURE.md](PROJECT_HISTORY_AND_FUTURE.md)

## Phase 12+

| Phase | Theme | Status |
|---|---|---|
| Phase 10 | Student personal postmortem and personalized learning loop | Verified |
| Phase 11 | Plagiarism relationship graph and fairness alerts | Verified |
| Phase 12 | Training reports and run gradebook | Cancelled, removed |
| Team contest mainline | Teams, captains, substitutes, withdrawals, team scoreboards | Deferred, not planned |
| Phase 13 | Advanced contest operations: clarifications, announcements, staff reply workflow | Verified |
| Phase 14 | Advanced judging: full IOI subtask aggregation, custom checker, checker-based partial scoring | Verified |
| Phase 15 | Scale and governance: async jobs, global audit center, non-contest archive / restore / soft-delete governance | Implemented, unverified |

Removed from current roadmap: output-only tasks and old contest-only data
migration tooling.

## Standing Product Priorities

1. Keep contest data run-scoped and historically stable.
2. Keep AI outputs auditable and advisory.
3. Keep student learning flows separate from teacher/admin audit workflows.
4. Keep UI dense surfaces readable instead of squeezed into narrow panels.
5. Keep verification evidence in the phase tracker after each phase lands.
6. Complete single-node production acceptance before claiming 50-user capacity.
7. Keep immutable GHCR release, backup/restore, and Sandbox risk controls intact.
