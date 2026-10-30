# 系统架构

[English](../en/system-architecture.md)

## 总览

```text
浏览器 -> web-user / web-admin -> gateway
                                   |-> auth-service -> MySQL
                                   |-> problem-service -> MySQL / Redis / RabbitMQ
                                   `-> ai-service -> 模型调用包 -> DeepSeek / 向量模型
                                          `-> agent-executor -> agent-sandbox

RabbitMQ -> judge-worker -> go-judge Sandbox
```

浏览器只访问网关公开的 `/api/v1/**`。服务间契约和 Flyway 迁移集中在 `backend/api-contract`，共享响应、错误、追踪和安全过滤集中在 `backend/common-lib`。

内部 HTTP 客户端统一由 common-lib 的工厂创建，按被调方使用本方向的 `X-Internal-Token`。四个业务方向的独立令牌与执行器专用令牌互不替代；被调方只开放登记给该调用方的路径前缀，并支持当前/NEXT 双值入站轮换。网关和 auth-service 不持有内部凭据。详见[安全架构](security-architecture.md)。

工厂使用 JDK HttpClient 与显式连接/读取超时；每客户端可通过 `aioj.internal.http.clients.<名称>` 覆盖。熔断和立即失败、不排队的舱壁按目标服务共享，默认满窗 20 次、50% 失败率或连续 3 次故障后打开，30 秒后仅放一个探测。agent-executor 忙时返回带 `Retry-After` 的 503，这表示满负荷而不是故障，不计入熔断。只有 GET 在网络失败、超时或 502/503/504 时短抖动重试一次，两个尝试共享有界预算；POST 从不重试。比赛严格上下文在故障时仍拒答，可选上下文仍按原路径降级。远程上下文、模型及执行器工作与 DB 短事务分阶段；判题事件等待事务完全返回后才推送，推送失败不能改变已提交结果。事务内 HTTP 在生产警告并计数，在测试中报错。应用连接池按短 DB 阶段而非远程等待线程数配置，Harness DB 配额继续为非 Agent 请求保留连接。

## 健康分级

- `/actuator/health/liveness` 只有进程活性，不依赖数据库、缓存、队列或 Agent。
- `/actuator/health/readiness` 只有就绪状态和该服务不可降级的本地依赖；Compose 和部署门禁使用它。auth、ai 需要 DB；problem、judge-worker 需要 DB/RabbitMQ；gateway、agent-executor 无本地 DB/MQ。Redis 限流/缓存现有的失败放行或回退行为不被健康检查收紧。
- ai-service 的 `/actuator/health/agent` 汇总 Harness、文本模型熔断、执行器、数据通道和 problem-service；任一项降级可见为 `DEGRADED` / HTTP 200，但不改变总状态或 readiness。组件状态可见，细节只给已认证的教师/管理员。

模型和 problem-service 项只观察现有熔断状态，不代表已验证连通；执行器每 10 秒以短超时探测 readiness，不执行代码。数据通道只读现有定时统计快照，积压/死信和快照过期阈值可通过 `aioj.ai.health` 配置。所有健康项都不发模型请求。未暴露的 MVC 资源用现有错误包装返回 404，不回显内部资源路径。

## 组件

| 组件 | 职责 |
| --- | --- |
| gateway-service | 唯一浏览器 API 入口、路由与跨域边界 |
| auth-service | 登录、JWT/刷新令牌、用户、角色、用户组与 Flyway |
| problem-service | 题目、测试包、比赛、提交、通知和异步任务 |
| judge-worker | 消费 RabbitMQ 判题消息并调用 Sandbox，不直接执行代码 |
| ai-service | Agent 运行时、模型调用包、统一数据通道、对话、记忆、题目草稿、查重与复盘 |
| agent-executor | Agent 工具和题目页自测运行的唯一执行入口：编译、运行、生成器与测试包，只调用 agent-sandbox |
| web-user / web-admin | 学生与管理 React 单页应用 |
| MySQL / Redis / RabbitMQ | 权威业务数据、缓存/短期状态与判题队列 |
| go-judge Sandbox | 隔离执行不可信代码的特权运行时，只接正式判题 |
| agent-sandbox | Agent 工具和自测运行专用的 go-judge 实例，只由 agent-executor 调用 |

`backend/agent-harness` 是与领域无关的 Agent 循环、工具管线和上下文装配库，ai-service 在它上面实现助教问答和 AI 题目草稿两个场景；`backend/sandbox-client` 是判题与 Agent 共用的 go-judge 协议、语言配置和输出比较。

## 主要数据流

### 认证

浏览器经网关向 auth-service 登录。密码只在登录时校验，成功后换取短期访问令牌与受控刷新令牌。其他服务使用统一安全过滤校验身份与角色。

### 判题

