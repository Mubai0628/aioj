import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot, CheckCircle2, Download, Eye, EyeOff, FileArchive, ShieldCheck, Upload } from "lucide-react";
import {
  activeQueryRefetchInterval,
  api,
  type ProblemDraftResponse,
  type ProblemDraftTestcaseArtifactResponse,
  type ProblemDraftTestcaseUploadStatusResponse
} from "@aioj/api-client";
import { Badge, Button, cn } from "@aioj/ui-react";
import { ErrorPanel, Field, inputClass } from "./Common";
import { RiskNotices } from "./AiDraftAgentWorkspace";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { formatBytes, formatDateTime, shortId } from "../lib/format";
import { readableCaughtError } from "../lib/readableError";

type UploadPhase = "idle" | "hashing" | "uploading" | "completing" | "processing" | "done";

export function AiDraftTestcasePanel({
  draft,
  onUpdated
}: {
  draft: ProblemDraftResponse;
  onUpdated: (draft: ProblemDraftResponse) => Promise<void> | void;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [file, setFile] = React.useState<File | null>(null);
  const [phase, setPhase] = React.useState<UploadPhase>("idle");
  const [uploadStatus, setUploadStatus] = React.useState<ProblemDraftTestcaseUploadStatusResponse | null>(null);
  const [localArtifact, setLocalArtifact] = React.useState<ProblemDraftTestcaseArtifactResponse | null>(null);
  const [sha256Value, setSha256Value] = React.useState("");
  const [validationRequested, setValidationRequested] = React.useState(false);
  const [chunkSizeBytes, setChunkSizeBytes] = React.useState<number | null>(null);
  const workspaceId = draft.workspaceId;
  const currentArtifact = localArtifact ?? draft.testcaseArtifact ?? null;
  const generatedArtifact = draft.testcaseArtifact?.source === "AGENT_GENERATED" ? draft.testcaseArtifact : null;
  const manualArtifact = currentArtifact?.source === "MANUAL_UPLOAD" ? currentArtifact : null;
  const uploadId = uploadStatus?.uploadId;

  React.useEffect(() => {
    setFile(null);
    setPhase("idle");
    setUploadStatus(null);
    setLocalArtifact(null);
    setSha256Value("");
    setValidationRequested(false);
    setChunkSizeBytes(null);
  }, [draft.id, workspaceId]);

  const uploadStatusQuery = useQuery({
    queryKey: ["admin-problem-draft-testcase-upload", workspaceId, uploadId],
    queryFn: () => api.problemDraftTestcaseUploadStatus(String(workspaceId), String(uploadId)),
    enabled: Boolean(workspaceId && uploadId && isUploadActive(uploadStatus?.status)),
    refetchInterval: (query) => activeQueryRefetchInterval(query, (data) => isUploadActive(data?.status), {
      fastMs: 1500,
      slowMs: 3500,
      hiddenMs: 8000
    })
  });

  React.useEffect(() => {
    const next = uploadStatusQuery.data;
    if (!next) return;
    setUploadStatus(next);
    if (isUploadDone(next.status)) {
      setPhase("done");
      void refreshDraft();
    } else if (next.status === "FAILED") {
      setPhase("idle");
    } else {
      setPhase("processing");
    }
  }, [uploadStatusQuery.data]);

  const validationMutation = useMutation({
    mutationFn: (artifactId: string) => api.validateProblemDraftTestcaseArtifact(String(workspaceId), artifactId),
    onSuccess: async (result) => {
      setLocalArtifact(result);
      setValidationRequested(true);
      toast.success(t("draftTestcase.validationQueued"));
      await refreshDraft();
    },
    onError: (caught) => toast.error(readableCaughtError(caught, locale, t("draftTestcase.validationFailed")))
  });

  const validationWorkspaceQuery = useQuery({
    queryKey: ["admin-problem-draft-testcase-validation", workspaceId],
    queryFn: () => api.problemDraftWorkspace(String(workspaceId)),
    enabled: Boolean(workspaceId && validationRequested),
    refetchInterval: (query) => activeQueryRefetchInterval(query, (data) =>
      Boolean(validationRequested && isArtifactValidationActive(data?.testcaseArtifact?.executionStatus)), {
        fastMs: 1600,
        slowMs: 4000,
        hiddenMs: 9000
      })
  });

  React.useEffect(() => {
    const artifact = validationWorkspaceQuery.data?.testcaseArtifact;
    if (!artifact || !validationRequested) return;
    setLocalArtifact(artifact);
    if (!isArtifactValidationActive(artifact.executionStatus)) {
      setValidationRequested(false);
      void refreshDraft();
    }
  }, [validationRequested, validationWorkspaceQuery.data?.testcaseArtifact]);

  async function refreshDraft() {
    const nextDraft = await api.problemDraft(draft.id);
    await onUpdated(nextDraft);
    await queryClient.invalidateQueries({ queryKey: ["admin-problem-draft-workspace", workspaceId] });
  }

  async function uploadPackage() {
    if (!workspaceId || !file) return;
    if (!file.name.toLowerCase().endsWith(".zip")) {
      toast.error(t("draftTestcase.zipRequired"));
      return;
    }
    try {
      setPhase("hashing");
      const totalSha = await sha256(file);
      setSha256Value(totalSha);
      const init = await api.initProblemDraftTestcaseUpload(workspaceId, {
        fileName: file.name,
        fileSizeBytes: file.size,
        sha256: totalSha
      });
      setChunkSizeBytes(init.chunkSizeBytes);
      let status: ProblemDraftTestcaseUploadStatusResponse = {
        uploadId: init.uploadId,
        workspaceId: init.workspaceId,
        status: init.status,
        receivedChunkCount: 0,
        chunkCount: init.chunkCount,
        uploadedSizeBytes: 0,
        expectedSizeBytes: file.size,
        expiresAt: init.expiresAt,
        updatedAt: new Date().toISOString()
      };
      setUploadStatus(status);
      setPhase("uploading");
      for (let index = status.receivedChunkCount; index < status.chunkCount; index += 1) {
        const chunk = file.slice(index * init.chunkSizeBytes, Math.min(file.size, (index + 1) * init.chunkSizeBytes));
        status = await api.uploadProblemDraftTestcaseChunk(workspaceId, status.uploadId, index, chunk, await sha256(chunk));
        setUploadStatus(status);
      }
      setPhase("completing");
      status = await api.completeProblemDraftTestcaseUpload(workspaceId, status.uploadId, totalSha);
      setUploadStatus(status);
      if (isUploadDone(status.status)) {
        setPhase("done");
        toast.success(t("draftTestcase.uploaded"));
        await refreshDraft();
      } else {
        setPhase("processing");
      }
    } catch (caught) {
      setPhase("idle");
      toast.error(readableCaughtError(caught, locale, t("draftTestcase.uploadFailed")));
    }
  }

  return (
    <div className="space-y-5">
      <RiskNotices reviewRisk={draft.reviewRisk} />

      <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <div className="flex items-start gap-3">
          <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-blue-50 text-blue-700">
            <Bot className="size-5" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("draftTestcase.agentPathTitle")}</h3>
            <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("draftTestcase.agentPathDescription")}</p>
          </div>
        </div>
        <div className="mt-4">
          {generatedArtifact ? (
            <ArtifactCard artifact={generatedArtifact} workspaceId={workspaceId} />
          ) : (
            <p className="rounded-lg border border-dashed border-[var(--oj-border)] p-4 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("draftTestcase.agentArtifactEmpty")}</p>
          )}
        </div>
      </section>

      <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <div className="flex items-start gap-3">
          <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-amber-50 text-amber-700">
            <Upload className="size-5" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("draftTestcase.manualPathTitle")}</h3>
            <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("draftTestcase.manualPathDescription")}</p>
          </div>
        </div>

        {!workspaceId ? (
          <ErrorPanel tone="warning" title={t("draftTestcase.workspaceRequiredTitle")} description={t("draftTestcase.workspaceRequiredDescription")} />
        ) : (
          <div className="mt-4 space-y-4">
            <Field label={t("draftTestcase.selectZip")}>
              <input
                className={inputClass}
                type="file"
                accept=".zip,application/zip"
                onChange={(event) => {
                  setFile(event.target.files?.[0] ?? null);
                  setUploadStatus(null);
                  setLocalArtifact(null);
                  setSha256Value("");
                  setChunkSizeBytes(null);
                  setPhase("idle");
                }}
              />
            </Field>
            {file ? (
              <dl className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                <Fact label={t("draftTestcase.fileName")} value={file.name} />
                <Fact label={t("draftTestcase.fileSize")} value={formatBytes(file.size)} />
                <Fact label={t("draftTestcase.chunkSize")} value={chunkSizeBytes != null ? formatBytes(chunkSizeBytes) : "--"} />
                <Fact label="SHA-256" value={sha256Value || t(`draftTestcase.phase.${phase}`)} title={sha256Value || undefined} />
              </dl>
            ) : null}
            {uploadStatus ? <UploadProgress status={uploadStatus} phase={phase} /> : null}
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <p className="text-xs leading-5 text-[var(--oj-ink-muted)]">{t("draftTestcase.uploadSafetyHint")}</p>
              <Button className="w-full shrink-0 sm:w-auto" disabled={!file || phase !== "idle"} onClick={() => void uploadPackage()}>
                <Upload className="size-4" aria-hidden="true" />
                {phase === "idle" ? t("draftTestcase.upload") : t(`draftTestcase.phase.${phase}`)}
              </Button>
            </div>
            {manualArtifact ? <ArtifactCard artifact={manualArtifact} workspaceId={workspaceId} /> : null}
          </div>
        )}
      </section>

      {currentArtifact ? (
        <section className="rounded-xl border border-[var(--oj-border)] bg-[var(--oj-surface-muted)] p-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <ShieldCheck className="size-5 text-emerald-700" aria-hidden="true" />
                <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("draftTestcase.agentValidationTitle")}</h3>
              </div>
              <p className="mt-2 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("draftTestcase.agentValidationDescription")}</p>
            </div>
            <Button
              className="w-full shrink-0 sm:w-auto"
              variant="outline"
              disabled={!workspaceId || validationMutation.isPending || validationRequested || currentArtifact.executionStatus === "RUNNING" || currentArtifact.validationStatus === "RUNNING"}
              onClick={() => validationMutation.mutate(currentArtifact.id)}
            >
              {validationMutation.isPending ? t("common.loading") : t("draftTestcase.runAgentValidation")}
            </Button>
          </div>
        </section>
      ) : null}

      <ImportReadinessCard draft={draft} artifact={currentArtifact} />
    </div>
  );
}

