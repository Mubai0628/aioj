# AI 题目草稿 Agent 架构

[English](../en/problem-draft-agent-architecture.md)

> [!NOTE]
> 当前状态：本地真实 Provider + Docker 端到端已验证；生产仍为 `IMPLEMENTED_UNVERIFIED`，生产节点 `aioj_a` 尚未部署。真实 DeepSeek 两次完成了 CF 800、1400、1800 三个隔离工作区的草稿生成、样例运行、测试包生成、全包执行与 `draft.finalize`：2026-08-15 一次；2026-10-29 执行改走 agent-executor、工具返回统一反馈之后，又验收一次。所有草稿都停留在 `PENDING_REVIEW`，未批准、导入或发布。

## 目标与边界

AI 题目草稿的目标运行路径不再由固定七阶段 Provider 流水线决定每一步，而是复用 Agent Core V3：模型负责分析当前草稿、选择下一项工具和决定修复方向；服务器始终负责身份、工作区、权限、预算、Sandbox 编排、工件归属、验证结果与最终门禁。旧同步、SSE 和重新生成兼容入口已统一创建并等待持久 Agent 作业，且已标记弃用，不再运行独立生成引擎。

```text
管理端
  -> ProblemDraftAgentCoordinator
  -> AgentRuntime
  -> agent-harness 循环与工具运行时
  -> agent-executor
  -> agent-sandbox（go-judge）

ai-service -> problem-service -> 题目与测试包
```

本能力只面向教师和管理员，仍遵循“草稿 → 人工审核/批准 → 导入”。`draft.finalize` 只能创建 `PENDING_REVIEW` 的不可变草稿版本，模型不能批准、导入、激活或发布题目。

## 工作区与上下文隔离

每道新题创建独立、持久化的 `workspaceId`，后续继续优化、人工编辑、验证、测试包与导入都绑定同一工作区。服务器同时绑定创建者、专用会话、当前 working state、状态版本、当前不可变草稿、Agent run 和测试包工件；这些身份不能由模型参数覆盖。

- 专用会话来源固定为 `problem_draft_agent`，不会混入学生助教的 `ai_tutor` 会话、长期记忆、学习画像或跨题检索。
- 新一轮只加载本工作区的安全历史消息、当前用户请求和服务器策略；内部工具子轮次与原始思考内容不作为下一轮上下文。
- working state 使用 `stateVersion` 乐观并发控制。`draft.state.patch` 必须携带 `expectedStateVersion`，过期写入返回冲突。
- 每次有效内容修改都会重新计算指纹。题意、样例、解法、生成器或限制变化会使旧执行验收变为 `STALE`；纯标题或标签变化不会重复执行测试包。
- 管理端人工编辑也写入同一 working state，并保存为新的不可变草稿版本；编辑发生在 Agent 运行期间会被拒绝，避免人与模型并发覆盖。人工编辑后的版本必须重新通过受影响的 Agent 门禁，才能导入。
- 失败、超时或预算耗尽时保留 working state，并将工作区置为 `NEEDS_ATTENTION`，教师可以在同一题目上下文中继续优化。

## 模型循环与 DeepSeek 协议

DeepSeek 思考模式的 `reasoning_content` 只作为当前工具循环的内存传输字段。在一次工具循环后续请求中，运行时会完整回传对应 assistant 消息的 `content`、`reasoning_content` 与 `tool_calls`；思考模式不发送无效的 `temperature`。该行为遵循 [DeepSeek 思考模式](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode) 和 [DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls) 的公开协议。

原始思考内容不得写入数据库、日志、通用审计或前端，也不得在跨用户轮次中重放。持久化内容仅包括安全的用户消息、最终摘要、工具安全摘要、资源指标和状态。

当配置关闭思考、模型不支持思考，或 Provider 明确返回思考能力错误时，运行时切换到标准 `tools/tool_calls` 循环，并把模式记录为 `STANDARD_TOOL_FALLBACK`。参数仍由服务器 JSON Schema 校验，不依赖 `/beta` strict mode。

草稿 Agent 的默认可配置预算为：

| 预算 | 默认值 |
| --- | ---: |
| 模型步骤 | 20 |
| 工具调用 | 16 |
| Sandbox 操作 | 8 |
| 测试包生成 | 2 |
| 全包执行验收 | 2 |
| 单轮总时限 | 15 分钟 |

当前单 Sandbox 拓扑按顺序执行代码和测试包操作。达到任一预算不会绕过门禁，只会结束本轮并保留可诊断状态。

## 九个受控工具

| 工具 | 受控职责 |
| --- | --- |
| `draft.state.get` | 返回当前 working state、状态版本和权威验证摘要 |
| `draft.state.patch` | 以类型化字段补丁和期望版本修改 working state |
| `draft.static.validate` | 执行 Schema、字段一致性、算法难度与复杂度静态检查 |
| `draft.code.execute` | 在 Sandbox 中运行标程、参考解或受限探针，返回不透明 `executionId` |
| `draft.execution.get` | 分段读取受限输出、状态、耗时、内存、进程峰值与退出状态 |
| `draft.samples.run` | 使用当前代码、样例和限制运行全部公开样例 |
| `draft.testcase.generate` | 在 Sandbox 中运行当前 Python 生成器并创建测试包工件 |
| `draft.testcase.run_package` | 按正式判题比较语义执行完整测试包；高难题同时运行独立参考解 |
| `draft.finalize` | 检查当前指纹下全部门禁并创建待人工审核的不可变草稿 |

