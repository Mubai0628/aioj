#!/usr/bin/env node
/**
 * T1.11 tool-feedback eval (plan A5.5). Runs against the live local stack (gateway -> ai-service
 * -> DeepSeek -> agent-executor) and measures from the observability plane (ai_tool_calls,
 * ai_agent_runs), plus the answer text for the one thing only the answer shows.
 *
 * Part T, tutor debugging: eight programs with a known bug for the practice A+B problem (two
 * each of CE, WA, TLE and RE), each sent as the code selected in a fresh conversation. A case
 * passes when the turn ran a code tool successfully and the answer names the failure the tool
 * reported (the planted verdict: the tools are deterministic for these programs) without
 * claiming a contradicting one. CE answers are also checked for the error's line number.
 *
 * Part D, problem drafting: from every problem-draft run's tool calls already in the database
 * (no model calls): first-pass rate (runs that reached draft.finalize without a draft.state.patch
 * after their first draft.samples.run), repair rounds (those patches), tool error rate and the
 * repeated-call rate (same tool, same arguments, same run, with no successful draft.state.patch in
 * between: re-checking after an edit is not a repeat).
 *
 * This is a BASELINE: the script exits 0 on a completed measurement (non-zero only on FATAL).
 * Run it with ai-service's agent jobs off: then the turns make no curator or embedding calls.
 *
 * Env:
 *   AIOJ_EVAL_BASE_URL    gateway base (default http://127.0.0.1:8101)
 *   AIOJ_EVAL_ACCOUNT     student account; AIOJ_EVAL_PASSWORD (required)
 *   AIOJ_EVAL_PROBLEM_ID  the practice problem (default 2060209062558281729, A+B)
 *   AIOJ_EVAL_CASES       comma subset of case ids, e.g. "CE1,WA1" (default: all)
 *   AIOJ_EVAL_KEEP        keep eval rows (default false)
 *   MYSQL_EXE / MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB
 */
import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = join(HERE, "out");

const config = {
  baseUrl: (process.env.AIOJ_EVAL_BASE_URL || "http://127.0.0.1:8101").replace(/\/+$/, ""),
  account: process.env.AIOJ_EVAL_ACCOUNT || "tooleval001",
  password: process.env.AIOJ_EVAL_PASSWORD || "",
  problemId: process.env.AIOJ_EVAL_PROBLEM_ID || "2060209062558281729",
  cases: (process.env.AIOJ_EVAL_CASES || "").split(",").map((c) => c.trim().toUpperCase()).filter(Boolean),
  keep: (process.env.AIOJ_EVAL_KEEP || "false") === "true",
  mysql: {
    exe: process.env.MYSQL_EXE || "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe",
    host: process.env.MYSQL_HOST || "127.0.0.1",
    port: process.env.MYSQL_PORT || "3306",
    user: process.env.MYSQL_USER || "aioj",
    password: process.env.MYSQL_PASSWORD || "",
    db: process.env.MYSQL_DB || "ai_oj_next",
  },
};

const RUN_TAG = `tfbeval-${Date.now()}`;
const MARKER_PREFIX = "tfbeval-";
const CODE_TOOLS = ["code.compile_check", "code.run_samples", "code.run_custom"];

function fail(message) {
  console.error(`FATAL: ${message}`);
  process.exit(2);
}

if (!config.password) {
  fail("AIOJ_EVAL_PASSWORD is required");
}
if (!config.mysql.password) {
  fail("MYSQL_PASSWORD env is required for observability queries");
}

// ---------------------------------------------------------------------------
// MySQL CLI and HTTP helpers (same conventions as agent-recall-eval.mjs)
// ---------------------------------------------------------------------------

function mysqlArgs() {
  return [
    `--host=${config.mysql.host}`, `--port=${config.mysql.port}`, `--user=${config.mysql.user}`,
    `--password=${config.mysql.password}`, `--database=${config.mysql.db}`,
    "--batch", "--raw", "--silent", "--skip-column-names", "--default-character-set=utf8mb4",
  ];
}

