import * as React from "react";
import { Link, useParams, useSearch } from "@tanstack/react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Brain, Check, Download, Loader2, RefreshCw, Sparkles, X } from "lucide-react";
import {
  ApiError,
  api,
  type ContestStudentPostmortemOperationJobResponse,
  type ContestStudentPostmortemReportResponse,
  type ContestStudentPostmortemWeaknessCandidateResponse,
  type EntityId
} from "@aioj/api-client";
import { Badge, Button } from "@aioj/ui-react";
import { EmptyState, ErrorPanel, LoadingPanel, PageSection } from "../components/Common";
import { MarkdownView } from "../components/MarkdownView";
import { useI18n } from "../lib/i18n";
import { formatDateTime } from "../lib/format";

type ContestPostmortemSearch = { runId?: EntityId };

type StudentPostmortemStats = {
  contestTitle?: string;
  runTitle?: string;
  mode?: string;
  runKind?: string;
  displayNameSnapshot?: string;
  submissionCount?: number;
  acceptedCount?: number;
  totalScore?: number;
  maxScore?: number;
  languageDistribution?: Record<string, number>;
  statusDistribution?: Record<string, number>;
  errorDistribution?: Record<string, number>;
  weaknessSeeds?: string[];
  problems?: Array<{
    contestProblemId?: EntityId;
    problemId?: EntityId;
    label?: string;
    title?: string;
    difficulty?: string;
    tags?: string[];
    submissionCount?: number;
    bestSubmissionId?: EntityId;
    bestStatus?: string;
    bestScore?: number;
    maxScore?: number;
    caseSummary?: {
      caseResultCount?: number;
      statusDistribution?: Record<string, number>;
      totalScore?: number;
      totalMaxScore?: number;
    };
  }>;
  submissionTimeline?: Array<{
    submissionId?: EntityId;
    contestProblemId?: EntityId;
    problemLabel?: string;
    problemTitle?: string;
    language?: string;
    status?: string;
    score?: number;
    maxScore?: number;
    createdAt?: string;
    judgedAt?: string;
  }>;
};

type PracticeSuggestion = {
  title?: string;
  description?: string;
  tags?: string[];
};

export function ContestStudentPostmortemView() {
  const params = useParams({ strict: false }) as { contestId: EntityId };
  const search = useSearch({ strict: false }) as ContestPostmortemSearch;
  const contestId = params.contestId;
  const runId = search.runId;

  return <ContestStudentPostmortemPanel contestId={contestId} runId={runId} />;
}

