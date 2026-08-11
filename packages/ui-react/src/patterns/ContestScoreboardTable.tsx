import { cn } from "../lib/cn";

export type ContestScoreboardTableProblem = {
  contestProblemId: string;
  label: string;
  displayTitle?: string | null;
  score?: number;
};

export type ContestScoreboardTableCell = {
  contestProblemId?: string | null;
  status: "UNSOLVED" | "ATTEMPTED" | "PENDING" | "SOLVED" | "HIDDEN";
  attempts: number;
  wrongAttempts: number;
  pendingAttempts: number;
  acceptedAtMillis?: number | null;
  penaltyMinutes: number;
  score?: number | null;
  maxScore?: number | null;
  bestSubmissionId?: string | null;
  lastScoreImprovedAtMillis?: number | null;
};

export type ContestScoreboardTableRow = {
  rank: number;
  participantId: string;
  accountSnapshot: string;
  displayNameSnapshot: string;
  solvedCount: number;
  penaltyMinutes: number;
  totalScore?: number | null;
  lastScoreImprovedAtMillis?: number | null;
  cells: ContestScoreboardTableCell[];
};

export type ContestScoreboardTableLabels = {
  rank: string;
  participant: string;
  solved: string;
  penalty: string;
  empty: string;
  pending: string;
  solvedStatus: string;
  attempted: string;
  unsolved: string;
  totalScore?: string;
  score?: string;
};

export function ContestScoreboardTable({
  mode = "ACM",
  problems,
  rows,
  labels
}: {
  mode?: "ACM" | "IOI";
  problems: ContestScoreboardTableProblem[];
  rows: ContestScoreboardTableRow[];
  labels: ContestScoreboardTableLabels;
}) {
  if (!rows.length) {
    return (
      <div className="rounded-xl border border-[var(--oj-border-soft)] bg-white p-6 text-sm text-[var(--oj-ink-muted)]">
        {labels.empty}
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-xl border border-[var(--oj-border)] bg-white">
      <table className="w-full min-w-[980px] table-fixed text-sm">
        <colgroup>
          <col className="w-16" />
          <col className="w-56" />
          <col className="w-20" />
          <col className="w-24" />
          {problems.map((problem) => <col key={problem.contestProblemId} className="w-28" />)}
        </colgroup>
        <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
          <tr>
            <th className="sticky left-0 z-10 bg-[var(--oj-surface-muted)] px-3 py-3 text-center">{labels.rank}</th>
            <th className="sticky left-16 z-10 bg-[var(--oj-surface-muted)] px-3 py-3 text-left">{labels.participant}</th>
            <th className="px-3 py-3 text-center">{mode === "IOI" ? labels.solved : labels.solved}</th>
            <th className="px-3 py-3 text-center">{mode === "IOI" ? (labels.totalScore ?? labels.score ?? labels.penalty) : labels.penalty}</th>
            {problems.map((problem) => (
              <th key={problem.contestProblemId} className="px-3 py-3 text-center">
                <div className="truncate font-semibold text-[var(--oj-ink)]">{problem.label}</div>
                {typeof problem.score === "number" ? (
                  <div className="mt-1 text-[11px] font-normal tabular-nums text-[var(--oj-ink-muted)]">{problem.score}</div>
                ) : null}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-[var(--oj-border-soft)]">
          {rows.map((row) => {
            const cellsByProblem = new Map(row.cells.map((cell) => [cell.contestProblemId, cell]));
            return (
              <tr key={row.participantId} className="align-middle">
                <td className="sticky left-0 z-10 bg-white px-3 py-3 text-center font-semibold tabular-nums text-[var(--oj-ink)]">
                  {row.rank}
                </td>
                <td className="sticky left-16 z-10 bg-white px-3 py-3">
                  <div className="min-w-0">
                    <div className="truncate font-semibold text-[var(--oj-ink)]">{row.displayNameSnapshot}</div>
                    <div className="mt-1 truncate text-xs tabular-nums text-[var(--oj-ink-muted)]">{row.accountSnapshot}</div>
                  </div>
                </td>
                <td className="px-3 py-3 text-center font-semibold tabular-nums text-[var(--oj-ink)]">{row.solvedCount}</td>
                <td className="px-3 py-3 text-center tabular-nums text-[var(--oj-ink-muted)]">
                  {mode === "IOI" ? formatScore(row.totalScore) : row.penaltyMinutes}
                </td>
                {problems.map((problem) => (
                  <td key={problem.contestProblemId} className="px-2 py-3 text-center">
                    <ScoreCell mode={mode} cell={cellsByProblem.get(problem.contestProblemId)} labels={labels} />
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function ScoreCell({
  mode,
  cell,
  labels
}: {
  mode: "ACM" | "IOI";
  cell?: ContestScoreboardTableCell;
  labels: ContestScoreboardTableLabels;
}) {
  if (!cell || cell.status === "UNSOLVED") {
    return <span className="text-[var(--oj-ink-muted)]" aria-label={labels.unsolved}>-</span>;
  }
  if (mode === "IOI") {
    const content = cell.status === "PENDING"
      ? `${labels.pending} ${cell.pendingAttempts}`
      : `${formatScore(cell.score)}/${formatScore(cell.maxScore)}`;
    const ariaLabel = cell.status === "SOLVED"
      ? labels.solvedStatus
      : cell.status === "PENDING"
        ? labels.pending
        : labels.attempted;
    return (
      <span
        className={cn(
          "inline-flex min-h-7 min-w-20 items-center justify-center rounded-lg border px-2 text-xs font-semibold tabular-nums",
          cell.status === "SOLVED" && "border-emerald-200 bg-emerald-50 text-emerald-700",
          cell.status === "PENDING" && "border-sky-200 bg-sky-50 text-sky-700",
          cell.status === "ATTEMPTED" && "border-amber-200 bg-amber-50 text-amber-700",
          cell.status === "HIDDEN" && "border-slate-200 bg-slate-50 text-slate-500"
        )}
        aria-label={ariaLabel}
      >
        {content}
      </span>
    );
  }
  const acceptedMinute = cell.acceptedAtMillis == null ? null : Math.floor(cell.acceptedAtMillis / 60_000);
  const content = cell.status === "SOLVED"
    ? `${acceptedMinute ?? 0}m${cell.attempts > 1 ? ` / ${cell.attempts}` : ""}`
    : cell.status === "PENDING"
      ? `${labels.pending} ${cell.pendingAttempts}`
      : `-${cell.wrongAttempts || cell.attempts}`;
  const ariaLabel = cell.status === "SOLVED"
    ? labels.solvedStatus
    : cell.status === "PENDING"
      ? labels.pending
      : labels.attempted;

  return (
    <span
      className={cn(
        "inline-flex min-h-7 min-w-16 items-center justify-center rounded-lg border px-2 text-xs font-semibold tabular-nums",
        cell.status === "SOLVED" && "border-emerald-200 bg-emerald-50 text-emerald-700",
        cell.status === "PENDING" && "border-sky-200 bg-sky-50 text-sky-700",
        cell.status === "ATTEMPTED" && "border-rose-200 bg-rose-50 text-rose-700",
        cell.status === "HIDDEN" && "border-slate-200 bg-slate-50 text-slate-500"
      )}
      aria-label={ariaLabel}
    >
      {content}
    </span>
  );
}

function formatScore(value?: number | null) {
  if (value == null) {
    return "0";
  }
  return Number.isInteger(value) ? String(value) : value.toFixed(3).replace(/0+$/, "").replace(/\.$/, "");
}