工具只接收模型可决定的业务参数。`workspaceId`、用户、run、草稿、工件和权限均由服务器绑定；`executionId` 与 `artifactId` 会验证工作区归属和有效期，内部路径、Sandbox file ID 与凭据不会暴露给模型。

执行类工具（`draft.code.execute`、`draft.samples.run`、`draft.testcase.run_package`、`draft.testcase.generate`）在原有字段之外都返回同一结构的 `feedback`：判定、耗时、内存、进程峰值，以及编译诊断或 stderr 摘要；批量用例只给通过数、失败分类和最大资源用量。生成测试包不运行被评判的程序，所以它的 `feedback` 没有判定和资源数值，只有状态、错误码和用例计数；原有字段里有界的错误消息保留，模型靠它们修复生成器。

## 执行与审计

ai-service 不直接访问 Docker 或 go-judge。所有 C++17、Java 17 和 Python 3 执行均经 agent-executor 调用 agent-sandbox，并受源码、输入、批次数量、文件、包体积、时间、内存、进程与输出硬限制。单次运行最多 256 MiB，编译和生成脚本最多 384 MiB；超过上限的请求直接拒绝，题目内存限制超过运行上限时明确报告超出出题沙箱的内存上限，不误报为超内存。全包执行复用正式判题的 STANDARD 比较语义；隐藏用例只返回通过数、失败分类和聚合资源数据，不返回完整隐藏输入输出。