export function ContestStudentPostmortemPanel({
  contestId,
  runId,
  embedded = false
}: {
  contestId: EntityId;
  runId?: EntityId | null;
  embedded?: boolean;
}) {
  const { t, locale } = useI18n();
  const queryClient = useQueryClient();
  const [selectedReportId, setSelectedReportId] = React.useState<EntityId>("");
  const [error, setError] = React.useState<string | null>(null);
  const [notice, setNotice] = React.useState<string | null>(null);
  const [pendingJobId, setPendingJobId] = React.useState<EntityId | null>(null);
  const handledFailureNotificationIds = React.useRef(new Set<EntityId>());
  const activeJobSeen = React.useRef(false);
  const activeJobQueryKey = ["student-postmortem-active-job", contestId, runId] as const;

  const openRunQuery = useQuery({
    queryKey: ["student-contest-postmortem-open-run", contestId, runId],
    queryFn: () => api.openContestRun(contestId, runId!),
    enabled: Boolean(runId)
  });

  const reportsQuery = useQuery({
    queryKey: ["student-contest-postmortem-reports", contestId, runId],
    queryFn: () => api.myContestStudentPostmortemReports(contestId, runId!, { page: 1, pageSize: 20 }),
    enabled: Boolean(runId)
  });
  const activeJobQuery = useQuery({
    queryKey: activeJobQueryKey,
    queryFn: () => api.myActiveContestStudentPostmortemOperationJob(contestId, runId!),
    enabled: Boolean(runId),
    refetchInterval: (query) => query.state.data ? 3000 : false
  });
  const activeJobId = activeJobQuery.data?.id ?? null;
  const pendingJobNotificationQuery = useQuery({
    queryKey: ["student-postmortem-notifications", contestId, runId, pendingJobId],
    queryFn: () => api.userNotifications({
      subjectType: "OPERATION_JOB",
      subjectId: pendingJobId!,
      page: 1,
      pageSize: 5
    }),
    enabled: Boolean(runId && pendingJobId),
    refetchInterval: pendingJobId ? 3000 : false
  });
  const unreadFailureNotificationsQuery = useQuery({
    queryKey: ["student-postmortem-notifications", contestId, runId, "unread-failed"],
    queryFn: () => api.userNotifications({
      type: "STUDENT_POSTMORTEM_JOB_FAILED",
      scopeType: "CONTEST_RUN",
      scopeId: runId!,
      unreadOnly: true,
      page: 1,
      pageSize: 20
    }),
    enabled: Boolean(runId)
  });
  const reports = reportsQuery.data?.records ?? [];
  const terminalNotification = pendingJobNotificationQuery.data?.records.find((notification) =>
    notification.type === "STUDENT_POSTMORTEM_JOB_COMPLETED" || notification.type === "STUDENT_POSTMORTEM_JOB_FAILED"
  );
  const restoredActiveJobId = activeJobQuery.data?.id;
  const handledTerminalNotificationIds = React.useRef(new Set<EntityId>());

  React.useEffect(() => {
    if (!restoredActiveJobId) return;
    setPendingJobId((current) => current === restoredActiveJobId ? current : restoredActiveJobId);
  }, [restoredActiveJobId]);

  React.useEffect(() => {
    if (activeJobId) {
      activeJobSeen.current = true;
      setPendingJobId((current) => current === activeJobId ? current : activeJobId);
      return;
    }
    if (!activeJobSeen.current) return;
    activeJobSeen.current = false;
    setPendingJobId(null);
    void queryClient.invalidateQueries({ queryKey: ["student-contest-postmortem-reports", contestId, runId] });
  }, [activeJobId, contestId, queryClient, runId]);

  React.useEffect(() => {
    const terminal = terminalNotification;
    if (!terminal || !pendingJobId || handledTerminalNotificationIds.current.has(terminal.id)) return;
    handledTerminalNotificationIds.current.add(terminal.id);
    setPendingJobId(null);
    void queryClient.invalidateQueries({ queryKey: ["student-postmortem-active-job", contestId, runId] });
    if (terminal.type === "STUDENT_POSTMORTEM_JOB_FAILED") {
      setNotice(null);
      setError(t("contests.studentPostmortemJobFailed"));
    } else if (terminal.type === "STUDENT_POSTMORTEM_JOB_COMPLETED") {
      setNotice(t("contests.studentPostmortemGenerated"));
      void queryClient.invalidateQueries({ queryKey: ["student-contest-postmortem-reports", contestId, runId] });
    }
    void api.markUserNotificationsRead({
      type: terminal.type,
      subjectType: terminal.subjectType,
      subjectId: terminal.subjectId
    }).then(() => queryClient.invalidateQueries({ queryKey: ["student-postmortem-notifications", contestId, runId] }))
      .catch(() => undefined);
  }, [contestId, pendingJobId, queryClient, runId, t, terminalNotification]);

  React.useEffect(() => {
    const records = (unreadFailureNotificationsQuery.data?.records ?? []).filter((notification) =>
      !handledFailureNotificationIds.current.has(notification.id)
    );
    if (!records.length) return;
    records.forEach((notification) => handledFailureNotificationIds.current.add(notification.id));
    setError(t("contests.studentPostmortemJobFailed"));
    void Promise.all(records.map((notification) => api.markUserNotificationsRead({
      type: notification.type,
      subjectType: notification.subjectType,
      subjectId: notification.subjectId
    }))).then(() => queryClient.invalidateQueries({
      queryKey: ["student-postmortem-notifications", contestId, runId]
    })).catch(() => undefined);
  }, [contestId, queryClient, runId, t, unreadFailureNotificationsQuery.data?.records]);

  React.useEffect(() => {
    if (!selectedReportId && reports.length) {
      setSelectedReportId(reports[0].id);
    } else if (selectedReportId && reports.length && !reports.some((report) => report.id === selectedReportId)) {
      setSelectedReportId(reports[0].id);
    }
  }, [reports, selectedReportId]);

  const selectedReport = reports.find((report) => report.id === selectedReportId) ?? reports[0] ?? null;
  const stats = selectedReport ? parseStats(selectedReport.statisticsJson) : null;
  const suggestions = selectedReport ? parseSuggestions(selectedReport.practiceSuggestionsJson) : [];

  const createMutation = useMutation({
    mutationFn: () => api.createMyContestStudentPostmortemOperationJob(contestId, runId!),
    onSuccess: (job) => {
      setPendingJobId(job.id);
      queryClient.setQueryData<ContestStudentPostmortemOperationJobResponse>(activeJobQueryKey, {
        id: job.id,
        status: job.status,
        createdAt: job.createdAt,
        startedAt: job.startedAt,
        updatedAt: job.updatedAt
      });
      setNotice(t("contests.studentPostmortemJobCreated"));
      void queryClient.invalidateQueries({ queryKey: activeJobQueryKey });
    }
  });
  const acceptMutation = useMutation({
    mutationFn: (candidateId: EntityId) => api.acceptContestStudentPostmortemWeakness(contestId, runId!, selectedReport!.id, candidateId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["student-contest-postmortem-reports", contestId, runId] });
    }
  });
  const rejectMutation = useMutation({
    mutationFn: (candidateId: EntityId) => api.rejectContestStudentPostmortemWeakness(contestId, runId!, selectedReport!.id, candidateId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["student-contest-postmortem-reports", contestId, runId] });
    }
  });

  async function createReport() {
    setError(null);
    setNotice(null);
    try {
      await createMutation.mutateAsync();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.userMessage : t("contests.studentPostmortemCreateFailed"));
    }
  }

  if (!runId) {
    if (embedded) {
      return <ErrorPanel title={t("contests.studentPostmortemRunRequired")} />;
    }
    return (
      <div className="mx-auto flex max-w-[1200px] flex-col gap-6 px-4 py-5 md:px-8">
        <Button asChild variant="ghost" className="w-fit">
          <Link to="/contests">
            <ArrowLeft className="size-4" aria-hidden="true" />
            {t("contests.backToList")}
          </Link>
        </Button>
        <ErrorPanel title={t("contests.studentPostmortemRunRequired")} />
      </div>
    );
  }

  const run = openRunQuery.data?.run;
  const contest = openRunQuery.data?.contest;
  const ended = run ? Date.now() >= new Date(run.endAt).getTime() : false;

  const content = (
    <>
      {!embedded ? <div className="flex flex-wrap gap-2">
        <Button asChild variant="ghost" className="w-fit">
          <Link to="/contests/$contestId" params={{ contestId }} search={{ runId }}>
            <ArrowLeft className="size-4" aria-hidden="true" />
            {t("contests.backToContest")}
          </Link>
        </Button>
        <Button asChild variant="ghost" className="w-fit">
          <Link to="/contests/$contestId/scoreboard" params={{ contestId }} search={{ runId }}>
            {t("contests.viewScoreboard")}
          </Link>
        </Button>
      </div> : null}

      {openRunQuery.isLoading ? (
        <LoadingPanel label={t("contests.loading")} />
      ) : openRunQuery.isError || !contest || !run ? (
        <ErrorPanel title={t("contests.loadFailed")} />
      ) : !ended ? (
        <ErrorPanel title={t("contests.studentPostmortemUnavailable")} description={t("contests.studentPostmortemUnavailableCopy")} />
      ) : (
        <>
          {!embedded ? (
            <>
              <PageSection
                eyebrow={t("contests.studentPostmortem")}
                title={run.title || contest.title}
                description={t("contests.studentPostmortemCopy")}
                actions={(
                  <div className="flex flex-wrap gap-2">
                    <Badge tone="blue">{t(`contests.mode.${contest.mode}`)}</Badge>
                    <Badge tone="neutral">{t(`contests.runKind.${run.runKind}`)}</Badge>
                    <Badge tone="green">{t("contests.runStatus.ENDED")}</Badge>
                  </div>
                )}
              />

              <section className="grid gap-3 md:grid-cols-3">
                <InfoCard label={t("contests.startAt")} value={formatDateTime(run.startAt)} />
                <InfoCard label={t("contests.endAt")} value={formatDateTime(run.endAt)} />
                <InfoCard label={t("contests.freezeAt")} value={run.freezeAt ? formatDateTime(run.freezeAt) : "--"} />
              </section>
            </>
          ) : null}

          <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
            <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
              <div>
                <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.studentPostmortemReports")}</h2>
                <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("contests.studentPostmortemReportsCopy")}</p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button variant="outline" disabled={reportsQuery.isFetching} onClick={() => void reportsQuery.refetch()}>
                  <RefreshCw className="size-4" aria-hidden="true" />
                  {t("common.refresh")}
                </Button>
                <Button disabled={createMutation.isPending || Boolean(activeJobId)} onClick={() => void createReport()}>
                  <Brain className="size-4" aria-hidden="true" />
                  {createMutation.isPending || activeJobId ? t("contests.studentPostmortemGenerating") : t("contests.studentPostmortemGenerate")}
                </Button>
              </div>
            </div>
            {error ? <div className="mt-4"><ErrorPanel title={error} /></div> : null}
            {notice ? <div className="mt-4"><OperationJobNotice message={notice} /></div> : null}
            {reports.length > 1 ? (
              <div className="mt-4 flex flex-wrap gap-2">
                {reports.map((report) => (
                  <button
                    key={report.id}
                    type="button"
                    className={`rounded-xl border px-3 py-2 text-sm tabular-nums ${selectedReport?.id === report.id ? "border-blue-500 bg-blue-50 text-blue-900" : "border-[var(--oj-border)] bg-white text-[var(--oj-ink-muted)]"}`}
                    onClick={() => setSelectedReportId(report.id)}
                  >
                    #{shortId(report.id)} · {formatDateTime(report.createdAt)}
                  </button>
                ))}
              </div>
            ) : null}
          </section>

          {activeJobId ? <PostmortemGenerationPlaceholder /> : null}

          {reportsQuery.isLoading ? (
            <LoadingPanel label={t("common.loading")} />
          ) : reportsQuery.isError ? (
            <ErrorPanel title={t("contests.studentPostmortemLoadFailed")} />
          ) : !selectedReport ? (
            <EmptyState title={t("contests.studentPostmortemEmpty")} description={t("contests.studentPostmortemEmptyCopy")} />
          ) : (
            <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px]">
              <main className="min-w-0 space-y-5">
                {stats ? <StatsOverview stats={stats} /> : <ErrorPanel title={t("contests.studentPostmortemStatsParseFailed")} />}
                {stats ? <ProblemBreakdown stats={stats} /> : null}

                <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
                  <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.studentPostmortemAiMarkdown")}</h2>
                      <p className="mt-1 text-sm text-[var(--oj-ink-muted)]">
                        {stats?.mode === "ACM" ? t("contests.studentPostmortemAiMarkdownCopyAcm") : t("contests.studentPostmortemAiMarkdownCopy")}
                      </p>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <PostmortemAiStatusChip status={selectedReport.aiStatus} />
                      {selectedReport.aiMarkdown ? (
                        <Button size="sm" variant="outline" onClick={() => downloadStudentMarkdown(selectedReport, stats, {
                          kind: t("contests.studentPostmortemExportKind"),
                          reportId: t("contests.postmortemExportReportId"),
                          generatedAt: t("contests.postmortemExportGeneratedAt")
                        })}>
                          <Download className="size-4" aria-hidden="true" />
                          {t("contests.postmortemDownloadMarkdown")}
                        </Button>
                      ) : null}
                    </div>
                  </div>
                  {selectedReport.aiStatus === "FAILED" ? (
                    <ErrorPanel title={t("contests.studentPostmortemJobFailed")} />
                  ) : selectedReport.aiMarkdown ? (
                    <MarkdownView content={selectedReport.aiMarkdown} />
                  ) : (
                    <p className="text-sm leading-6 text-[var(--oj-ink-muted)]">{t("contests.postmortemNoMarkdown")}</p>
                  )}
                </section>
              </main>

              <aside className="space-y-5">
                <WeaknessCandidatePanel
                  candidates={selectedReport.weaknessCandidates ?? []}
                  accepting={acceptMutation.isPending}
                  rejecting={rejectMutation.isPending}
                  onAccept={(candidate) => void acceptMutation.mutateAsync(candidate.id).catch(() => setError(t("contests.studentPostmortemWeaknessAcceptFailed")))}
                  onReject={(candidate) => void rejectMutation.mutateAsync(candidate.id).catch(() => setError(t("contests.studentPostmortemWeaknessRejectFailed")))}
                />
                <PracticeSuggestionPanel suggestions={suggestions} />
              </aside>
            </div>
          )}
        </>
      )}
    </>
  );

  if (embedded) {
    return <div className="space-y-5">{content}</div>;
  }

  return (
    <div className="mx-auto flex max-w-[1280px] flex-col gap-6 px-4 py-5 md:px-8">
      {content}
    </div>
  );
}