problem-service 持久化提交并向 RabbitMQ 发布判题任务。judge-worker 消费任务、读取授权测试包引用并调用 Sandbox。结果回写 problem-service 所属数据，不通过浏览器直连 judge-worker 或 Sandbox。

### AI

ai-service 在服务器端计算权限与策略，构建最小上下文，调用 Provider 和受权工具，并持久化用量与审计证据。AI 访问 problem-service 使用最小跨服务契约，不直接读取其他服务的数据库表。

所有模型调用都经 ai-service 内的模型调用包：超时、重试、熔断和每次调用的观测记录都在这里，架构测试禁止其他调用路径。文本模型只用 DeepSeek：Kimi 和通用 OpenAI 兼容的文本选项已于 2026-10-29 停用，库里留下的这类配置照常显示，但不能再保存或发起调用。向量模型可以用 DashScope 或自定义的 OpenAI 兼容接口。学生问答通过 `/ai/chat/stream` 返回事件流，正文增量和工具进度见 [Agent Core V3 架构](agent-core-v3-architecture.md)。

轮次结束后的工作经统一数据通道完成：摘要整理、向量化、学习画像聚合、判题分析和记忆合并审查都是通道的订阅者。事件和每个订阅者的作业在同一个事务里写入；作业失败按退避重试，超过次数进入死信，管理员可以按订阅者重放。

AI 题目草稿使用 `ProblemDraftAgentCoordinator → AgentRuntime → agent-harness 循环`。代码、样例、生成器和测试包执行由 ai-service 通过受保护的内部接口交给 agent-executor，再由 agent-executor 调用 agent-sandbox；ai-service 不直接访问 Docker 或任何沙箱，判题 Sandbox 只做正式判题。通过人工审核的草稿使用可恢复导入 saga 将题目信息和 `READY` 测试包提交给 problem-service。详见 [AI 题目草稿 Agent 架构](problem-draft-agent-architecture.md)。

### 自测运行

题目页编辑器旁的“运行”把代码交给 ai-service（`/api/v1/ai/code-runs`）。problem-service 按这个用户核对题目是否可见（私有题只对比赛时间内的有效参赛者开放，比赛上下文由服务端核对），并给出公开样例和该语言的时限；代码再经 agent-executor 在 agent-sandbox 里运行，和助教的代码工具同一条路，不经过判题 Sandbox。运行不建提交记录、不触发分析、不计成绩，只用公开样例和学生自己的输入（不超过 1 MB）。服务端限制同一人两次运行的间隔、每人同时一个运行、全站同时运行数和每人每分钟、每天的次数，这些限额由管理员在管理端设置。

### 通知

持久化通知记录是事实来源；SSE 只负责实时唤醒。断线或刷新后，客户端通过 REST 补偿并恢复未读状态。

## 信任边界

- 浏览器输入、客户端比赛上下文和模型输出均不可信。
- 授权、比赛可见性、工具许可和数据裁剪在服务端执行。
- 前端 16 位以上实体 ID 始终保留为字符串。
- 隐藏测试、参与者源码、Provider 密钥与完整评测输出不能跨越未授权 API。
- AI 输出和工具调用不能获得高于当前用户与场景策略的权限。

## 持久化

- MySQL 保存权威业务数据和 Flyway 历史。
- Redis 保存可重建的缓存与短期状态。
- RabbitMQ 保存异步判题队列与受控重试状态。
- 测试包、运维产物、AI 草稿产物和判题缓存使用文件/卷存储；大体积隐藏测试不进入 MySQL。
- V66 新增 AI 草稿工作区、受限执行元数据、分块上传会话和可恢复导入 saga，并为既有草稿/工件/Agent 审计/题目增加来源与指纹。V49–V51 未接线的旧草稿 job/attempt/artifact 表仅保留为历史结构，不进入当前路径。
- 模型调用记录（V70）、统一通道的事件信封（V72）和订阅者作业都在 MySQL。旧的记忆作业表保留但不再写入；ai-service 启动时把其中没做完的判题分析和记忆合并审查作业转成通道作业。

## 生产拓扑

当前生产把应用、数据服务、judge-worker 与 Sandbox 合并在单节点。应用网络承载业务服务，内部 judge 网络只连接 judge-worker 与 Sandbox。Sandbox 不公开端口、不使用 host network、不挂载 Docker socket 或业务目录，也不接收数据库、JWT、AI 与部署秘密。

Sandbox 仍是 `privileged` 容器，成功逃逸可能危及整台主机与全部数据。这一风险不能被网络和资源控制完全消除，详见[安全架构](security-architecture.md)。

Agent 系统（含 AI 题目草稿）只在本地用真实 Provider 验收过，状态仍为 `IMPLEMENTED_UNVERIFIED`；生产节点 `aioj_a` 尚未部署这些变更。