function query(sql) {
  const result = spawnSync(config.mysql.exe, [...mysqlArgs(), `--execute=${sql}`], {
    encoding: "utf8", maxBuffer: 64 * 1024 * 1024,
  });
  if (result.status !== 0) {
    fail(`mysql query failed: ${result.stderr || result.stdout}`);
  }
  const out = (result.stdout || "").replace(/\r\n/g, "\n").trim();
  return out ? out.split("\n").map((line) => line.split("\t").map((col) => (col === "NULL" ? null : col))) : [];
}

function queryOne(sql) {
  const rows = query(sql);
  return rows.length ? rows[0] : null;
}

function sqlString(value) {
  return `'${String(value).replace(/\\/g, "\\\\").replace(/'/g, "''")}'`;
}

async function apiPost(path, body, token, timeoutMs = 240_000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${config.baseUrl}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const text = await response.text();
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch {
      fail(`${path} returned non-JSON (HTTP ${response.status}): ${text.slice(0, 200)}`);
    }
    if (!response.ok || parsed == null || parsed.data == null) {
      fail(`${path} failed (HTTP ${response.status}): ${text.slice(0, 300)}`);
    }
    return { data: parsed.data, raw: text };
  } finally {
    clearTimeout(timer);
  }
}

/** Snowflake ids are 19-digit numbers that JS Number cannot hold; pull them from raw JSON. */
function extractDataId(raw, label) {
  const markerIndex = raw.indexOf('"data"');
  const match = (markerIndex >= 0 ? raw.slice(markerIndex) : raw).match(/"id"\s*:\s*"?(\d+)"?/);
  if (!match) {
    fail(`${label} response did not include an id`);
  }
  return match[1];
}

async function login() {
  const { data } = await apiPost("/api/v1/auth/login", { account: config.account, password: config.password }, null, 30_000);
  if (!data.accessToken) {
    fail("login response missing accessToken");
  }
  return data.accessToken;
}

let sendSeq = 0;

/** One practice turn on the A+B problem with the code selected; returns the turn and its answer. */
async function sendDebugTurn(token, code, message, note) {
  const clientMessageId = `${RUN_TAG}-${String(++sendSeq).padStart(3, "0")}`;
  const { data, raw } = await apiPost("/api/v1/ai/chat/send", {
    conversationId: null,
    problemId: config.problemId,
    message,
    clientMessageId,
    selectionContext: { selectionId: clientMessageId, sourceType: "CODE_EDITOR", sourceRole: "user", selectedText: code },
  }, token);
  const messageId = extractDataId(raw, note);
  const turnRow = queryOne(`SELECT id FROM ai_turns WHERE assistant_message_id = '${messageId}' LIMIT 1`);
  if (!turnRow) {
    fail(`no ai_turns row for assistant message ${messageId} (${note})`);
  }
  return { turnId: turnRow[0], conversationId: data.conversationId, content: data.content || "" };
}

function toolCalls(turnId) {
  return query(
    `SELECT tool_name, status, COALESCE(error_code, '') FROM ai_tool_calls WHERE turn_id = '${turnId}' ORDER BY call_seq`
  ).map(([toolName, status, errorCode]) => ({ toolName, status, errorCode }));
}

// ---------------------------------------------------------------------------
// Part T: the planted programs
// ---------------------------------------------------------------------------

const ASK = "这是我在这道题上写的代码，提交没过。帮我看看是什么问题：请先用工具实际编译或运行一下，再根据运行结果告诉我错在哪。";

