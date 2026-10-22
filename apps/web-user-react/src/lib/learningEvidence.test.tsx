import { describe, expect, it } from "vitest";
import type { AiLearningProfileEvidenceResponse } from "@aioj/api-client";
import { evidenceStatusCounts, groupLearningEvidence, parseLearningEvidence } from "./learningEvidence";

function evidence(id: string, sourceType: string, sourceId: string | null, summary: string, extra: Partial<AiLearningProfileEvidenceResponse> = {}): AiLearningProfileEvidenceResponse {
  return { id, profileId: "p1", evidenceType: "SUBMISSION_ANALYSIS", sourceType, sourceId, summary, confidence: 0.5, createdAt: "2026-10-22T10:00:00Z", ...extra };
}

const analysis = [
  "submission=901",
  "status=WRONG_ANSWER",
  "language=cpp",
  "codeHash=abc",
  "rootCauseTags=off_by_one,boundary",
  "algorithmTags=binary_search，boundary",
  "structuredSummary=二分右端点少算了一位",
  "nextSteps=先手推 n=1 的边界",
  "evidenceItems=#3 WRONG_ANSWER",
  "judgeMessage=Wrong answer on test 3"
].join("\n");

describe("learning evidence", () => {
  it("parses the submission analysis fields and keeps other lines as free text", () => {
    const detail = parseLearningEvidence(`${analysis}\nn=5 时会溢出`);

    expect(detail.status).toBe("WRONG_ANSWER");
    expect(detail.language).toBe("cpp");
    expect(detail.tags).toEqual(["off_by_one", "boundary", "binary_search"]);
    expect(detail.judgement).toBe("二分右端点少算了一位");
    expect(detail.nextSteps).toBe("先手推 n=1 的边界");
    expect(detail.cases).toBe("#3 WRONG_ANSWER");
    expect(detail.judgeMessage).toBe("Wrong answer on test 3");
    expect(detail.freeText).toBe("n=5 时会溢出");
  });

  it("groups a submission's analysis and judged signal, and keeps other evidence apart", () => {
    const groups = groupLearningEvidence([
      evidence("e1", "SUBMISSION", "901", analysis, { confidence: 0.6 }),
      evidence("e2", "JUDGED_SUBMISSION", "901", "连续两次答案错误", { evidenceType: "PROFILE_SIGNAL", confidence: 0.8 }),
      evidence("e3", "SUBMISSION", "901", analysis),
      evidence("e4", "CHAT_TURN", "77", "提问时提到边界", { evidenceType: "PROFILE_SIGNAL" }),
      evidence("e5", "SUBMISSION", "902", "#include <bits/stdc++.h>")
    ]);

    expect(groups.map((group) => group.key)).toEqual(["submission:901", "evidence:e4", "submission:902"]);
    const [first, chat, redacted] = groups;
    expect(first.evidenceTypes).toEqual(["SUBMISSION_ANALYSIS", "PROFILE_SIGNAL"]);
    expect(first.sourceTypes).toEqual(["SUBMISSION", "JUDGED_SUBMISSION"]);
    expect(first.confidence).toBe(0.8);
    expect(first.entries.map((entry) => entry.count)).toEqual([2, 1]);
    expect(chat.submissionId).toBeNull();
    expect(chat.sourceId).toBe("77");
    expect(redacted.entries).toEqual([]);
  });

  it("counts linked submissions by status, most frequent first", () => {
    const groups = groupLearningEvidence([
      evidence("e1", "SUBMISSION", "1", "status=TIME_LIMIT_EXCEEDED"),
      evidence("e2", "SUBMISSION", "2", "status=WRONG_ANSWER"),
      evidence("e3", "SUBMISSION", "3", "status=WRONG_ANSWER"),
      evidence("e4", "CHAT_TURN", "4", "status=WRONG_ANSWER")
    ]);

    expect(evidenceStatusCounts(groups)).toEqual([["WRONG_ANSWER", 2], ["TIME_LIMIT_EXCEEDED", 1]]);
  });
});
