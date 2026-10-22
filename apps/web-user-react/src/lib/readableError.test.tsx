import { describe, expect, it } from "vitest";
import { readableJudgeMessage } from "./readableError";

describe("readableJudgeMessage", () => {
  it("words a bare sandbox status and leaves other judge text alone", () => {
    expect(readableJudgeMessage("Signalled", "RUNTIME_ERROR", "zh-CN", "运行错误")).toBe("程序被信号终止（例如段错误或除以零）");
    expect(readableJudgeMessage(" Nonzero Exit Status ", "RUNTIME_ERROR", "en-US", "Runtime Error")).toBe("Exited with a non-zero status");
    expect(readableJudgeMessage("Line 3: expected 4, got 5", "WRONG_ANSWER", "zh-CN", "答案错误")).toBe("Line 3: expected 4, got 5");
    expect(readableJudgeMessage("  ", "WRONG_ANSWER", "zh-CN", "答案错误")).toBe("答案错误");
  });
});
