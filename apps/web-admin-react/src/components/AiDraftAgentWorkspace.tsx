import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Activity, Bot, Clock3, Cpu, MessageSquareText, RefreshCw, Wrench } from "lucide-react";
import {
  activeQueryRefetchInterval,
  api,
  type EntityId,
  type ProblemDraftAgentRunResponse,
  type ProblemDraftGenerationJobResponse,
  type ProblemDraftResourceMetrics,
  type ProblemDraftReviewRisk,
  type ProblemDraftToolCallResponse,
  type ProblemDraftWorkspaceResponse,
  type ProblemDraftWorkspaceTurnIntent
} from "@aioj/api-client";
import { Badge, Button, cn } from "@aioj/ui-react";
import { EmptyState, ErrorPanel, Field, LoadingPanel, SidePanel, selectClass, textareaClass } from "./Common";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { formatBytes, formatDateTime, shortId } from "../lib/format";
import { getStoredLocale } from "@aioj/i18n/vanilla";
import { readableCaughtError, readableStoredError } from "../lib/readableError";

type TimelineItem =
  | { kind: "run"; id: string; createdAt: string; value: ProblemDraftAgentRunResponse }
  | { kind: "tool"; id: string; createdAt: string; value: ProblemDraftToolCallResponse };

const TURN_INTENTS: ProblemDraftWorkspaceTurnIntent[] = ["REFINE", "REPAIR", "REVALIDATE", "GENERATE_TESTS"];
const MESSAGES_PAGE_SIZE = 50;

export function AiDraftWorkspacePanel({
  open,
  onOpenChange,
  workspaceId,
  fallbackJob,
  onOpenDraft
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  workspaceId?: EntityId | null;
  fallbackJob?: ProblemDraftGenerationJobResponse | null;
  onOpenDraft?: (draftId: EntityId) => void;
}) {
  const { t } = useI18n();
  return (
    <SidePanel
      open={open}
      onOpenChange={onOpenChange}
      presentation="workspace"
      workspaceSize="xl"
      title={t("draftWorkspace.title")}
      description={workspaceId ? `#${shortId(workspaceId)}` : fallbackJob ? `#${shortId(fallbackJob.id)}` : undefined}
    >
      <AiDraftWorkspaceContent
        workspaceId={workspaceId}
        fallbackJob={fallbackJob}
        onOpenDraft={onOpenDraft}
      />
    </SidePanel>
  );
}

