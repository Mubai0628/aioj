# 交付架构

[English](../en/delivery-architecture.md)

## 目标

AIOJ 使用 GitHub Actions、私有 GHCR、不可变镜像 digest 与受保护的生产环境，把“源码版本、构建产物、生产运行版本”建立可追踪的一一关系。生产服务器不拉取 Git 工作树，也不现场构建源码。

## 交付流

```text
受审查的 main
   -> 正式 SemVer GitHub Release
   -> CI 测试与仓库卫生检查
   -> 构建八个 Linux/amd64 镜像
   -> GHCR 私有包 + SBOM + 构建证明
   -> service -> image@digest 清单
   -> production 人工批准
   -> 受限 SSH 部署门禁
   -> 健康检查与版本记录
```

九个应用镜像分别对应 gateway、auth、problem、ai、judge-worker、agent-executor、sandbox、web-user 和 web-admin。Agent 工具专用的 agent-sandbox 复用 sandbox 镜像的同一个 digest，只是配置和令牌不同。第三方基础镜像同样固定到经过审核的平台 digest。

## 不可变版本身份

生产版本由三类身份共同描述：

- Git Release 标签供人阅读；
- Git commit 对应源代码；
- OCI digest 对应实际运行镜像内容。

生产 Compose 只接受完整 `image@sha256:...`，不使用 `latest` 或可移动标签。Release 清单为每个服务保存 digest，并单独保存清单校验和。

## CI 与供应链证据

CI 对后端、React、Compose、Markdown 链接、仓库卫生和秘密泄露执行自动检查。镜像构建产生 SBOM 与 provenance 证明；第三方 Actions 固定到审核过的 commit SHA，降低供应链漂移。

后端测试包含 ai-service 的架构测试（ArchUnit，只在测试范围）：模型调用包只能经它的 `api` 子包使用，且不依赖持久层、agent-harness 或 ai-service 的其他代码；旧的模型调用路径和 ai-service 自己的 Agent 循环、工具管线都已删除，测试断言它们不再出现；ai-service 不使用 agent-harness 的内部包；事件只经统一通道的接口发布；ai-service 进程内不启动子进程，代码只在 agent-executor 里执行。

`backend/agent-harness` 是与领域无关的 Agent 循环和工具管线，它的架构测试要求：运行时只依赖 JDK 和 Jackson；各子包的 `internal` 包只在本子包内使用；依赖方向是 runtime → tool → context；源码里不出现判题领域的词。每条规则都有违规样例证明它会报错。

## 生产边界

- 生产根目录保存 image-only Compose、环境文件、当前/上一版本 digest 与部署历史，不保存 Git 工作树。
- 应用秘密与 GHCR 读取凭据只保存在服务器 root-only 配置，不进入 GitHub Release 或镜像层。
- GitHub 部署身份没有 Docker 组和通用 sudo，只能经过 forced command 调用 root-owned 部署入口。
- 部署入口只接受合法 Release 标签与清单校验和，并验证命名空间、平台与 digest。

## 数据与迁移

生产数据始终来自生产服务器，不能用工作站数据库、用户导出或本地测试包替代。结构迁移由 auth-service 的 Flyway 在受控启动阶段执行。迁移只向前；应用镜像回滚不会自动反向修改数据库。

## 启动依赖

生产栈按依赖关系形成有向图：基础设施先健康，auth 完成结构检查，problem 可用后才启动判题链，随后启动 AI、网关和 Web。健康检查只在依赖真正可服务后通过，避免容器“进程已启动但应用不可用”。

## 停止

后端镜像的入口是 `sh -c "exec java $JAVA_OPTS -jar /app/app.jar"`：shell 展开 `JAVA_OPTS` 后，`exec` 让 Java 接替 PID 1，`docker stop` 的 SIGTERM 才到得了 JVM，Spring Boot 的优雅关闭（每个阶段最多等 30 秒）和 `@PreDestroy`（例如 agent-executor 把缓存的编译产物从沙箱里删掉）才会执行，正常停止的退出码是 143。以前入口里没有 `exec`，PID 1 是 `sh`，SIGTERM 被忽略，每次停止都等满 10 秒后被强杀（137）。本地用正式镜像实测：六个后端服务空闲时 0.6–2.5 秒停下、退出码 143、日志里有优雅关闭的开始和完成；agent-executor 有一个 2 秒的运行在途时，请求照常返回，停止约 3.4 秒；停止时沙箱里的缓存产物被清掉。关闭超过 Docker 默认的 10 秒仍会被强杀，生产 Compose 没有设置 `stop_grace_period`，是否加、加多少在恢复部署时按实际在途请求的时长另行决定。

## 回滚模型

常规回滚恢复上一组镜像 digest，并继续使用当前数据卷。部署失败可以自动回滚镜像；数据库结构与已经产生的新数据不会自动回退。首次切换在重新开放写入前可以恢复完整旧栈；新版本产生写入后，旧卷即为过期恢复点，不能直接重新上线。

## 单节点影响

当前交付目标把应用、数据和判题合并到一个节点，因此发布验收同时关注容器健康、重启/OOM、宿主机内存与磁盘、消息队列、判题路径和特权 Sandbox 边界。旧双节点容量结果不能直接视为单节点 SLA。