agent-sandbox 的 `ES_OUTPUT_LIMIT` 与 `ES_COPY_OUT_LIMIT`（`AGENT_SANDBOX_OUTPUT_LIMIT`、`AGENT_SANDBOX_COPY_OUT_LIMIT`）默认均为 64 MiB，必须不小于 Agent 测试包 50 MiB 上限。该约束分别对应 go-judge 的进程输出限制与 copy-out 上限；若执行报告成功但未返回缓存包句柄，agent-executor 会返回 `PACKAGE_CACHE_FILE_MISSING` 结构化失败，不再把无工件状态误报为成功。参数语义参见 [go-judge 官方说明](https://github.com/criyle/go-judge)。

Harness 工具运行时在真正执行前写入 `RUNNING` 审计，结束后更新状态、结果哈希、安全摘要和资源指标。原始代码、生成器、stdin/stdout/stderr、测试包字节和 Provider 思考不进入通用审计字段。工具有真实超时与取消；执行结果分页、截断并设置有效期。

## 测试包双路径

### Agent 自动生成

Agent 运行 Python 生成器得到工件，先通过格式、安全、manifest、SHA 与大小检查，再完成全包执行验收。只有当前内容指纹下静态校验、公开样例、测试包格式和全包验收均通过，生成任务才可完成并由 `draft.finalize` 创建草稿版本。

### 人工上传

人工 ZIP 使用工作区级分块上传，会校验分块与整包 SHA、manifest、路径穿越、软链接、重复/大小写冲突路径、文件与解压体积、压缩比、用例数量和引用文件。格式 `READY` 后提供两条明确路径：

1. **Agent 验收测试**：运行完整测试包并展示受控 Agent 工具轨迹；通过后记录 `AGENT_VERIFIED`。
2. **直接导入**：允许跳过执行验收，但必须二次确认，并记录 `MANUAL_UNVERIFIED`、操作者、工件 SHA 与已有失败摘要。

无论选择哪条路径，导入仍要求草稿已批准、静态内容有效、格式为 `READY` 且工件指纹与当前草稿一致。缺包、上传中、格式失败、工件过期、验收失败和导入中断都返回结构化阻断原因。

## 可恢复导入

ai-service 使用调用方提供的 idempotency key 驱动持久化 saga：

```text
PREPARING -> PACKAGE_READY -> ACTIVATED -> COMPLETED
```

problem-service 的受保护内部契约保证题目标记 `aiGenerated=true`，并保存来源 workspace/draft、审核风险与测试包验证模式。新题先以 `PRIVATE` 准备；测试包到达 `READY` 后，在 problem-service 事务中同时提交题目信息、激活测试包并应用目标可见性。更新已有题目时，旧题面、可见性和活动包会保留到新包可激活为止。

更新目标不接受浏览器或模型提供的题目 ID。ai-service 只从当前工作区的已导入草稿版本或服务器保存的来源草稿派生目标，并把该目标写入 idempotency saga；problem-service 通过条件更新认领旧目标，若目标已被另一工作区认领则返回冲突，避免生成重复题目或静默覆盖其他题目。

工作区来源在题目侧唯一，导入幂等键在操作者范围内唯一。每个步骤重试都会复用已有 problem/package，避免产生重复题目或题面与测试包错配。

## 数据模型

Flyway `V66__problem_draft_agent_workspaces.sql` 只向前新增：

- 隔离工作区与 working state；
- 受限执行结果；
- 人工测试包分块上传会话与分块元数据；
- 可恢复导入 saga；
- generation job、草稿、测试包工件、Agent run、工具调用和题目的 workspace、版本、指纹、风险、来源、验证与安全审计字段。

V49–V51 中未接线的旧 job/attempt/artifact 表是历史结构，保持不动且不作为当前运行路径。新实现复用 `ai_conversations/ai_turns/ai_messages` 与 `ai_agent_runs/ai_tool_calls/ai_context_manifests`，不建立第二套对话或 Agent 循环。

## 管理端呈现

生成任务、失败任务和待处理任务均可打开同一 Agent 工作区。工作区显示模型、思考/兼容模式、运行时间、安全决策摘要、工具状态、资源指标、题目内会话和继续优化输入；不会显示原始思考链。

草稿详情包含预览、验证、测试包、Agent 过程和编辑。桌面端使用表格/时间线，手机端使用卡片和单列工具详情；客户端增量拉取 run/tool 状态，所有长 ID 以字符串处理。

当难度为 `HARD`、`CHALLENGE` 或请求 CF rating ≥ 1701 时，管理端持续展示：

> 该题由 AI 生成且难度较高，题意、复杂度、标程与测试覆盖仍需人工仔细甄别。

`MANUAL_UNVERIFIED` 导入无论难度都显示更强的未验收提示。学生端继续只展示既有 AI 标签，不暴露管理侧风险与工具详情。

## 验收与交付状态

本地实现的目标验证范围包括 DeepSeek 回传与降级、预算/超时/取消、ABAC、工作区隔离、指纹失效、九工具、三语言 Sandbox、危险 ZIP、自动/人工测试包、导入 saga 崩溃点，以及管理端桌面与 400×858、440×956 移动视口。

截至本文更新，本地真实验收结果如下：

| 请求难度 | 运行模式与恢复路径 | 公开样例 | 全包验收 | 工件大小 | 最终状态 |
| --- | --- | ---: | ---: | ---: | --- |
| CF 800 | 单轮 `THINKING_TOOL_LOOP`，模型自行发现并修复生成器错误 | 3/3 | 8/8 | 8.1 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |
| CF 1400 | 单轮安全降级为 `STANDARD_TOOL_FALLBACK` | 3/3 | 12/12 | 1.2 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |
| CF 1800 | 前两轮在同一隔离工作区保留状态；修复 copy-out 容量后仅执行 state → generate → run-package → finalize | 3/3 | 16/16，含独立参考解 | 20.4 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |

本次排查同时修复了两项真实根因：其一，旧运行时会把任意工具调用后的普通文本误判为完成，现在草稿运行必须成功调用 `draft.finalize`，否则保留状态并继续/降级；其二，旧本地 Sandbox 的 16 MiB copy-out 上限小于 50 MiB 测试包上限，导致 1800 工件虽生成却没有可取回句柄，现在限制已统一为 64 MiB 并增加结构化诊断。真实 Chrome 验证了草稿信息、完整工具轨迹、高难警告以及桌面、400×858、440×956 三种视口无横向溢出；原始思考未进入前端。该证据只证明本地单并发路径，不代表生产容量验证；本次未连接或更新 `aioj_a`。

2026-10-29 用同样的三份请求再验收一次。执行改走 agent-executor → agent-sandbox，判题 Sandbox 在此期间除自身健康检查外没有收到任何请求：

| 请求难度 | 轮次与运行模式 | 公开样例 | 全包验收 | 工件大小 | 最终状态 |
| --- | --- | ---: | ---: | ---: | --- |
| CF 800 | 三轮：第一轮的测试包在验收端以压缩比不安全被拒，并用完预算；第二轮 Provider 返回 HTTP 400；第三轮 `THINKING_TOOL_LOOP` 完成 | 3/3 | 8/8 | 0.07 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |
| CF 1400 | 两轮 `THINKING_TOOL_LOOP`：第一轮全包已通过，但没有 finalize 就用完了工具预算 | 3/3 | 12/12 | 2.5 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |
| CF 1800 | 两轮 `THINKING_TOOL_LOOP`：第一轮生成测试包后用完工具预算 | 3/3 | 16/16，含独立参考解 | 10.3 MiB | `PENDING_REVIEW` / `AGENT_VERIFIED` |

这次验收修了两处：agent-executor 拒绝测试包时，拒绝原因现在会交给模型，之前模型只看到不透明的执行错误；工具预算在一轮多个调用的中途用完时，剩下的调用也会得到“预算已用完”的结果，不再因为缺少 tool 消息被 Provider 以 HTTP 400 拒绝。当时生成端和验收端的压缩比规则不一致：生成端只检查 1 MiB 以上的条目，验收端检查全部条目，所以生成能通过的包可能在验收时被拒。同日改为两端一致：都只对解压后 1 MiB 以上的条目限制压缩比（不超过 200:1），更小的条目由单条目和总量上限约束，这两个上限按实际写出的字节数计算。