export function AiDraftWorkspaceContent({
  workspaceId,
  fallbackJob,
  currentDraftId,
  onOpenDraft
}: {
  workspaceId?: EntityId | null;
  fallbackJob?: ProblemDraftGenerationJobResponse | null;
  currentDraftId?: EntityId | null;
  onOpenDraft?: (draftId: EntityId) => void;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [runs, setRuns] = React.useState<ProblemDraftAgentRunResponse[]>([]);
  const [toolCalls, setToolCalls] = React.useState<ProblemDraftToolCallResponse[]>([]);
  const [message, setMessage] = React.useState("");
  const [intent, setIntent] = React.useState<ProblemDraftWorkspaceTurnIntent>("REFINE");

  React.useEffect(() => {
    setRuns([]);
    setToolCalls([]);
    setMessage("");
    setIntent("REFINE");
  }, [workspaceId]);

  const workspaceQuery = useQuery({
    queryKey: ["admin-problem-draft-workspace", workspaceId],
    queryFn: () => api.problemDraftWorkspace(String(workspaceId)),
    enabled: Boolean(workspaceId),
    refetchInterval: (query) => activeQueryRefetchInterval(query, (data) => isWorkspaceActive(data), {
      fastMs: 1800,
      slowMs: 4000,
      hiddenMs: 10000
    })
  });

  const workspace = workspaceQuery.data;
  const workspaceActive = isWorkspaceActive(workspace);
  const afterRunId = incrementalCursor(runs);
  const afterToolCallId = incrementalCursor(toolCalls);

  const messagesQuery = useQuery({
    queryKey: ["admin-problem-draft-workspace-messages", workspaceId],
    queryFn: () => api.problemDraftWorkspaceMessages(String(workspaceId), { page: 1, pageSize: MESSAGES_PAGE_SIZE }),
    enabled: Boolean(workspaceId),
    refetchInterval: (query) => activeQueryRefetchInterval(query, () => workspaceActive, {
      fastMs: 2200,
      slowMs: 5000,
      hiddenMs: 10000
    })
  });

  const runsQuery = useQuery({
    queryKey: ["admin-problem-draft-workspace-runs", workspaceId, afterRunId ?? "start"],
    queryFn: () => api.problemDraftWorkspaceAgentRuns(String(workspaceId), afterRunId),
    enabled: Boolean(workspaceId),
    refetchInterval: (query) => activeQueryRefetchInterval(query, () => workspaceActive, {
      fastMs: 2000,
      slowMs: 5000,
      hiddenMs: 10000
    })
  });

  const toolCallsQuery = useQuery({
    queryKey: ["admin-problem-draft-workspace-tool-calls", workspaceId, afterToolCallId ?? "start"],
    queryFn: () => api.problemDraftWorkspaceToolCalls(String(workspaceId), afterToolCallId),
    enabled: Boolean(workspaceId),
    refetchInterval: (query) => activeQueryRefetchInterval(query, () => workspaceActive, {
      fastMs: 1800,
      slowMs: 4500,
      hiddenMs: 10000
    })
  });

  React.useEffect(() => {
    if (!runsQuery.data?.length) return;
    setRuns((previous) => mergeById(previous, runsQuery.data));
  }, [runsQuery.data]);

  React.useEffect(() => {
    if (!toolCallsQuery.data?.length) return;
    setToolCalls((previous) => mergeById(previous, toolCallsQuery.data));
  }, [toolCallsQuery.data]);

  React.useEffect(() => {
    if (!workspace?.updatedAt) return;
    void Promise.all([runsQuery.refetch(), toolCallsQuery.refetch(), messagesQuery.refetch()]);
  }, [workspace?.updatedAt]);

  const turnMutation = useMutation({
    mutationFn: (payload: { message: string; intent: ProblemDraftWorkspaceTurnIntent }) =>
      api.createProblemDraftWorkspaceTurn(String(workspaceId), {
        clientTurnId: createClientTurnId(),
        expectedCurrentDraftId: workspace?.currentDraftId ?? currentDraftId ?? null,
        message: payload.message,
        intent: payload.intent
      }),
    onSuccess: async () => {
      setMessage("");
      toast.success(t("draftWorkspace.turnAccepted"));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["admin-problem-draft-workspace", workspaceId] }),
        queryClient.invalidateQueries({ queryKey: ["admin-problem-draft-workspace-messages", workspaceId] }),
        queryClient.invalidateQueries({ queryKey: ["admin-ai-draft-generation-jobs"] })
      ]);
    },
    onError: (caught) => toast.error(readableCaughtError(caught, locale, t("draftWorkspace.turnFailed")))
  });

  if (!workspaceId) {
    return fallbackJob ? <LegacyJobWorkspace job={fallbackJob} onOpenDraft={onOpenDraft} /> : (
      <EmptyState title={t("draftWorkspace.unavailableTitle")} description={t("draftWorkspace.unavailableDescription")} />
    );
  }

  if (workspaceQuery.isLoading) return <LoadingPanel label={t("draftWorkspace.loading")} />;
  if (workspaceQuery.isError || !workspace) {
    return (
      <ErrorPanel
        title={t("draftWorkspace.loadFailed")}
        action={<Button variant="outline" onClick={() => void workspaceQuery.refetch()}>{t("common.refresh")}</Button>}
      />
    );
  }

  const timeline = buildTimeline(runs, toolCalls);
  const visibleMessages = (messagesQuery.data?.records ?? []).flatMap((item) => {
    const role = String(item.role).toUpperCase();
    return role === "USER" || role === "ASSISTANT" ? [{ ...item, role }] : [];
  });
  const draftId = workspace.currentDraftId ?? currentDraftId;

  return (
    <div className="min-w-0 space-y-5">
      <WorkspaceHeader workspace={workspace} onRefresh={() => void refreshWorkspaceQueries(workspaceId, queryClient)} />
      <RiskNotices reviewRisk={workspace.reviewRisk} />

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard icon={Clock3} label={t("draftWorkspace.metrics.wallTime")} value={formatDuration(workspace.resourceMetrics?.wallTimeMillis, t)} />
        <MetricCard icon={Cpu} label={t("draftWorkspace.metrics.peakMemory")} value={formatOptionalBytes(workspace.resourceMetrics?.peakMemoryKb, true)} />
        <MetricCard icon={Bot} label={t("draftWorkspace.metrics.modelCalls")} value={formatOptionalNumber(workspace.resourceMetrics?.modelCallCount)} />
        <MetricCard icon={Wrench} label={t("draftWorkspace.metrics.toolCalls")} value={formatOptionalNumber(workspace.resourceMetrics?.toolCallCount ?? toolCalls.length)} />
      </section>

      <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="min-w-0">
            <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("draftWorkspace.safeSummary")}</h3>
            <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-[var(--oj-ink-muted)]">
              {workspace.safeSummary || t("draftWorkspace.safeSummaryEmpty")}
            </p>
          </div>
          {draftId && onOpenDraft ? (
            <Button className="w-full shrink-0 sm:w-auto" variant="outline" onClick={() => onOpenDraft(draftId)}>
              {t("draftWorkspace.openDraft")}
            </Button>
          ) : null}
        </div>
      </section>

      <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <div className="mb-4 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("draftWorkspace.timelineTitle")}</h3>
            <p className="mt-1 text-xs leading-5 text-[var(--oj-ink-muted)]">{t("draftWorkspace.timelineSafeHint")}</p>
          </div>
          {(runsQuery.isFetching || toolCallsQuery.isFetching) ? <Badge tone="blue">{t("common.loading")}</Badge> : null}
        </div>
        {timeline.length ? <WorkspaceTimeline items={timeline} /> : (
          <p className="rounded-lg border border-dashed border-[var(--oj-border)] p-4 text-sm text-[var(--oj-ink-muted)]">{t("draftWorkspace.timelineEmpty")}</p>
        )}
      </section>

      <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <div className="mb-4">
          <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("draftWorkspace.messagesTitle")}</h3>
          <p className="mt-1 text-xs leading-5 text-[var(--oj-ink-muted)]">{t("draftWorkspace.messagesSafeHint")}</p>
        </div>
        {messagesQuery.isError ? <ErrorPanel title={t("draftWorkspace.messagesFailed")} /> : null}
        <div className="space-y-3">
          {visibleMessages.map((item) => (
            <article
              key={item.id}
              className={cn(
                "rounded-xl border p-3 text-sm leading-6",
                item.role === "USER"
                  ? "ml-0 border-blue-200 bg-blue-50 sm:ml-12"
                  : "mr-0 border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] sm:mr-12"
              )}
            >
              <div className="mb-1 flex items-center justify-between gap-3 text-xs text-[var(--oj-ink-muted)]">
                <strong>{item.role === "USER" ? t("draftWorkspace.roleUser") : t("draftWorkspace.roleAgent")}</strong>
                <span className="tabular-nums">{formatDateTime(item.createdAt)}</span>
              </div>
              <p className="whitespace-pre-wrap break-words text-[var(--oj-ink)]">{item.safeSummary || item.content}</p>
            </article>
          ))}
          {!visibleMessages.length && !messagesQuery.isLoading ? (
            <p className="rounded-lg border border-dashed border-[var(--oj-border)] p-4 text-sm text-[var(--oj-ink-muted)]">{t("draftWorkspace.messagesEmpty")}</p>
          ) : null}
          {(messagesQuery.data?.total ?? 0) > MESSAGES_PAGE_SIZE ? (
            <p className="pt-1 text-xs leading-5 text-[var(--oj-ink-muted)]">{t("draftWorkspace.messagesTruncated", { count: MESSAGES_PAGE_SIZE })}</p>
          ) : null}
        </div>
      </section>

      <section className="rounded-xl border border-[var(--oj-border)] bg-[var(--oj-surface-muted)] p-4">
        <div className="flex items-start gap-3">
          <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-[var(--oj-primary)] text-white">
            <MessageSquareText className="size-4" aria-hidden="true" />
          </span>
          <div className="min-w-0 flex-1">
            <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("draftWorkspace.continueTitle")}</h3>
            <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("draftWorkspace.continueDescription")}</p>
          </div>
        </div>
        <div className="mt-4 grid gap-3 lg:grid-cols-[220px_minmax(0,1fr)]">
          <Field label={t("draftWorkspace.intentLabel")}>
            <select className={selectClass} value={intent} onChange={(event) => setIntent(event.target.value)}>
              {TURN_INTENTS.map((item) => <option key={item} value={item}>{t(`draftWorkspace.intent.${item}`)}</option>)}
            </select>
          </Field>
          <Field label={t("draftWorkspace.messageLabel")}>
            <textarea
              className={`${textareaClass} min-h-32`}
              value={message}
              maxLength={4000}
              onChange={(event) => setMessage(event.target.value)}
              placeholder={t("draftWorkspace.messagePlaceholder")}
            />
          </Field>
        </div>
        <div className="mt-3 flex justify-end">
          <Button
            className="w-full sm:w-auto"
            disabled={turnMutation.isPending || !message.trim()}
            onClick={() => turnMutation.mutate({ message: message.trim(), intent })}
          >
            {turnMutation.isPending ? t("common.loading") : t("draftWorkspace.sendTurn")}
          </Button>
        </div>
      </section>
    </div>
  );
}