function StatsOverview({ stats }: { stats: StudentPostmortemStats }) {
  const { t } = useI18n();
  const isAcm = stats.mode === "ACM";
  const progress = problemProgress(stats.problems ?? []);
  const segments = [
    { key: "solved", label: t("contests.scoreboardSolvedStatus"), count: progress.solved, color: "bg-emerald-500" },
    { key: "unsolved", label: t("contests.studentPostmortemUnsolved"), count: progress.unsolved, color: "bg-amber-500" },
    { key: "unattempted", label: t("contests.studentPostmortemUnattempted"), count: progress.unattempted, color: "bg-slate-300" }
  ];
  return (
    <section className="space-y-3">
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <InfoCard label={t("contests.postmortemSubmissions")} value={String(stats.submissionCount ?? 0)} />
        <InfoCard label={t("contests.studentPostmortemSolvedProblems")} value={`${progress.solved}/${progress.total}`} />
        <InfoCard
          label={isAcm ? t("contests.studentPostmortemUnresolvedProblems") : t("contests.studentPostmortemScore")}
          value={isAcm ? String(progress.unsolved) : formatScoreSummary(stats.totalScore, stats.maxScore)}
        />
        <InfoCard label={t("contests.postmortemWeaknessCandidates")} value={String(stats.weaknessSeeds?.length ?? 0)} />
      </div>
      {progress.total > 0 ? (
        <div className="rounded-2xl border border-[var(--oj-border-soft)] bg-white p-4">
          <div className="text-xs font-medium text-[var(--oj-ink-muted)]">{t("contests.studentPostmortemProblemProgress")}</div>
          <div className="mt-3 flex h-2.5 overflow-hidden rounded-full bg-slate-100" aria-hidden="true">
            {segments.filter((segment) => segment.count > 0).map((segment) => (
              <span key={segment.key} className={`h-full ${segment.color}`} style={{ width: `${(segment.count / progress.total) * 100}%` }} />
            ))}
          </div>
          <div className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-sm text-[var(--oj-ink-muted)]">
            {segments.map((segment) => (
              <span key={segment.key} className="inline-flex items-center gap-2">
                <span className={`size-2.5 rounded-full ${segment.color}`} aria-hidden="true" />
                {segment.label} <span className="tabular-nums">{segment.count}</span>
              </span>
            ))}
          </div>
        </div>
      ) : null}
    </section>
  );
}

