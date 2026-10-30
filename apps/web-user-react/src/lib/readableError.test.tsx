import { describe, expect, it } from "vitest";
import { readableJudgeMessage, readableStoredError, readableStreamError } from "./readableError";

describe("readableJudgeMessage", () => {
  it("words a bare sandbox status and leaves other judge text alone", () => {
    expect(readableJudgeMessage("Signalled", "RUNTIME_ERROR", "zh-CN", "运行错误")).toBe("程序被信号终止（例如段错误或除以零）");
    expect(readableJudgeMessage(" Nonzero Exit Status ", "RUNTIME_ERROR", "en-US", "Runtime Error")).toBe("Exited with a non-zero status");
    expect(readableJudgeMessage("Line 3: expected 4, got 5", "WRONG_ANSWER", "zh-CN", "答案错误")).toBe("Line 3: expected 4, got 5");
    expect(readableJudgeMessage("  ", "WRONG_ANSWER", "zh-CN", "答案错误")).toBe("答案错误");
  });

  // T2.6 (plan S7): the server sends a system error as its key.
  it("translates a system error key and never shows older free text as it is", () => {
    expect(readableJudgeMessage("judge.checkerUnavailable", "SYSTEM_ERROR", "zh-CN", "系统错误"))
      .toBe("这道题的自定义检查器暂时无法使用，请联系老师检查测试包。");
    expect(readableJudgeMessage("judge.sandboxUnavailable", "SYSTEM_ERROR", "en-US", "System Error"))
      .toBe("The code sandbox is temporarily unavailable. Try again later or contact an administrator.");
    expect(readableJudgeMessage("Sandbox execution failed: Sandbox HTTP 500 on POST /run", "SYSTEM_ERROR", "zh-CN", "系统错误"))
      .not.toContain("POST /run");
  });
});

describe("stored and streamed errors", () => {
  it("translates stored keys and falls back for unknown ones", () => {
    expect(readableStoredError("request.timeout", "zh-CN", "失败", "ai")).not.toBe("request.timeout");
    expect(readableStoredError("ai.providerFailed", "en-US", "Failed", "ai")).not.toContain("ai.providerFailed");
    expect(readableStoredError("unknown.keyThatDoesNotExist", "zh-CN", "失败", "ai")).not.toBe("unknown.keyThatDoesNotExist");
  });

  it("reads the chat stream's structured error and never shows raw stream text", () => {
    const structured = JSON.stringify({ code: 50000, errorKey: "ai.providerFailed", message: "AI 服务调用失败" });
    expect(readableStreamError(structured, "zh-CN", "回答失败")).not.toBe("回答失败");
    expect(readableStreamError("AI provider call failed: http://provider.internal/canary", "zh-CN", "回答失败"))
      .toBe("回答失败");
    expect(readableStreamError(undefined, "en-US", "Answer failed")).toBe("Answer failed");
  });
});
