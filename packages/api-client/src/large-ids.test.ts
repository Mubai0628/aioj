import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "./index";

// S6: a 19-digit snowflake id read as a JSON number comes back with its last digits wrong.
function ok(raw: string) {
  return new Response(`{"code":0,"message":"ok","data":${raw},"details":null,"traceId":"t"}`,
    { status: 200, headers: { "Content-Type": "application/json" } });
}

describe("large integer ids", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("keeps ids under id, ...Id and ...By keys and in ...Ids arrays exactly", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(ok(`{"records":[{
      "id":1990011223344556677,"contestRunId":1990011223344556678,"reviewedBy":1990011223344556679,
      "evidence":{"participantIds":[1990011223344556680, -1990011223344556681],"participantCount":2},
      "createdBy":12,"score":1234567890123456789.5,"note":"1990011223344556682"}],"total":1,"page":1,"pageSize":20}`));

    const page = await api.contestFairnessAlerts("1", "2");
    const alert = page.records[0] as unknown as Record<string, unknown>;

    expect(alert.id).toBe("1990011223344556677");
    expect(alert.contestRunId).toBe("1990011223344556678");
    expect(alert.reviewedBy).toBe("1990011223344556679");
    expect((alert.evidence as Record<string, unknown>).participantIds).toEqual(["1990011223344556680", "-1990011223344556681"]);
    expect((alert.evidence as Record<string, unknown>).participantCount).toBe(2);
    expect(alert.createdBy).toBe(12);
    expect(typeof alert.score).toBe("number");
    expect(alert.note).toBe("1990011223344556682");
  });

  it("leaves arrays that hold anything but numbers alone", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(ok(`{"records":[{"id":"7","evidence":{
      "problemIds":["a,1990011223344556677,b"],"submissionIds":[]}}],"total":1,"page":1,"pageSize":20}`));

    const page = await api.contestFairnessAlerts("1", "2");
    const evidence = (page.records[0] as unknown as Record<string, Record<string, unknown>>).evidence;

    expect(evidence.problemIds).toEqual(["a,1990011223344556677,b"]);
    expect(evidence.submissionIds).toEqual([]);
  });
});
