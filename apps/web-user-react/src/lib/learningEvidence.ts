import type { AiLearningProfileEvidenceResponse } from "@aioj/api-client";

/** Learning-profile evidence as the profile page shows it: redacted, parsed and grouped by submission. */

export function displaySafeEvidence(value?: string | null) {
  if (!value) return "";
  const withoutFences = value.replace(/```[\s\S]*?```/g, "");
  const kept: string[] = [];
  let skippingRawOutput = false;
  for (const rawLine of withoutFences.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n")) {
    const line = rawLine.trim();
    const lower = line.toLowerCase();
    if (/^(stdout|stderr|stdoutexcerpt|stderrexcerpt)\s*[:=]/i.test(line)) {
      skippingRawOutput = true;
      continue;
    }
    if (skippingRawOutput) {
      if (!line || /^[A-Za-z][A-Za-z0-9 _-]{0,40}:/.test(line)) {
        skippingRawOutput = false;
      } else {
        continue;
      }
    }
    if (/(sk-[a-z0-9_-]{4,}|bearer\s+[a-z0-9._-]{8,}|(token|secret|password|api[_-]?key)\s*[:=])/i.test(line)) {
      continue;
    }
    if (/^(#include|using namespace|int main|public class|public static void main|def main|import sys|if __name__|cin >>|cout <<)/i.test(line)) {
      continue;
    }
    if (line) kept.push(line);
  }
  return kept.join("\n");
}

const SUBMISSION_EVIDENCE_SOURCES = new Set(["SUBMISSION", "JUDGED_SUBMISSION"]);
const EVIDENCE_TAG_FIELDS = new Set(["rootCauseTags", "algorithmTags", "bugPatternTags", "complexityTags"]);
/** Keys of the submission analysis summary (ai-service); other lines are free text. */
const EVIDENCE_FIELDS = new Set([...EVIDENCE_TAG_FIELDS, "submission", "status", "language", "codeHash", "masteryEvidence",
  "profileEligible", "structuredSummary", "nextSteps", "evidenceItems", "judgeMessage"]);

export type LearningEvidenceDetail = {
  status: string;
  language: string;
  tags: string[];
  judgement: string;
  nextSteps: string;
  cases: string;
  judgeMessage: string;
  freeText: string;
};

export type LearningEvidenceEntry = { text: string; count: number; detail: LearningEvidenceDetail };

export type LearningEvidenceGroup = {
  key: string;
  sourceId: string | null;
  submissionId: string | null;
  evidenceTypes: string[];
  sourceTypes: string[];
  entries: LearningEvidenceEntry[];
  confidence: number | null;
  createdAt?: string | null;
};

export function parseLearningEvidence(text: string): LearningEvidenceDetail {
  const detail: LearningEvidenceDetail = { status: "", language: "", tags: [], judgement: "", nextSteps: "", cases: "", judgeMessage: "", freeText: "" };
  const freeText: string[] = [];
  for (const line of text.split("\n")) {
    const separator = line.indexOf("=");
    const key = separator > 0 ? line.slice(0, separator).trim() : "";
    if (!EVIDENCE_FIELDS.has(key)) {
      if (line.trim()) freeText.push(line.trim());
      continue;
    }
    const value = line.slice(separator + 1).trim();
    if (EVIDENCE_TAG_FIELDS.has(key)) {
      for (const tag of value.split(/[,，]/).map((item) => item.trim()).filter(Boolean)) {
        if (!detail.tags.includes(tag)) detail.tags.push(tag);
      }
    } else if (key === "status") detail.status = value;
    else if (key === "language") detail.language = value;
    else if (key === "structuredSummary") detail.judgement = value;
    else if (key === "nextSteps") detail.nextSteps = value;
    else if (key === "evidenceItems") detail.cases = value;
    else if (key === "judgeMessage") detail.judgeMessage = value;
  }
  detail.freeText = freeText.join("\n");
  return detail;
}

/** One group per submission (its analysis and judged signals together); other evidence stays on its own. */
export function groupLearningEvidence(evidence: AiLearningProfileEvidenceResponse[]): LearningEvidenceGroup[] {
  const groups = new Map<string, LearningEvidenceGroup>();
  for (const item of evidence) {
    const sourceId = item.sourceId == null ? null : String(item.sourceId);
    const sourceType = item.sourceType ?? "";
    const submissionId = sourceId && SUBMISSION_EVIDENCE_SOURCES.has(sourceType.toUpperCase()) ? sourceId : null;
    const key = submissionId ? `submission:${submissionId}` : `evidence:${item.id}`;
    let group = groups.get(key);
    if (!group) {
      group = { key, sourceId, submissionId, evidenceTypes: [], sourceTypes: [], entries: [], confidence: null, createdAt: item.createdAt };
      groups.set(key, group);
    }
    if (item.evidenceType && !group.evidenceTypes.includes(item.evidenceType)) group.evidenceTypes.push(item.evidenceType);
    if (sourceType && !group.sourceTypes.includes(sourceType)) group.sourceTypes.push(sourceType);
    const text = displaySafeEvidence(item.summary);
    if (text) {
      const existing = group.entries.find((entry) => entry.text === text);
      if (existing) existing.count += 1;
      else group.entries.push({ text, count: 1, detail: parseLearningEvidence(text) });
    }
    if (item.confidence != null && (group.confidence == null || item.confidence > group.confidence)) group.confidence = item.confidence;
  }
  return Array.from(groups.values());
}

/** How many linked submissions ended in each status, most frequent first. */
export function evidenceStatusCounts(groups: LearningEvidenceGroup[]) {
  const counts = new Map<string, number>();
  for (const group of groups) {
    const status = group.submissionId ? group.entries.find((entry) => entry.detail.status)?.detail.status : undefined;
    if (status) counts.set(status, (counts.get(status) ?? 0) + 1);
  }
  return Array.from(counts.entries()).sort((left, right) => right[1] - left[1]);
}
