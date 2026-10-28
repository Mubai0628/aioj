# 系统架构

[English](../en/system-architecture.md)

## 总览

```text
浏览器 -> web-user / web-admin -> gateway
                                   |-> auth-service -> MySQL
                                   |-> problem-service -> MySQL / Redis / RabbitMQ
                                   `-> ai-service -> 模型调用包 -> 兼容 Provider
                                          `-> agent-executor -> agent-sandbox

RabbitMQ -> judge-worker -> go-judge Sandbox
```

浏览器只访问网关公开的 `/api/v1/**`。服务间契约和 Flyway 迁移集中在 `backend/api-contract`，共享响应、错误、追踪和安全过滤集中在 `backend/common-lib`。

## 组件

| 组件 | 职责 |
| --- | --- |
| gateway-service | 唯一浏览器 API 入口、路由与跨域边界 |
| auth-service | 登录、JWT/刷新令牌、用户、角色、用户组与 Flyway |
| problem-service | 题目、测试包、比赛、提交、通知和异步任务 |
| judge-worker | 消费 RabbitMQ 判题消息并调用 Sandbox，不直接执行代码 |
| ai-service | Agent 运行时、模型调用包、统一数据通道、对话、记忆、题目草稿、查重与复盘 |
| agent-executor | Agent 工具执行的唯一入口：编译、运行、生成器与测试包，只调用 agent-sandbox |
| web-user / web-admin | 学生与管理 React 单页应用 |
| MySQL / Redis / RabbitMQ | 权威业务数据、缓存/短期状态与判题队列 |
| go-judge Sandbox | 隔离执行不可信代码的特权运行时，只接正式判题 |
| agent-sandbox | Agent 工具专用的 go-judge 实例，只由 agent-executor 调用 |

`backend/agent-harness` 是与领域无关的 Agent 循环、工具管线和上下文装配库，ai-service 在它上面实现助教问答和 AI 题目草稿两个场景；`backend/sandbox-client` 是判题与 Agent 共用的 go-judge 协议、语言配置和输出比较。

## 主要数据流

### 认证

浏览器经网关向 auth-service 登录。密码只在登录时校验，成功后换取短期访问令牌与受控刷新令牌。其他服务使用统一安全过滤校验身份与角色。

### 判题

problem-service 持久化提交并向 RabbitMQ 发布判题任务。judge-worker 消费任务、读取授权测试包引用并调用 Sandbox。结果回写 problem-service 所属数据，不通过浏览器直连 judge-worker 或 Sandbox。

### AI

ai-service 在服务器端计算权限与策略，构建最小上下文，调用 Provider 和受权工具，并持久化用量与审计证据。AI 访问 problem-service 使用最小跨服务契约，不直接读取其他服务的数据库表。

所有模型调用都经 ai-service 内的模型调用包：超时、重试、熔断和每次调用的观测记录都在这里，架构测试禁止其他调用路径。学生问答通过 `/ai/chat/stream` 返回事件流，正文增量和工具进度见 [Agent Core V3 架构](agent-core-v3-architecture.md)。

轮次结束后的工作经统一数据通道完成：摘要整理、向量化、学习画像聚合、判题分析和记忆合并审查都是通道的订阅者。事件和每个订阅者的作业在同一个事务里写入；作业失败按退避重试，超过次数进入死信，管理员可以按订阅者重放。

AI 题目草稿使用 `ProblemDraftAgentCoordinator → AgentRuntime → agent-harness 循环`。代码、样例、生成器和测试包执行由 ai-service 通过受保护的内部接口交给 agent-executor，再由 agent-executor 调用 agent-sandbox；ai-service 不直接访问 Docker 或任何沙箱，判题 Sandbox 只做正式判题。通过人工审核的草稿使用可恢复导入 saga 将题目信息和 `READY` 测试包提交给 problem-service。详见 [AI 题目草稿 Agent 架构](problem-draft-agent-architecture.md)。

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