function WorkspaceHeader({ workspace, onRefresh }: { workspace: ProblemDraftWorkspaceResponse; onRefresh: () => void }) {
  const { t } = useI18n();
  return (
    <header className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h2 className="break-words text-base font-semibold text-[var(--oj-ink)]">{workspace.title || workspace.topic || t("draftWorkspace.untitled")}</h2>
          <p className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">{t("draftWorkspace.updatedAt", { time: formatDateTime(workspace.updatedAt) })}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={workspaceStatusTone(workspace.status)}>{workspaceStatusLabel(workspace.status, t)}</Badge>
          {workspace.stage ? <Badge tone="neutral">{workspaceStageLabel(workspace.stage, t)}</Badge> : null}
          {workspace.agentMode ? <Badge tone="blue">{workspace.agentMode}</Badge> : null}
          <Button size="sm" variant="outline" onClick={onRefresh} aria-label={t("common.refresh")}>
            <RefreshCw className="size-4" aria-hidden="true" />
          </Button>
        </div>
      </div>
      {workspace.errorMessage ? <p className="mt-3 rounded-lg border border-red-200 bg-red-50 p-3 text-sm leading-6 text-red-700">{workspace.errorMessage}</p> : null}
    </header>
  );
}

export function RiskNotices({
  reviewRisk
}: {
  reviewRisk?: ProblemDraftReviewRisk | null;
}) {
  const { t } = useI18n();
  const showHigh = Boolean(reviewRisk?.highDifficulty);
  const showManual = Boolean(reviewRisk?.manualUnverified);
  if (!showHigh && !showManual) return null;
  return (
    <div className="space-y-3">
      {showHigh ? (
        <ErrorPanel tone="warning" title={t("draftWorkspace.highDifficultyTitle")} description={t("draftWorkspace.highDifficultyDescription")} />
      ) : null}
      {showManual ? (
        <ErrorPanel tone="warning" title={t("draftWorkspace.manualUnverifiedTitle")} description={t("draftWorkspace.manualUnverifiedDescription")} />
      ) : null}
    </div>
  );
}