function ArtifactCard({ artifact, workspaceId }: { artifact: ProblemDraftTestcaseArtifactResponse; workspaceId?: string | null }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [showEntries, setShowEntries] = React.useState(false);
  const [downloading, setDownloading] = React.useState(false);
  const ready = (artifact.lifecycleStatus || artifact.status) === "READY" && Boolean(workspaceId);
  const entriesQuery = useQuery({
    queryKey: ["problem-draft-testcase-artifact-entries", workspaceId, artifact.id],
    queryFn: () => api.problemDraftTestcaseArtifactEntries(String(workspaceId), artifact.id),
    enabled: ready && showEntries,
    staleTime: 60_000
  });

  async function download() {
    setDownloading(true);
    try {
      const file = await api.downloadProblemDraftTestcaseArtifact(String(workspaceId), artifact.id);
      saveDownloadedFile(file.blob, file.fileName || artifact.fileName || "testcase-package.zip");
    } catch (caught) {
      toast.error(readableCaughtError(caught, locale, t("draftTestcase.downloadFailed")));
    } finally {
      setDownloading(false);
    }
  }

  return (
    <article className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
      <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 items-start gap-3">
          <FileArchive className="mt-0.5 size-5 shrink-0 text-[var(--oj-primary)]" aria-hidden="true" />
          <div className="min-w-0">
          <strong className="block truncate text-sm text-[var(--oj-ink)]" title={artifact.fileName || artifact.sha256}>{artifact.fileName || t("draftTestcase.artifactFallbackName")}</strong>
            <p className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">#{shortId(artifact.id)}{artifact.generatedAt || artifact.createdAt ? ` · ${formatDateTime(artifact.generatedAt || artifact.createdAt || "")}` : ""}</p>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Badge tone={artifactStatusTone(artifact)}>{artifactStatusLabel(artifact.lifecycleStatus || artifact.status || "UNKNOWN", t)}</Badge>
          <Badge tone={artifact.executionStatus === "VERIFIED" || artifact.executionStatus === "PASSED" ? "green" : artifact.executionStatus === "FAILED" ? "red" : "neutral"}>
            {artifactValidationLabel(artifact.executionStatus || artifact.validationStatus, t)}
          </Badge>
        </div>
      </div>
      <dl className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Fact label={t("draftTestcase.fileSize")} value={formatBytes(artifact.fileSizeBytes)} />
        <Fact label={t("draftTestcase.caseCount")} value={artifact.caseCount == null ? "--" : String(artifact.caseCount)} />
        <Fact label={t("draftTestcase.source")} value={artifact.source} />
        <Fact label="SHA-256" value={artifact.sha256} title={artifact.sha256} />
      </dl>
      {artifact.stale ? <p className="mt-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">{t("draftTestcase.staleArtifact")}</p> : null}
      {artifact.failureSummary ? <p className="mt-3 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">{artifact.failureSummary}</p> : null}
      {ready ? (
        <div className="mt-4 flex flex-wrap gap-2">
          <Button type="button" size="sm" variant="outline" onClick={() => setShowEntries((value) => !value)}>
            {showEntries ? <EyeOff className="size-4" aria-hidden="true" /> : <Eye className="size-4" aria-hidden="true" />}
            {showEntries ? t("draftTestcase.hideEntries") : t("draftTestcase.showEntries")}
          </Button>
          <Button type="button" size="sm" variant="outline" disabled={downloading} onClick={() => void download()}>
            <Download className="size-4" aria-hidden="true" />
            {t("draftTestcase.downloadPackage")}
          </Button>
        </div>
      ) : null}
      {ready && showEntries ? (
        entriesQuery.isLoading ? (
          <p className="mt-3 text-sm text-[var(--oj-ink-muted)]">{t("draftTestcase.entriesLoading")}</p>
        ) : entriesQuery.isError || !entriesQuery.data ? (
          <ErrorPanel title={t("draftTestcase.entriesFailed")} />
        ) : (
          <div className="mt-3 space-y-2">
            <p className="text-xs text-[var(--oj-ink-muted)]">
              {t("draftTestcase.entriesShown", { shown: entriesQuery.data.entries.length, total: entriesQuery.data.totalEntries })}
            </p>
            <div className="max-h-96 space-y-2 overflow-auto">
              {entriesQuery.data.entries.map((entry) => (
                <details key={entry.name} className="rounded-lg border border-[var(--oj-border-soft)] bg-white px-3 py-2">
                  <summary className="cursor-pointer break-all text-xs text-[var(--oj-ink)]">
                    {entry.name} <span className="tabular-nums text-[var(--oj-ink-muted)]">· {formatBytes(entry.sizeBytes)}</span>
                  </summary>
                  {entry.binary ? (
                    <p className="mt-2 text-xs text-[var(--oj-ink-muted)]">{t("draftTestcase.entryBinary")}</p>
                  ) : (
                    <>
                      <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap break-all rounded bg-[var(--oj-surface-muted)] p-2 text-xs">{entry.preview}</pre>
                      {entry.truncated ? <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">{t("draftTestcase.entryTruncated")}</p> : null}
                    </>
                  )}
                </details>
              ))}
            </div>
          </div>
        )
      ) : null}
    </article>
  );
}

function saveDownloadedFile(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function UploadProgress({ status, phase }: { status: ProblemDraftTestcaseUploadStatusResponse; phase: UploadPhase }) {
  const { t } = useI18n();
  const total = Math.max(1, status.chunkCount || 1);
  const percent = Math.round(((status.receivedChunkCount || 0) / total) * 100);
  return (
    <div className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
      <div className="flex items-center justify-between gap-3 text-xs text-[var(--oj-ink-muted)]">
        <span>{t(`draftTestcase.phase.${phase}`)}</span>
        <span className="tabular-nums">{Math.min(100, Math.max(0, percent))}%</span>
      </div>
      <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-white">
        <div className="h-full rounded-full bg-[var(--oj-primary)]" style={{ width: `${Math.min(100, Math.max(0, percent))}%` }} />
      </div>
      {status.errorMessage ? <p className="mt-2 text-sm text-red-700">{status.errorMessage}</p> : null}
    </div>
  );
}

function ImportReadinessCard({ draft, artifact }: { draft: ProblemDraftResponse; artifact: ProblemDraftTestcaseArtifactResponse | null }) {
  const { t } = useI18n();
  const readiness = draft.importReadiness;
  const ready = readiness?.ready ?? (
    draft.validationStatus === "VALID"
    && (draft.verificationStatus === "EXECUTION_VERIFIED" || draft.verificationStatus === "MANUAL_VERIFIED")
  );
  return (
    <section className={cn(
      "rounded-xl border p-4",
      ready ? "border-emerald-200 bg-emerald-50" : "border-amber-200 bg-amber-50"
    )}>
      <div className="flex items-start gap-3">
        <CheckCircle2 className={cn("mt-0.5 size-5 shrink-0", ready ? "text-emerald-700" : "text-amber-700")} aria-hidden="true" />
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{ready ? t("draftTestcase.importReady") : t("draftTestcase.importNotReady")}</h3>
          <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">
            {ready ? t("draftTestcase.importReadyDescription") : t("draftTestcase.importNotReadyDescription")}
          </p>
          {readiness?.verificationMode ? <Badge className="mt-2" tone={readiness.requiresUnverifiedAcknowledgement ? "amber" : "green"}>{readiness.verificationMode}</Badge> : null}
          {readiness?.blockerCodes?.length ? <p className="mt-2 text-xs leading-5 text-amber-800">{readiness.blockerCodes.join(" · ")}</p> : null}
          {artifact ? <p className="mt-2 text-xs tabular-nums text-[var(--oj-ink-muted)]">{t("draftTestcase.selectedArtifact", { id: shortId(artifact.id) })}</p> : null}
        </div>
      </div>
    </section>
  );
}

function Fact({ label, value, title }: { label: string; value: string; title?: string }) {
  return (
    <div className="min-w-0 rounded-lg border border-[var(--oj-border-soft)] bg-white p-3">
      <dt className="text-xs text-[var(--oj-ink-muted)]">{label}</dt>
      <dd className="mt-1 truncate text-sm tabular-nums text-[var(--oj-ink)]" title={title ?? value}>{value}</dd>
    </div>
  );
}

function isUploadActive(status: string | null | undefined) {
  return status === "UPLOADING" || status === "PROCESSING" || status === "QUEUED" || status === "RUNNING";
}

function isUploadDone(status: string | null | undefined) {
  return status === "READY" || status === "COMPLETED" || status === "SUCCEEDED";
}

function isArtifactValidationActive(status: string | null | undefined) {
  return status == null || status === "NOT_RUN" || status === "QUEUED" || status === "RUNNING";
}

function artifactStatusLabel(status: string, t: ReturnType<typeof useI18n>["t"]) {
  return t(`draftTestcase.artifactStatus.${status}`, undefined, status);
}

function artifactValidationLabel(status: string | null | undefined, t: ReturnType<typeof useI18n>["t"]) {
  const normalized = status || "NOT_RUN";
  return t(`draftTestcase.validationStatus.${normalized}`, undefined, normalized);
}

function artifactStatusTone(artifact: ProblemDraftTestcaseArtifactResponse): "green" | "red" | "amber" | "neutral" {
  const status = artifact.lifecycleStatus || artifact.status;
  if (status === "READY" || status === "CURRENT") return "green";
  if (status === "FAILED" || artifact.formatStatus === "FAILED") return "red";
  if (artifact.stale) return "amber";
  return "neutral";
}

async function sha256(blob: Blob) {
  if (!globalThis.crypto?.subtle) throw new Error("SHA-256 is unavailable in this browser");
  const digest = await globalThis.crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