function ProblemBreakdown({ stats }: { stats: StudentPostmortemStats }) {
  const { t } = useI18n();
  const problems = stats.problems ?? [];
  const timeline = stats.submissionTimeline ?? [];
  const isAcm = stats.mode === "ACM";
  const statusLabel = (status?: string) => status ? t(`submissionStatus.${status}`) : "--";
  return (
    <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
      <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.studentPostmortemProblemReview")}</h2>
      <div className="mt-4 grid gap-3 md:grid-cols-2">
        {problems.map((problem, index) => {
          const attempts = problem.submissionCount ?? 0;
          const solved = problem.bestStatus === "ACCEPTED";
          const trail = problem.contestProblemId == null ? [] : statusTrail(timeline
            .filter((submission) => submission.contestProblemId != null && String(submission.contestProblemId) === String(problem.contestProblemId))
            .map((submission) => statusLabel(submission.status)));
          return (
            <article key={problem.contestProblemId ?? problem.problemId ?? index} className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <h3 className="font-semibold text-[var(--oj-ink)]">{problem.label} · {problem.title}</h3>
                  <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">{problem.difficulty || "--"}</p>
                </div>
                <Badge className="shrink-0" tone={solved ? "green" : attempts > 0 ? "amber" : "neutral"}>
                  {solved ? t("contests.scoreboardSolvedStatus") : attempts > 0 ? t("contests.studentPostmortemUnsolved") : t("contests.studentPostmortemUnattempted")}
                </Badge>
              </div>
              <div className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-sm text-[var(--oj-ink-muted)]">
                <span>{t("contests.postmortemAttempts")}: <span className="tabular-nums">{attempts}</span></span>
                {attempts > 0 ? <span>{t("contests.studentPostmortemBestStatus")}: {statusLabel(problem.bestStatus)}</span> : null}
                {!isAcm ? <span>{t("contests.score")}: <span className="tabular-nums">{formatScoreSummary(problem.bestScore, problem.maxScore)}</span></span> : null}
              </div>
              {problem.tags?.length ? (
                <div className="mt-3 flex flex-wrap gap-1.5">
                  {problem.tags.map((tag, tagIndex) => <Badge key={`${tag}-${tagIndex}`} tone="neutral">{tag}</Badge>)}
                </div>
              ) : null}
              {trail.length ? (
                <p className="mt-3 border-t border-[var(--oj-border-soft)] pt-3 text-sm leading-6 text-[var(--oj-ink)]">
                  <span className="mr-2 text-xs font-medium text-[var(--oj-ink-muted)]">{t("contests.studentPostmortemTimeline")}</span>
                  {trail.map((step) => step.count > 1 ? `${step.label} ×${step.count}` : step.label).join(" → ")}
                </p>
              ) : null}
            </article>
          );
        })}
      </div>

      {timeline.length ? (
        <details className="mt-5 rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
          <summary className="cursor-pointer text-sm font-semibold text-[var(--oj-ink)]">
            {t("contests.studentPostmortemTimeline")} <span className="tabular-nums">({timeline.length})</span>
          </summary>
          <div className="mt-3 space-y-2">
            {timeline.map((submission, index) => (
              <div key={submission.submissionId ?? index} className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-white px-3 py-2 text-sm">
                <span className="font-medium text-[var(--oj-ink)]">{submission.problemLabel} · {submission.problemTitle}</span>
                <span className="text-xs tabular-nums text-[var(--oj-ink-muted)]">
                  {submission.language} · {statusLabel(submission.status)}
                  {!isAcm ? ` · ${formatScoreSummary(submission.score, submission.maxScore)}` : ""}
                </span>
              </div>
            ))}
          </div>
        </details>
      ) : null}
    </section>
  );
}