function WorkspaceTimeline({ items }: { items: TimelineItem[] }) {
  return (
    <>
      <div className="space-y-3 sm:hidden">
        {items.map((item) => <TimelineCard key={`${item.kind}-${item.id}`} item={item} mobile />)}
      </div>
      <ol className="relative ml-3 hidden border-l border-[var(--oj-border)] pl-6 sm:block">
        {items.map((item) => (
          <li key={`${item.kind}-${item.id}`} className="relative pb-5 last:pb-0">
            <span className={cn(
              "absolute -left-[31px] top-4 grid size-3 rounded-full border-2 border-white",
              item.kind === "tool" ? "bg-violet-500" : "bg-blue-500"
            )} />
            <TimelineCard item={item} />
          </li>
        ))}
      </ol>
    </>
  );
}

function TimelineCard({ item, mobile = false }: { item: TimelineItem; mobile?: boolean }) {
  const { t } = useI18n();
  const isTool = item.kind === "tool";
  const status = item.value.status;
  const title = isTool ? toolLabel(item.value.toolName, t) : t("draftWorkspace.agentRun");
  const summary = isTool
    ? item.value.safeSummary || item.value.safeResultSummary || item.value.safeArgumentsSummary || item.value.resultClassification
    : item.value.safeSummary;
  const metrics = item.kind === "tool" ? item.value.resourceMetrics : undefined;
  return (
    <article className={cn("rounded-xl border border-[var(--oj-border-soft)] bg-white p-3", mobile && "min-w-0")}>
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 items-center gap-2">
          {isTool ? <Wrench className="size-4 shrink-0 text-violet-600" aria-hidden="true" /> : <Activity className="size-4 shrink-0 text-blue-600" aria-hidden="true" />}
          <strong className="truncate text-sm text-[var(--oj-ink)]" title={title}>{title}</strong>
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-2">
          <Badge tone={operationStatusTone(status)}>{operationStatusLabel(status, t)}</Badge>
          <span className="text-xs tabular-nums text-[var(--oj-ink-muted)]">{formatDateTime(item.createdAt)}</span>
        </div>
      </div>
      {summary ? <p className="mt-2 whitespace-pre-wrap break-words text-sm leading-6 text-[var(--oj-ink-muted)]">{summary}</p> : null}
      {isTool && item.value.safeArgumentsSummary ? (
        <p className="mt-2 break-words text-xs leading-5 text-[var(--oj-ink-muted)]">{t("draftWorkspace.safeInput")}: {item.value.safeArgumentsSummary}</p>
      ) : null}
      {hasMetrics(metrics) ? <CompactMetrics metrics={metrics} /> : null}
    </article>
  );
}

