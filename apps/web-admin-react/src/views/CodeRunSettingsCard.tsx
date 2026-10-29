import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Save } from "lucide-react";
import { api, type CodeRunSettings, type CodeRunSettingsPayload } from "@aioj/api-client";
import { Badge, Button } from "@aioj/ui-react";
import { ErrorPanel, Field, LoadingPanel, inputClass } from "../components/Common";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { readableCaughtError } from "../lib/readableError";

/** Plan 15.5: the limits of the problem page's "Run" button; only an administrator changes them. */
const FIELDS = [
  { key: "maxConcurrency", min: 1, max: 16 },
  { key: "perMinuteLimit", min: 1, max: 600 },
  { key: "perDayLimit", min: 1, max: 100000 },
  { key: "minIntervalMillis", min: 0, max: 60000 }
] as const;

type NumberField = (typeof FIELDS)[number]["key"];
type FormState = { enabled: boolean } & Record<NumberField, string>;

function formFrom(settings: CodeRunSettings): FormState {
  return {
    enabled: settings.enabled,
    maxConcurrency: String(settings.maxConcurrency),
    perMinuteLimit: String(settings.perMinuteLimit),
    perDayLimit: String(settings.perDayLimit),
    minIntervalMillis: String(settings.minIntervalMillis)
  };
}

function fieldError(form: FormState, field: (typeof FIELDS)[number]) {
  const raw = form[field.key].trim();
  const value = Number(raw);
  return raw === "" || !Number.isInteger(value) || value < field.min || value > field.max;
}

export function CodeRunSettingsCard() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const settingsQuery = useQuery({
    queryKey: ["admin-code-run-settings"],
    queryFn: () => api.adminCodeRunSettings()
  });
  const [form, setForm] = React.useState<FormState | null>(null);

  React.useEffect(() => {
    if (settingsQuery.data) setForm(formFrom(settingsQuery.data));
  }, [settingsQuery.data]);

  const saveMutation = useMutation({
    mutationFn: (payload: CodeRunSettingsPayload) => api.updateAdminCodeRunSettings(payload),
    onSuccess: async () => {
      toast.success(t("codeRunSettings.saved"));
      await queryClient.invalidateQueries({ queryKey: ["admin-code-run-settings"] });
    },
    onError: (caught) => {
      toast.error(readableCaughtError(caught, locale, t("common.errorFallback")));
    }
  });

  const baseline = settingsQuery.data ? JSON.stringify(formFrom(settingsQuery.data)) : "";
  const dirty = form !== null && JSON.stringify(form) !== baseline;
  const invalid = form === null || FIELDS.some((field) => fieldError(form, field));

  function save() {
    if (!form || invalid) return;
    saveMutation.mutate({
      enabled: form.enabled,
      maxConcurrency: Number(form.maxConcurrency),
      perMinuteLimit: Number(form.perMinuteLimit),
      perDayLimit: Number(form.perDayLimit),
      minIntervalMillis: Number(form.minIntervalMillis)
    });
  }

  return (
    <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4" aria-labelledby="code-run-settings-title">
      <div className="flex flex-col gap-3 border-b border-[var(--oj-border-soft)] pb-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h2 id="code-run-settings-title" className="text-base font-semibold text-[var(--oj-ink)]">{t("codeRunSettings.title")}</h2>
            {form ? (
              <Badge tone={form.enabled ? "green" : "amber"}>{form.enabled ? t("codeRunSettings.on") : t("codeRunSettings.off")}</Badge>
            ) : null}
          </div>
          <p className="mt-1 max-w-[86ch] text-sm leading-6 text-[var(--oj-ink-muted)]">{t("codeRunSettings.description")}</p>
        </div>
        <Button disabled={!dirty || invalid || saveMutation.isPending} onClick={save}>
          {saveMutation.isPending ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : <Save className="size-4" aria-hidden="true" />}
          {t("common.save")}
        </Button>
      </div>

      {settingsQuery.isLoading || (!form && !settingsQuery.isError) ? (
        <div className="pt-4"><LoadingPanel label={t("codeRunSettings.loading")} /></div>
      ) : settingsQuery.isError || !form ? (
        <div className="pt-4">
          <ErrorPanel
            title={readableCaughtError(settingsQuery.error, locale, t("codeRunSettings.loadFailed"))}
            action={<Button variant="outline" onClick={() => void settingsQuery.refetch()}>{t("common.refresh")}</Button>}
          />
        </div>
      ) : (
        <div className="grid gap-4 pt-4 md:grid-cols-2 xl:grid-cols-5">
          <label className="flex min-h-10 items-center gap-2 rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 text-sm text-[var(--oj-ink)] md:col-span-2 xl:col-span-1">
            <input
              type="checkbox"
              checked={form.enabled}
              onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
            />
            {t("codeRunSettings.enabled")}
          </label>
          {FIELDS.map((field) => (
            <Field
              key={field.key}
              label={t(`codeRunSettings.${field.key}`)}
              hint={fieldError(form, field)
                ? t("codeRunSettings.range", { min: field.min, max: field.max })
                : t(`codeRunSettings.${field.key}Hint`)}
            >
              <input
                className={inputClass}
                inputMode="numeric"
                aria-invalid={fieldError(form, field)}
                value={form[field.key]}
                onChange={(event) => setForm({ ...form, [field.key]: event.target.value })}
              />
            </Field>
          ))}
        </div>
      )}
    </section>
  );
}