function WeaknessCandidatePanel({
  candidates,
  accepting,
  rejecting,
  onAccept,
  onReject
}: {
  candidates: ContestStudentPostmortemWeaknessCandidateResponse[];
  accepting: boolean;
  rejecting: boolean;
  onAccept: (candidate: ContestStudentPostmortemWeaknessCandidateResponse) => void;
  onReject: (candidate: ContestStudentPostmortemWeaknessCandidateResponse) => void;
}) {
  const { t } = useI18n();
  return (
    <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
      <div className="flex items-center gap-2">
        <Sparkles className="size-4 text-[var(--oj-primary)]" aria-hidden="true" />
        <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.studentPostmortemWeaknessCandidates")}</h2>
      </div>
      <p className="mt-2 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("contests.studentPostmortemWeaknessCopy")}</p>
      <div className="mt-4 space-y-3">
        {candidates.length ? candidates.map((candidate) => (
          <article key={candidate.id} className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{candidate.knowledgeNode}</h3>
                <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{candidate.symptom}</p>
              </div>
              <Badge tone={candidate.status === "ACCEPTED" ? "green" : candidate.status === "REJECTED" ? "red" : "amber"}>
                {t(`contests.studentPostmortemCandidateStatus.${candidate.status}`)}
              </Badge>
            </div>
            {candidate.evidence?.length ? (
              <ul className="mt-3 space-y-1 text-xs leading-5 text-[var(--oj-ink-muted)]">
                {candidate.evidence.map((item) => <li key={item}>· {item}</li>)}
              </ul>
            ) : null}
            {candidate.status === "PENDING" ? (
              <div className="mt-4 flex flex-wrap gap-2">
                <Button size="sm" disabled={accepting || rejecting} onClick={() => onAccept(candidate)}>
                  <Check className="size-4" aria-hidden="true" />
                  {t("contests.studentPostmortemAcceptWeakness")}
                </Button>
                <Button size="sm" variant="outline" disabled={accepting || rejecting} onClick={() => onReject(candidate)}>
                  <X className="size-4" aria-hidden="true" />
                  {t("contests.studentPostmortemRejectWeakness")}
                </Button>
              </div>
            ) : null}
          </article>
        )) : (
          <EmptyState title={t("contests.studentPostmortemNoWeakness")} description={t("contests.studentPostmortemNoWeaknessCopy")} />
        )}
      </div>
    </section>
  );
}

