import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Loader2, Play, X } from "lucide-react";
import {
  api,
  ApiError,
  type CodeRunCaseResult,
  type CodeRunMode,
  type CodeRunResponse,
  type EntityId
} from "@aioj/api-client";
import { Badge, Button, cn } from "@aioj/ui-react";
import { textareaClass } from "./Common";
import { useI18n } from "../lib/i18n";
import { formatMemory } from "../lib/format";

/** Plan 15.5: the editor's code on the public samples or a custom input. Nothing is submitted. */
const MAX_INPUT_BYTES = 1024 * 1024;
/** A second click this soon after the first is the same click. */
const CLICK_DEBOUNCE_MILLIS = 300;

type Tone = "blue" | "green" | "amber" | "red" | "neutral";

const VERDICT_TONES: Record<string, Tone> = {
  AC: "green",
  OK: "green",
  WA: "red",
  CE: "amber",
  TLE: "amber",
  MLE: "amber",
  OLE: "amber",
  RE: "red",
  SE: "neutral"
};

const encoder = new TextEncoder();

function byteLength(text: string) {
  return encoder.encode(text).length;
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

export function CodeRunPanel({
  problemId,
  language,
  code,
  samplesCount,
  contestRunId,
  contestProblemId,
  disabled = false
}: {
  problemId: EntityId;
  language: string;
  code: string;
  samplesCount: number;
  contestRunId?: EntityId;
  contestProblemId?: EntityId;
  disabled?: boolean;
}) {
  const { t } = useI18n();
  const [mode, setMode] = React.useState<CodeRunMode>(samplesCount > 0 ? "SAMPLES" : "CUSTOM");
  const [input, setInput] = React.useState("");
  const [expectedOutput, setExpectedOutput] = React.useState("");
  const [showExpected, setShowExpected] = React.useState(false);
  const [result, setResult] = React.useState<CodeRunResponse | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const lastClickRef = React.useRef(0);
  const outcomeRef = React.useRef<HTMLDivElement | null>(null);

  const limitsQuery = useQuery({
    queryKey: ["code-run-limits"],
    queryFn: () => api.codeRunLimits(),
    staleTime: 60_000
  });
  const limits = limitsQuery.data;
  const switchedOff = limits ? !limits.enabled : false;

  const inputBytes = React.useMemo(() => byteLength(input), [input]);
  const expectedBytes = React.useMemo(() => byteLength(expectedOutput), [expectedOutput]);
  const tooLarge = mode === "CUSTOM" && (inputBytes > MAX_INPUT_BYTES || (showExpected && expectedBytes > MAX_INPUT_BYTES));

  const runMutation = useMutation({
    mutationFn: () => api.runCode({
      problemId,
      language,
      sourceCode: code,
      mode,
      input: mode === "CUSTOM" ? input : undefined,
      expectedOutput: mode === "CUSTOM" && showExpected && expectedOutput ? expectedOutput : undefined,
      contestRunId,
      contestProblemId
    }),
    onMutate: () => {
      setError(null);
    },
    onSuccess: (response) => {
      setResult(response);
    },
    onError: (caught) => {
      setResult(null);
      setError(caught instanceof ApiError ? caught.userMessage : t("codeRun.failed"));
    }
  });

  // The panel may scroll on wide screens: bring a new result or error into its view.
  React.useEffect(() => {
    if (result || error) outcomeRef.current?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }, [result, error]);

  const canRun = !disabled && !switchedOff && Boolean(code.trim()) && !tooLarge && !runMutation.isPending
    && (mode === "CUSTOM" || samplesCount > 0);

  function run() {
    const now = Date.now();
    if (now - lastClickRef.current < CLICK_DEBOUNCE_MILLIS || runMutation.isPending || !canRun) return;
    lastClickRef.current = now;
    runMutation.mutate();
  }

  return (
    // On wide screens the editor pane has a fixed height: the panel scrolls inside its share so the editor keeps the rest.
    <section className="mt-3 shrink-0 rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] xl:max-h-[45%] xl:overflow-y-auto" aria-label={t("codeRun.title")}>
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-t-xl px-3 py-2 xl:sticky xl:top-0 xl:z-10 xl:bg-[var(--oj-surface-muted)]">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("codeRun.title")}</h3>
          <div className="flex rounded-lg border border-[var(--oj-border)] bg-white p-0.5" role="tablist" aria-label={t("codeRun.modeLabel")}>
            {([["SAMPLES", t("codeRun.samples")], ["CUSTOM", t("codeRun.custom")]] as const).map(([value, label]) => (
              <button
                key={value}
                type="button"
                role="tab"
                aria-selected={mode === value}
                className={cn(
                  "h-7 rounded-md px-2.5 text-xs font-medium outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]",
                  mode === value ? "bg-[var(--oj-primary-soft)] text-[var(--oj-primary)]" : "text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)]"
                )}
                onClick={() => setMode(value)}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
        <Button variant="outline" size="sm" disabled={!canRun} onClick={run}>
          {runMutation.isPending ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : <Play className="size-4" aria-hidden="true" />}
          {runMutation.isPending ? t("codeRun.running") : t("codeRun.run")}
        </Button>
      </div>

      <div className="space-y-2 border-t border-[var(--oj-border-soft)] px-3 py-2">
        {mode === "SAMPLES" ? (
          <p className="text-xs text-[var(--oj-ink-muted)]">
            {samplesCount > 0 ? t("codeRun.sampleCount", { count: samplesCount }) : t("codeRun.noSamples")}
          </p>
        ) : (
          <div className="space-y-2">
            <label className="block">
              <span className="mb-1 flex items-center justify-between text-xs font-medium text-[var(--oj-ink)]">
                {t("codeRun.input")}
                <span className={cn("font-normal", inputBytes > MAX_INPUT_BYTES ? "text-red-600" : "text-[var(--oj-ink-muted)]")}>
                  {t("codeRun.bytes", { used: formatBytes(inputBytes) })}
                </span>
              </span>
              <textarea
                className={cn(textareaClass, "min-h-20 resize-y font-mono text-xs")}
                rows={3}
                spellCheck={false}
                value={input}
                aria-invalid={inputBytes > MAX_INPUT_BYTES}
                onChange={(event) => setInput(event.target.value)}
              />
            </label>
            {showExpected ? (
              <label className="block">
                <span className="mb-1 flex items-center justify-between text-xs font-medium text-[var(--oj-ink)]">
                  {t("codeRun.expectedOutput")}
                  <span className={cn("font-normal", expectedBytes > MAX_INPUT_BYTES ? "text-red-600" : "text-[var(--oj-ink-muted)]")}>
                    {t("codeRun.bytes", { used: formatBytes(expectedBytes) })}
                  </span>
                </span>
                <textarea
                  className={cn(textareaClass, "min-h-16 resize-y font-mono text-xs")}
                  rows={2}
                  spellCheck={false}
                  value={expectedOutput}
                  aria-invalid={expectedBytes > MAX_INPUT_BYTES}
                  onChange={(event) => setExpectedOutput(event.target.value)}
                />
              </label>
            ) : (
              <button type="button" className="text-xs font-medium text-[var(--oj-primary)] hover:underline" onClick={() => setShowExpected(true)}>
                {t("codeRun.addExpectedOutput")}
              </button>
            )}
            {tooLarge ? <p className="text-xs text-red-600">{t("codeRun.tooLarge")}</p> : null}
          </div>
        )}
        <p className="text-xs leading-5 text-[var(--oj-ink-muted)]">
          {switchedOff ? t("codeRun.switchedOff") : t("codeRun.notCounted")}
          {limits && !switchedOff ? ` ${t("codeRun.limits", { perMinute: limits.perMinuteLimit, perDay: limits.perDayLimit })}` : ""}
        </p>
        <div ref={outcomeRef} className="space-y-2 empty:hidden">
          {error ? <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700" role="alert">{error}</p> : null}
          {result ? <CodeRunResult result={result} onClose={() => setResult(null)} /> : null}
        </div>
      </div>
    </section>
  );
}

function CodeRunResult({ result, onClose }: { result: CodeRunResponse; onClose: () => void }) {
  const { t } = useI18n();
  const compared = result.verdict !== "OK";
  return (
    <div className="rounded-lg border border-[var(--oj-border)] bg-white" aria-live="polite">
      <div className="flex items-center justify-between gap-2 border-b border-[var(--oj-border-soft)] px-3 py-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2 text-xs">
          <Badge tone={VERDICT_TONES[result.verdict] ?? "neutral"}>{t(`codeRun.verdict.${result.verdict}`, undefined, result.verdict)}</Badge>
          {compared && result.mode === "SAMPLES" ? (
            <span className="text-[var(--oj-ink-muted)]">{t("codeRun.passed", { passed: result.passed, total: result.total })}</span>
          ) : null}
        </div>
        <button
          type="button"
          className="grid size-7 place-items-center rounded-md text-[var(--oj-ink-muted)] outline-none hover:bg-[var(--oj-surface-muted)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
          aria-label={t("codeRun.close")}
          onClick={onClose}
        >
          <X className="size-4" aria-hidden="true" />
        </button>
      </div>
      <div className="max-h-72 space-y-2 overflow-y-auto p-3 xl:max-h-none xl:overflow-visible">
        {result.compileOutput ? (
          <div>
            <p className="mb-1 text-xs font-medium text-[var(--oj-ink)]">{t("codeRun.compileOutput")}</p>
            <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md bg-[var(--oj-surface-muted)] p-2 font-mono text-xs text-[var(--oj-ink)]">{result.compileOutput}</pre>
          </div>
        ) : null}
        {result.verdict === "CE" ? null : result.cases.map((item) => (
          <CodeRunCase key={item.index} item={item} custom={result.mode === "CUSTOM"} />
        ))}
      </div>
    </div>
  );
}

function CodeRunCase({ item, custom }: { item: CodeRunCaseResult; custom: boolean }) {
  const { t } = useI18n();
  return (
    <div className="rounded-md border border-[var(--oj-border-soft)] p-2">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span className="font-medium text-[var(--oj-ink)]">{custom ? t("codeRun.customCase") : t("codeRun.case", { index: item.index })}</span>
        <Badge tone={VERDICT_TONES[item.verdict] ?? "neutral"}>{t(`codeRun.verdict.${item.verdict}`, undefined, item.verdict)}</Badge>
        <span className="text-[var(--oj-ink-muted)]">{t("codeRun.time", { value: item.timeMillis })}</span>
        <span className="text-[var(--oj-ink-muted)]">{formatMemory(item.memoryKb)}</span>
      </div>
      {item.firstDifference ? (
        <div className="mt-2 rounded-md bg-red-50 px-2 py-1.5 text-xs text-red-800">
          <p>{t("codeRun.firstDifference", { line: item.firstDifference.line, column: item.firstDifference.column })}</p>
          <p className="mt-1 break-words font-mono">{t("codeRun.expected")}: {item.firstDifference.expected ?? t("codeRun.missingLine")}</p>
          <p className="break-words font-mono">{t("codeRun.actual")}: {item.firstDifference.actual ?? t("codeRun.missingLine")}</p>
        </div>
      ) : null}
      <p className="mt-2 text-xs font-medium text-[var(--oj-ink)]">{t("codeRun.output")}</p>
      <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md bg-[var(--oj-surface-muted)] p-2 font-mono text-xs text-[var(--oj-ink)]">
        {item.stdout || t("codeRun.emptyOutput")}
      </pre>
      {item.stdoutTruncated ? <p className="mt-1 text-xs text-amber-700">{t("codeRun.truncated")}</p> : null}
      {item.stderr ? (
        <>
          <p className="mt-2 text-xs font-medium text-[var(--oj-ink)]">{t("codeRun.stderr")}</p>
          <pre className="mt-1 max-h-32 overflow-auto whitespace-pre-wrap break-words rounded-md bg-[var(--oj-surface-muted)] p-2 font-mono text-xs text-[var(--oj-ink)]">{item.stderr}</pre>
        </>
      ) : null}
    </div>
  );
}