const CASES = [
  { id: "CE1", verdict: "CE", line: 4, code: "#include <cstdio>\nint main() {\n    int a, b;\n    scanf(\"%d %d\", &a, &b)\n    printf(\"%d\\n\", a + b);\n    return 0;\n}\n" },
  { id: "CE2", verdict: "CE", line: 2, code: "a, b = map(int, input().split())\nprint(a + b\n" },
  { id: "WA1", verdict: "WA", code: "#include <cstdio>\nint main() {\n    int a, b;\n    scanf(\"%d %d\", &a, &b);\n    printf(\"%d\\n\", a - b);\n    return 0;\n}\n" },
  { id: "WA2", verdict: "WA", code: "a, b = map(int, input().split())\nprint(a * b)\n" },
  { id: "TLE1", verdict: "TLE", code: "#include <cstdio>\nint main() {\n    int a, b;\n    scanf(\"%d %d\", &a, &b);\n    long long s = 0;\n    for (long long i = 0; i >= 0; i += 0) s += i;\n    printf(\"%d\\n\", a + b);\n    return 0;\n}\n" },
  { id: "TLE2", verdict: "TLE", code: "a, b = map(int, input().split())\nwhile a + b == a + b:\n    pass\nprint(a + b)\n" },
  { id: "RE1", verdict: "RE", code: "#include <cstdio>\nint main() {\n    int a, b;\n    scanf(\"%d %d\", &a, &b);\n    int *p = nullptr;\n    *p = a;\n    printf(\"%d\\n\", a + b);\n    return 0;\n}\n" },
  { id: "RE2", verdict: "RE", code: "a, b = map(int, input().split())\nprint((a + b) // (a - a))\n" },
];

const VERDICT_WORDS = {
  CE: /编译(错误|失败|不通过|报错|出错)|compil(e|ation) error|\bCE\b|syntax ?error|语法错误/i,
  WA: /答案错误|wrong answer|\bWA\b|输出(不对|错误|不一致|与期望|不符)|结果(不对|错误)|算成了|减法|乘法/i,
  TLE: /超时|time limit|\bTLE\b|死循环|无限循环|infinite loop|不会结束|永远不/i,
  RE: /运行(时)?错误|runtime error|\bRE\b|除(以)?零|ZeroDivisionError|段错误|segmentation|空指针|nullptr|崩溃/i,
};
// "编译通过之后再提交" is advice, not a claim that this code compiles (the first baseline counted it).
const CONTRADICTS = { CE: /编译(通过|成功)(?!\s*(之后|以后|后))|compiles (fine|successfully)/i };

function judgeCase(spec, turn) {
  const calls = toolCalls(turn.turnId);
  const codeCalls = calls.filter((call) => CODE_TOOLS.includes(call.toolName));
  const toolOk = codeCalls.some((call) => call.status === "SUCCESS");
  const named = VERDICT_WORDS[spec.verdict].test(turn.content);
  const contradicted = CONTRADICTS[spec.verdict] ? CONTRADICTS[spec.verdict].test(turn.content) : false;
  const lineCited = spec.line ? new RegExp(`(第\\s*${spec.line}\\s*行|line\\s*${spec.line}\\b)`, "i").test(turn.content) : null;
  return {
    id: spec.id,
    verdict: spec.verdict,
    turnId: turn.turnId,
    codeTools: codeCalls.map((call) => `${call.toolName}:${call.status}${call.errorCode ? `(${call.errorCode})` : ""}`),
    toolOk,
    named,
    contradicted,
    lineCited,
    pass: toolOk && named && !contradicted,
  };
}

// ---------------------------------------------------------------------------
// Part D: problem-draft runs, from their tool calls
// ---------------------------------------------------------------------------