function PracticeSuggestionPanel({ suggestions }: { suggestions: PracticeSuggestion[] }) {
  const { t } = useI18n();
  return (
    <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
      <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.studentPostmortemPracticeSuggestions")}</h2>
      <div className="mt-4 space-y-3">
        {suggestions.length ? suggestions.map((suggestion, index) => (
          <article key={`${suggestion.title}-${index}`} className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
            <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{suggestion.title || t("contests.studentPostmortemPracticeSuggestion")}</h3>
            {suggestion.description ? <p className="mt-2 text-sm leading-6 text-[var(--oj-ink-muted)]">{suggestion.description}</p> : null}
            {suggestion.tags?.length ? (
              <div className="mt-3 flex flex-wrap gap-2">
                {suggestion.tags.map((tag) => <Badge key={tag} tone="neutral">{tag}</Badge>)}
              </div>
            ) : null}
          </article>
        )) : (
          <EmptyState title={t("contests.studentPostmortemNoPractice")} description={t("contests.studentPostmortemNoPracticeCopy")} />
        )}
      </div>
    </section>
  );
}

function InfoCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-2xl border border-[var(--oj-border-soft)] bg-white p-4">
      <div className="text-xs font-medium text-[var(--oj-ink-muted)]">{label}</div>
      <div className="mt-2 text-lg font-semibold tabular-nums text-[var(--oj-ink)]">{value}</div>
    </div>
  );
}