function CompactMetrics({ metrics }: { metrics: ProblemDraftResourceMetrics }) {
  const { t } = useI18n();
  const values = [
    metrics.wallTimeMillis != null ? `${t("draftWorkspace.metrics.wallTime")}: ${formatDuration(metrics.wallTimeMillis, t)}` : null,
    metrics.peakMemoryKb != null ? `${t("draftWorkspace.metrics.peakMemory")}: ${formatOptionalBytes(metrics.peakMemoryKb, true)}` : null,
    metrics.testcaseCount != null ? `${t("draftWorkspace.metrics.cases")}: ${metrics.testcaseCount}` : null,
    metrics.outputBytes != null ? `${t("draftWorkspace.metrics.output")}: ${formatBytes(metrics.outputBytes)}` : null
  ].filter(Boolean);
  return <p className="mt-2 text-xs tabular-nums leading-5 text-[var(--oj-ink-muted)]">{values.join(" · ")}</p>;
}

function MetricCard({ icon: Icon, label, value }: { icon: React.ComponentType<{ className?: string; "aria-hidden"?: boolean | "true" | "false" }>; label: string; value: string }) {
  return (
    <div className="rounded-xl border border-[var(--oj-border-soft)] bg-white p-3">
      <div className="flex items-center gap-2 text-xs font-semibold text-[var(--oj-ink-muted)]">
        <Icon className="size-4" aria-hidden="true" />
        <span>{label}</span>
      </div>
      <strong className="mt-2 block text-lg tabular-nums text-[var(--oj-ink)]">{value}</strong>
    </div>
  );
}

function LegacyJobWorkspace({ job, onOpenDraft }: { job: ProblemDraftGenerationJobResponse; onOpenDraft?: (draftId: EntityId) => void }) {
  const { t } = useI18n();
  return (
    <div className="space-y-4">
      <ErrorPanel tone="warning" title={t("draftWorkspace.legacyTitle")} description={t("draftWorkspace.legacyDescription")} />
      <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="min-w-0">
            <h2 className="break-words text-base font-semibold text-[var(--oj-ink)]">{job.topicSnapshot || t("draftJobs.topicEmpty")}</h2>
            <p className="mt-2 text-sm leading-6 text-[var(--oj-ink-muted)]">{job.errorMessage
              ? readableStoredError(job.errorMessage, getStoredLocale(), t("draftWorkspace.safeSummaryEmpty"), "ai")
              : job.progressMessage || t("draftWorkspace.safeSummaryEmpty")}</p>
          </div>
          <Badge tone={workspaceStatusTone(job.status)}>{workspaceStatusLabel(job.status, t)}</Badge>
        </div>
        <dl className="mt-4 grid gap-3 sm:grid-cols-2">
          <LegacyValue label={t("draftJobs.createdAt")} value={formatDateTime(job.createdAt)} />
          <LegacyValue label={t("draftJobs.updatedAt")} value={formatDateTime(job.updatedAt)} />
        </dl>
        {job.draftId && onOpenDraft ? <Button className="mt-4 w-full sm:w-auto" onClick={() => onOpenDraft(job.draftId!)}>{t("draftWorkspace.openDraft")}</Button> : null}
      </section>
    </div>
  );
}

function LegacyValue({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg bg-[var(--oj-surface-muted)] p-3">
      <dt className="text-xs text-[var(--oj-ink-muted)]">{label}</dt>
      <dd className="mt-1 text-sm tabular-nums text-[var(--oj-ink)]">{value}</dd>
    </div>
  );
}

function buildTimeline(runs: ProblemDraftAgentRunResponse[], toolCalls: ProblemDraftToolCallResponse[]): TimelineItem[] {
  return [
    ...runs.map((value): TimelineItem => ({ kind: "run", id: value.id, createdAt: value.startedAt || value.createdAt || value.completedAt || "", value })),
    ...toolCalls.map((value): TimelineItem => ({ kind: "tool", id: value.id, createdAt: value.startedAt || value.createdAt || value.completedAt || "", value }))
  ].sort((left, right) => Date.parse(left.createdAt) - Date.parse(right.createdAt));
}