function draftMetrics() {
  const rows = query(
    "SELECT c.agent_run_id, c.call_seq, c.tool_name, c.status, MD5(COALESCE(CAST(c.arguments_redacted AS CHAR), '')) " +
    "FROM ai_tool_calls c JOIN ai_agent_runs r ON r.id = c.agent_run_id " +
    "WHERE r.workspace_id IS NOT NULL ORDER BY c.agent_run_id, c.call_seq"
  );
  const runs = new Map();
  for (const [runId, seq, toolName, status, argsHash] of rows) {
    if (!runs.has(runId)) {
      runs.set(runId, []);
    }
    runs.get(runId).push({ seq: Number(seq), toolName, status, argsHash });
  }
  let calls = 0;
  let errors = 0;
  let repeated = 0;
  let validatedRuns = 0;
  let finalizedRuns = 0;
  let firstPassRuns = 0;
  const repairRounds = [];
  for (const list of runs.values()) {
    const seen = new Set();
    let firstSamples = -1;
    let repairs = 0;
    let finalized = false;
    list.forEach((call, index) => {
      calls++;
      if (call.status !== "SUCCESS") {
        errors++;
      }
      const key = `${call.toolName}|${call.argsHash}`;
      // Reads of state repeat by design, and an edit makes every check worth running again.
      if (seen.has(key) && call.toolName !== "draft.state.get" && call.toolName !== "draft.execution.get") {
        repeated++;
      }
      seen.add(key);
      if (call.toolName === "draft.state.patch" && call.status === "SUCCESS") {
        seen.clear();
      }
      if (call.toolName === "draft.samples.run" && firstSamples < 0) {
        firstSamples = index;
      }
      if (call.toolName === "draft.state.patch" && firstSamples >= 0) {
        repairs++;
      }
      if (call.toolName === "draft.finalize" && call.status === "SUCCESS") {
        finalized = true;
      }
    });
    if (firstSamples >= 0) {
      validatedRuns++;
      repairRounds.push(repairs);
      if (finalized) {
        finalizedRuns++;
        if (repairs === 0) {
          firstPassRuns++;
        }
      }
    }
  }
  const avg = repairRounds.length ? repairRounds.reduce((a, b) => a + b, 0) / repairRounds.length : 0;
  return {
    runs: runs.size,
    validatedRuns,
    finalizedRuns,
    firstPassRate: finalizedRuns ? firstPassRuns / finalizedRuns : null,
    avgRepairRounds: Number(avg.toFixed(2)),
    toolCalls: calls,
    toolErrorRate: calls ? Number((errors / calls).toFixed(3)) : null,
    repeatedCallRate: calls ? Number((repeated / calls).toFixed(3)) : null,
  };
}

// ---------------------------------------------------------------------------
// Cleanup (same tables as the other suites, plus the channel's turn events)
// ---------------------------------------------------------------------------

function cleanupEvalData() {
  const conversations = query(
    `SELECT DISTINCT conversation_id FROM ai_messages WHERE client_message_id LIKE '${MARKER_PREFIX}%'`
  ).map(([id]) => id);
  if (!conversations.length) {
    return 0;
  }
  const idList = conversations.map(sqlString).join(",");
  const digestIds = query(`SELECT id FROM ai_turn_digests WHERE conversation_id IN (${idList})`).map(([id]) => id);
  if (digestIds.length) {
    const dgList = digestIds.map(sqlString).join(",");
    query(`DELETE FROM ai_retrieval_chunks WHERE owner_type = 'TURN_DIGEST' AND owner_id IN (${dgList})`);
  }
  const turnIds = query(`SELECT id FROM ai_turns WHERE conversation_id IN (${idList})`).map(([id]) => id);
  if (turnIds.length) {
    const tList = turnIds.map(sqlString).join(",");
    const channelEvents = query(
      `SELECT id FROM ai_domain_events WHERE source_type = 'turn' AND source_id IN (${tList})`
    ).map(([id]) => sqlString(id));
    if (channelEvents.length) {
      query(`DELETE FROM ai_async_jobs WHERE SUBSTRING_INDEX(idempotency_key, ':', 1) IN (${channelEvents.join(",")})`);
      query(`DELETE FROM ai_domain_events WHERE id IN (${channelEvents.join(",")})`);
    }
    query(`DELETE FROM ai_async_jobs WHERE job_type = 'TURN_CURATE' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.conversationId')) IN (${idList})`);
    query(`DELETE FROM ai_context_manifests WHERE turn_id IN (${tList})`);
    query(`DELETE FROM ai_tool_calls WHERE turn_id IN (${tList})`);
    query(`DELETE FROM ai_guard_decisions WHERE turn_id IN (${tList})`);
    query(`DELETE FROM ai_policy_snapshots WHERE turn_id IN (${tList})`);
    query(`DELETE FROM ai_agent_runs WHERE turn_id IN (${tList})`);
  }
  query(`DELETE FROM ai_turn_digests WHERE conversation_id IN (${idList})`);
  query(`DELETE FROM ai_turns WHERE conversation_id IN (${idList})`);
  query(`DELETE FROM ai_messages WHERE conversation_id IN (${idList})`);
  query(`DELETE FROM ai_conversations WHERE id IN (${idList})`);
  return conversations.length;
}