function OperationJobNotice({ message }: { message: string }) {
  return (
    <div className="rounded-xl border border-blue-200 bg-blue-50 px-4 py-3 text-sm leading-6 text-blue-800">
      {message}
    </div>
  );
}

function PostmortemGenerationPlaceholder() {
  const { t } = useI18n();
  return (
    <section className="rounded-2xl border border-dashed border-blue-300 bg-blue-50/70 p-5" aria-live="polite">
      <div className="flex items-start gap-3">
        <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-white text-[var(--oj-primary)] shadow-sm">
          <Loader2 className="size-5 animate-spin" aria-hidden="true" />
        </span>
        <div>
          <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.studentPostmortemGeneratingNotice")}</h2>
          <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("contests.studentPostmortemGeneratingCopy")}</p>
        </div>
      </div>
    </section>
  );
}

function PostmortemAiStatusChip({ status }: { status: string }) {
  const { t } = useI18n();
  const tone = status === "COMPLETED" ? "green" : status === "FAILED" ? "red" : status === "RUNNING" ? "blue" : "neutral";
  return <Badge className="w-fit" tone={tone}>{t(`contests.postmortemAiStatus.${status}`)}</Badge>;
}

function problemProgress(problems: NonNullable<StudentPostmortemStats["problems"]>) {
  const solved = problems.filter((problem) => problem.bestStatus === "ACCEPTED").length;
  const unsolved = problems.filter((problem) => (problem.submissionCount ?? 0) > 0 && problem.bestStatus !== "ACCEPTED").length;
  return { total: problems.length, solved, unsolved, unattempted: problems.length - solved - unsolved };
}