function mergeById<T extends { id: EntityId }>(previous: T[], incoming: T[]) {
  const next = new Map(previous.map((item) => [item.id, item]));
  incoming.forEach((item) => next.set(item.id, item));
  return Array.from(next.values());
}

function isWorkspaceActive(workspace: ProblemDraftWorkspaceResponse | undefined) {
  const status = String(workspace?.status || "").toUpperCase();
  return status === "QUEUED" || status === "RUNNING" || status === "ACTIVE";
}

function incrementalCursor(items: Array<{ id: string; status: string }>) {
  const latest = items.at(-1);
  if (!latest) return undefined;
  const status = String(latest.status || "").toUpperCase();
  if (status === "QUEUED" || status === "PENDING" || status === "RUNNING" || status === "ACTIVE" || status === "GENERATING") {
    return items.at(-2)?.id;
  }
  return latest.id;
}

function workspaceStatusTone(status: string): "blue" | "green" | "amber" | "red" | "neutral" {
  if (status === "SUCCEEDED" || status === "COMPLETED") return "green";
  if (status === "FAILED") return "red";
  if (status === "RUNNING" || status === "ACTIVE") return "blue";
  if (status === "QUEUED") return "amber";
  if (status === "NEEDS_ATTENTION" || status === "PENDING_REVIEW") return "amber";
  return "neutral";
}

function operationStatusTone(status: string): "blue" | "green" | "amber" | "red" | "neutral" {
  const normalized = String(status).toUpperCase();
  if (normalized === "SUCCEEDED" || normalized === "COMPLETED" || normalized === "ACCEPTED" || normalized === "VERIFIED") return "green";
  if (normalized === "FAILED" || normalized === "ERROR" || normalized === "DENIED") return "red";
  if (normalized === "RUNNING" || normalized === "ACTIVE") return "blue";
  if (normalized === "QUEUED" || normalized === "PENDING") return "amber";
  return "neutral";
}

function workspaceStatusLabel(status: string, t: ReturnType<typeof useI18n>["t"]) {
  return t(`draftWorkspace.status.${status}`, undefined, status);
}

function workspaceStageLabel(stage: string, t: ReturnType<typeof useI18n>["t"]) {
  return t(`draftJobs.stage.${stage}`, undefined, stage);
}

function operationStatusLabel(status: string, t: ReturnType<typeof useI18n>["t"]) {
  return t(`draftWorkspace.operationStatus.${status}`, undefined, status);
}

function toolLabel(toolName: string, t: ReturnType<typeof useI18n>["t"]) {
  const key = toolName.replace(/[^A-Za-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
  return t(`draftWorkspace.tools.${key}`, undefined, toolName);
}

function hasMetrics(metrics: ProblemDraftResourceMetrics | null | undefined): metrics is ProblemDraftResourceMetrics {
  return Boolean(metrics && Object.values(metrics).some((value) => value !== null && value !== undefined));
}

function formatDuration(value: number | null | undefined, t: ReturnType<typeof useI18n>["t"]) {
  if (value == null || !Number.isFinite(value)) return "--";
  if (value < 1000) return t("draftWorkspace.milliseconds", { value: Math.max(0, Math.round(value)) });
  return t("draftWorkspace.seconds", { value: (value / 1000).toFixed(1) });
}

function formatOptionalBytes(value: number | null | undefined, isKb = false) {
  if (value == null || !Number.isFinite(value)) return "--";
  return formatBytes(isKb ? value * 1024 : value);
}

function formatOptionalNumber(value: number | null | undefined) {
  return value == null || !Number.isFinite(value) ? "--" : String(value);
}

function createClientTurnId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  return `turn-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

async function refreshWorkspaceQueries(workspaceId: EntityId, queryClient: ReturnType<typeof useQueryClient>) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ["admin-problem-draft-workspace", workspaceId] }),
    queryClient.invalidateQueries({ queryKey: ["admin-problem-draft-workspace-messages", workspaceId] }),
    queryClient.invalidateQueries({ queryKey: ["admin-problem-draft-workspace-runs", workspaceId] }),
    queryClient.invalidateQueries({ queryKey: ["admin-problem-draft-workspace-tool-calls", workspaceId] })
  ]);
}
