import { beforeEach, describe, expect, it, vi } from "vitest";
import { api, authStore, newIdempotencyKey, type TokenResponse } from "./index";

function tokens(accessToken: string, refreshToken: string): TokenResponse {
  return {
    accessToken,
    refreshToken,
    tokenType: "Bearer",
    expiresAt: "2099-01-01T00:00:00Z",
    userId: "u1",
    account: "alice",
    displayName: "Alice",
    roles: ["STUDENT"],
    passwordResetRequired: false
  };
}

function apiResponse<T>(data: T, status = 200) {
  return new Response(JSON.stringify({ code: 0, message: "ok", data, details: null, traceId: "t", timestamp: "2026-01-01T00:00:00Z" }),
    { status, headers: { "Content-Type": "application/json" } });
}

function unauthorized() {
  return new Response(JSON.stringify({ code: 40100, message: "error", data: null, details: null, traceId: "t", timestamp: "2026-01-01T00:00:00Z", errorKey: "auth.required", errorParams: null }),
    { status: 401, headers: { "Content-Type": "application/json" } });
}

describe("submission idempotency in api-client", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it("resends a submit with the same Idempotency-Key after refreshing the token", async () => {
    authStore.save(tokens("old-access", "old-refresh"));
    const submitKeys: (string | null)[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();
      const headers = new Headers(init?.headers);
      if (url.endsWith("/api/v1/auth/refresh")) {
        return Promise.resolve(apiResponse(tokens("new-access", "new-refresh")));
      }
      if (url.endsWith("/api/v1/submissions")) {
        submitKeys.push(headers.get("Idempotency-Key"));
        return Promise.resolve(headers.get("Authorization") === "Bearer old-access"
          ? unauthorized()
          : apiResponse({ id: "s1", status: "QUEUED" }));
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    const submission = await api.submit({ problemId: "p1", language: "cpp", code: "int main(){}" });

    expect(submission.id).toBe("s1");
    expect(submitKeys).toHaveLength(2);
    expect(submitKeys[0]).toBeTruthy();
    expect(submitKeys[1]).toBe(submitKeys[0]);
  });

  it("uses a caller-provided key and generates distinct keys otherwise", async () => {
    authStore.save(tokens("access", "refresh"));
    const keys: (string | null)[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      keys.push(new Headers(init?.headers).get("Idempotency-Key"));
      return Promise.resolve(apiResponse({ id: "s1", status: "QUEUED" }));
    });

    await api.submit({ problemId: "p1", language: "cpp", code: "a" }, { idempotencyKey: "fixed-key" });
    await api.submit({ problemId: "p1", language: "cpp", code: "a" });
    await api.submit({ problemId: "p1", language: "cpp", code: "a" });

    expect(keys[0]).toBe("fixed-key");
    expect(keys[1]).not.toBe(keys[2]);
    expect(newIdempotencyKey()).toMatch(/^[A-Za-z0-9-]{16,}$/);
  });
});