/** Collapses repeated statuses, so WA, WA, WA, AC reads as "WA ×3 → AC". */
function statusTrail(labels: string[]) {
  const steps: Array<{ label: string; count: number }> = [];
  for (const label of labels) {
    const last = steps[steps.length - 1];
    if (last && last.label === label) {
      last.count += 1;
    } else {
      steps.push({ label, count: 1 });
    }
  }
  return steps;
}

function parseStats(value: string): StudentPostmortemStats | null {
  try {
    return JSON.parse(value) as StudentPostmortemStats;
  } catch {
    return null;
  }
}

function parseSuggestions(value?: string | null): PracticeSuggestion[] {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed as PracticeSuggestion[] : [];
  } catch {
    return [];
  }
}

function downloadStudentMarkdown(report: ContestStudentPostmortemReportResponse, stats: StudentPostmortemStats | null,
                                 labels: { kind: string; reportId: string; generatedAt: string }) {
  const title = stats?.contestTitle || "contest";
  const runTitle = stats?.runTitle || "run";
  const content = `# ${title} - ${runTitle} ${labels.kind}\n\n${labels.reportId}${report.id}\n${labels.generatedAt}${formatDateTime(report.createdAt)}\n\n${report.aiMarkdown || ""}`;
  const blob = new Blob([content], { type: "text/markdown;charset=utf-8" });
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `contest-${report.contestId}-run-${report.contestRunId}-student-postmortem-${shortId(report.id)}.md`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}

function formatScoreSummary(score?: number | null, maxScore?: number | null) {
  if (score == null && maxScore == null) return "--";
  return `${formatScore(score)}/${formatScore(maxScore)}`;
}

function formatScore(value?: number | null) {
  if (value == null) return "0";
  return Number.isInteger(value) ? String(value) : value.toFixed(3).replace(/0+$/, "").replace(/\.$/, "");
}

function shortId(id: EntityId) {
  return id.length > 8 ? id.slice(-8) : id;
}
