import { ApiError, type SubmissionStatus } from "@aioj/api-client";
import { resolveStoredErrorMessageForLocale, translate, type Locale } from "@aioj/i18n/vanilla";

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