// ---------------------------------------------------------------------------

async function main() {
  mkdirSync(OUT_DIR, { recursive: true });
  const token = await login();
  // AIOJ_EVAL_CASES=NONE measures only the draft part (no model calls).
  const selected = CASES.filter((spec) => !config.cases.length || config.cases.includes(spec.id));
  const results = [];
  for (const spec of selected) {
    const turn = await sendDebugTurn(token, spec.code, ASK, spec.id);
    const result = judgeCase(spec, turn);
    results.push(result);
    writeFileSync(join(OUT_DIR, `${RUN_TAG}-${spec.id}.json`), JSON.stringify({ ...result, answer: turn.content }, null, 2));
    console.log(`${spec.id}: ${result.pass ? "PASS" : "FAIL"} tools=[${result.codeTools.join(", ")}] named=${result.named}`
      + ` contradicted=${result.contradicted}${result.lineCited === null ? "" : ` lineCited=${result.lineCited}`}`);
  }
  const passed = results.filter((r) => r.pass).length;
  const toolUse = results.filter((r) => r.toolOk).length;
  const ceCases = results.filter((r) => r.lineCited !== null);
  const tutor = {
    cases: results.length,
    passed,
    consistencyRate: results.length ? Number((passed / results.length).toFixed(3)) : null,
    toolUseRate: results.length ? Number((toolUse / results.length).toFixed(3)) : null,
    ceLineCitedRate: ceCases.length ? Number((ceCases.filter((r) => r.lineCited).length / ceCases.length).toFixed(3)) : null,
    toolsUsed: [...new Set(results.flatMap((r) => r.codeTools.map((t) => t.split(":")[0])))].sort(),
  };
  const drafts = draftMetrics();
  const report = { runTag: RUN_TAG, tutor, drafts, results };
  writeFileSync(join(OUT_DIR, `${RUN_TAG}-report.json`), JSON.stringify(report, null, 2));
  console.log(`tutor: ${passed}/${results.length} cases passed, tool_use_rate=${tutor.toolUseRate}`
    + ` consistency_rate=${tutor.consistencyRate} ce_line_cited_rate=${tutor.ceLineCitedRate} tools=${tutor.toolsUsed.join(",")}`);
  console.log(`drafts: runs=${drafts.runs} first_pass_rate=${drafts.firstPassRate} avg_repair_rounds=${drafts.avgRepairRounds}`
    + ` tool_error_rate=${drafts.toolErrorRate} repeated_call_rate=${drafts.repeatedCallRate}`);
  if (!config.keep) {
    console.log(`cleanup: removed ${cleanupEvalData()} eval conversations`);
  } else {
    console.log(`keep: eval rows kept (marker ${MARKER_PREFIX})`);
  }
  console.log("BASELINE (no gate): cases passed reported above");
}

main().catch((error) => {
  if (!config.keep) {
    try {
      cleanupEvalData();
    } catch {
      // the original failure is what matters
    }
  }
  fail(error?.stack || String(error));
});
