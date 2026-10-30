import { ApiError, type SubmissionStatus } from "@aioj/api-client";
import { resolveApiErrorMessageForLocale, resolveStoredErrorMessageForLocale, translate, type Locale } from "@aioj/i18n/vanilla";

type StoredErrorSource = "ai" | "judge" | "operation" | "testcase";

export function readableCaughtError(caught: unknown, locale: Locale, fallback: string) {
  if (caught instanceof ApiError) {
    return caught.userMessage;
  }
  if (caught instanceof Error) {
    return resolveStoredErrorMessageForLocale(locale, caught.message);
  }
  return fallback;
}

export function readableStoredError(
  value: string | null | undefined,
  locale: Locale,
  fallback: string,
  source?: StoredErrorSource
) {
  if (!value?.trim()) {
    return fallback;
  }
  return resolveStoredErrorMessageForLocale(locale, value, source);
}

/** A chat stream's error event: {code, errorKey, message} (T2.6, plan S7); anything else reads as the fallback. */
export function readableStreamError(data: string | null | undefined, locale: Locale, fallback: string) {
  try {
    const parsed = JSON.parse(data ?? "") as { code?: unknown; errorKey?: unknown; message?: unknown };
    if (parsed && typeof parsed.errorKey === "string") {
      const code = typeof parsed.code === "number" ? parsed.code : 50000;
      return resolveApiErrorMessageForLocale(code, typeof parsed.message === "string" ? parsed.message : "", locale, {
        errorKey: parsed.errorKey
      });
    }
  } catch {
    // Not the structured event; never show raw stream text.
  }
  return fallback;
}

export function readableJudgeMessage(
  value: string | null | undefined,
  status: SubmissionStatus,
  locale: Locale,
  fallbackStatus: string
) {
  if (status === "SYSTEM_ERROR") {
    return resolveStoredErrorMessageForLocale(locale, value, "judge");
  }
  if (!value?.trim()) {
    return fallbackStatus;
  }
  // A bare sandbox status ("Signalled", "Nonzero Exit Status") reads as a sentence; other text stays as is.
  const rawStatus = value.trim().toUpperCase().replace(/[^A-Z]+/g, "_");
  return translate(locale, `judgeRawStatus.${rawStatus}`, undefined, value);
}
