import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { api, type JudgeMetricsResponse } from "@aioj/api-client";
import { Button, Card, CardBody } from "@aioj/ui-react";
import { ErrorPanel, LoadingPanel, PageHeader, StatusBadge } from "../components/Common";
import { useI18n } from "../lib/i18n";

type TFunction = ReturnType<typeof useI18n>["t"];

export function JudgeMetricsView() {
  const { t, locale } = useI18n();
  const metricsQuery = useQuery({
    queryKey: ["judge-metrics"],
    queryFn: () => api.judgeMetrics(),
    refetchInterval: 30_000
  });
  const metrics = metricsQuery.data;

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageHeader
        eyebrow={t("judgeMetrics.eyebrow")}
        title={t("judgeMetrics.title")}
        description={t("judgeMetrics.description")}
        actions={(
          <Button variant="outline" disabled={metricsQuery.isFetching} onClick={() => void metricsQuery.refetch()}>
            <RefreshCw className="size-4" aria-hidden="true" />
            {metricsQuery.isFetching ? t("judgeMetrics.refreshing") : t("judgeMetrics.refresh")}
          </Button>
        )}
      />
      {metricsQuery.isLoading ? (
        <LoadingPanel label={t("judgeMetrics.loading")} />
      ) : metricsQuery.isError || !metrics ? (
        <ErrorPanel
          title={t("judgeMetrics.loadFailed")}
          action={<Button variant="outline" onClick={() => void metricsQuery.refetch()}>{t("judgeMetrics.retry")}</Button>}
        />
      ) : (
        <>
          <MetricsSection
            title={t("judgeMetrics.queueSection")}
            status={metrics.queueReady == null
              ? <StatusBadge label={t("judgeMetrics.brokerUnavailable")} tone="amber" />
              : null}
            items={queueItems(metrics, t)}
          />
          <MetricsSection
            title={t("judgeMetrics.workerSection")}
            status={metrics.workerReachable
              ? <StatusBadge label={t("judgeMetrics.workerReachable")} tone="green" />
              : <StatusBadge label={t("judgeMetrics.workerUnreachable")} tone="red" />}
            note={metrics.workerReachable ? t("judgeMetrics.sinceStart") : undefined}
            items={metrics.workerReachable ? workerItems(metrics, t, locale) : []}
          />
          <p className="text-xs text-[var(--oj-ink-muted)]">
            {t("judgeMetrics.collectedAt")}: {formatTime(metrics.collectedAt, locale)}
          </p>
        </>
      )}
    </div>
  );
}

function MetricsSection({
  title,
  status,
  note,
  items
}: {
  title: string;
  status: ReactNode;
  note?: string;
  items: { label: string; value: string }[];
}) {
  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-[var(--oj-ink)]">{title}</h2>
        {status}
        {note ? <span className="text-xs text-[var(--oj-ink-muted)]">{note}</span> : null}
      </div>
      {items.length ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {items.map((item) => (
            <Card key={item.label} className="rounded-xl shadow-none">
              <CardBody>
                <p className="text-sm text-[var(--oj-ink-muted)]">{item.label}</p>
                <p className="mt-2 break-all text-2xl font-semibold tabular-nums text-[var(--oj-ink)]">{item.value}</p>
              </CardBody>
            </Card>
          ))}
        </div>
      ) : null}
    </section>
  );
}

function queueItems(metrics: JudgeMetricsResponse, t: TFunction) {
  if (metrics.queueReady == null) return [];
  return [
    { label: t("judgeMetrics.queueReady"), value: formatNumber(metrics.queueReady) },
    { label: t("judgeMetrics.queueConsumers"), value: formatNumber(metrics.queueConsumers) },
    { label: t("judgeMetrics.deadLetterReady"), value: formatNumber(metrics.deadLetterReady) }
  ];
}

function workerItems(metrics: JudgeMetricsResponse, t: TFunction, locale: string) {
  return [
    { label: t("judgeMetrics.inFlight"), value: formatNumber(metrics.inFlight) },
    { label: t("judgeMetrics.started"), value: formatNumber(metrics.started) },
    { label: t("judgeMetrics.finished"), value: formatNumber(metrics.finished) },
    { label: t("judgeMetrics.systemErrors"), value: formatNumber(metrics.systemErrors) },
    { label: t("judgeMetrics.failed"), value: formatNumber(metrics.failed) },
    { label: t("judgeMetrics.averageQueueWait"), value: formatNumber(metrics.averageQueueWaitMillis) },
    { label: t("judgeMetrics.averageJudge"), value: formatNumber(metrics.averageJudgeMillis) },
    { label: t("judgeMetrics.lastFinishedAt"), value: formatTime(metrics.lastFinishedAt, locale) },
    {
      label: t("judgeMetrics.lastFailure"),
      value: metrics.lastFailureAt
        ? `${formatTime(metrics.lastFailureAt, locale)} · ${metrics.lastFailureType ?? "--"}`
        : "--"
    }
  ];
}

function formatNumber(value?: number | null) {
  return value == null ? "--" : String(value);
}

function formatTime(value: string | null | undefined, locale: string) {
  if (!value) return "--";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "--" : date.toLocaleString(locale);
}
