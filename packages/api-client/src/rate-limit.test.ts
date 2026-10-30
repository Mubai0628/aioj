import { afterEach, describe, expect, it, vi } from "vitest";
import { resolveApiErrorMessageForLocale } from "@aioj/i18n/vanilla";
import { ApiError, api } from "./index";

// T2.2: every limit answers 429 with Retry-After and an error key; the page says which limit and when.
function tooMany(body: Record<string, unknown>, retryAfter: string) {
  return new Response(JSON.stringify({ code: 42900, message: "x", data: null, details: null, traceId: "t", ...body }),
    { status: 429, headers: { "Content-Type": "application/json", "Retry-After": retryAfter } });
}

async function submitAndCatch(): Promise<ApiError> {
  try {
    await api.submit({ problemId: "1", language: "cpp", code: "int main(){}" });
  } catch (error) {
    return error as ApiError;
  }
  throw new Error("expected the submit to be refused");
}

describe("rate-limited responses", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("keeps the seconds the body gives and reads the header when the body has none", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(
      tooMany({ errorKey: "submission.rateLimited", errorParams: { retryAfterSeconds: "30" } }, "30"));
    const fromBody = await submitAndCatch();
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(tooMany({ errorKey: "request.tooMany" }, "7"));
    const fromHeader = await submitAndCatch();

    expect(fromBody.errorKey).toBe("submission.rateLimited");
    expect(fromBody.errorParams?.retryAfterSeconds).toBe("30");
    expect(fromHeader.errorParams?.retryAfterSeconds).toBe("7");
  });

  it("names the limit and the wait in seconds, minutes or hours", () => {
    const say = (errorKey: string, seconds: string, locale: "zh-CN" | "en-US" = "zh-CN") =>
      resolveApiErrorMessageForLocale(42900, "x", locale, { errorKey, errorParams: { retryAfterSeconds: seconds } });

    expect(say("request.tooManyFromNetwork", "2")).toBe("同一网络的请求过多，请稍后再试。 约 2 秒后再试。");
    expect(say("auth.loginLocked", "900")).toBe("登录失败次数过多，请稍后再试。 约 15 分钟后再试。");
    expect(say("codeRun.dayLimit", "36000")).toContain("约 10 小时后再试。");
    expect(say("ai.turnConcurrencyLimit", "5", "en-US"))
      .toBe("You already have an AI answer in progress. Wait for it to finish before asking again. Try again in about 5 s.");
    expect(resolveApiErrorMessageForLocale(42900, "x", "zh-CN", { errorKey: "ai.monthlyQuotaExceeded" }))
      .not.toContain("后再试。 约");
  });
});
