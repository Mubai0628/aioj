# AIOJ 功能交接说明

## 1. 交接范围

本分支基于学长仓库 `main` 创建，内容来源于 `Yuhao-Zhang666/aioj-my` 的
`feat/dashboard-recommendation-v1` 分支，并包含迁移时工作区中尚未提交的代码修改。
个人学习笔记、本机环境文件、数据库密码和 Docker 运行数据没有带入本分支。

建议通过 Pull Request 审核后再合并到 `main`，不要直接覆盖主分支。

### 与当前 `main`（含 #9）的合并说明

本分支基于当前 `main`（`feat: rebuild AI problem-draft generation on Agent Core V3 (#9)`）创建，
学生的改动以三方合并方式叠加，而不是整树覆盖：

- AI 出题（Agent Core V3）相关：保留 `main` 上 #9 的新实现；学生侧同名的旧实现不覆盖。
- 判题 worker：学生侧新增「所有提交都记录逐测试点结果」与编译内存/时间倍率参数，
  同时保留 `main` 上对运行时间、内存上限的封顶保护，两者合并共存。
- 镜像仓库与发布地址：学生仓库里的 `ghcr.io/xueyunji/...`、`XueyunJi/aioj` 是本机部署用的命名空间，
  本分支保留 `main` 原有的 `ghcr.io/mubai0628/...` 与 `Mubai0628/aioj`，避免破坏现有 CI 发布。
- 文档：调研报告中的真实百炼 API Key 已替换为占位符，仓库内不保留任何凭据。

## 2. 已完成的主要功能

### 首页推荐题目

- 接口：`GET /api/v1/tutor/recommendations?limit=6`
- 读取学生日常提交，排除比赛提交。
- 已通过题目不再推荐。
- 根据失败状态、题目标签和学习画像中的负向信号进行规则评分。
- 对 `binary_search`、`lower_bound`、`upper_bound`、二分查找等主题做别名归一化。
- 仅推荐公开、未删除、未归档的题目。
- 无历史提交时使用冷启动难度排序。

该功能当前使用结构化规则和学习画像，不依赖 Embedding，便于解释、测试和降级。

### 题目自然语言搜索

- 接口：`GET /api/v1/tutor/problems/semantic-search`。
- 题目索引文本包含题号、标题、难度、标签、题面和补充说明。
- 通过 `problem_index_chunks` 保存索引状态、模型、维度和向量。
- 查询文本和题目索引文本分别生成 Embedding，使用余弦相似度排序。
- 结合关键词命中分数，当前综合权重为向量 `0.75`、关键词 `0.25`。
- Embedding 服务不可用时降级到普通关键词查询；索引失败会保留失败状态，不能阻塞题库主流程。

当前没有接入 Jieba、HanLP 或 IK 等显式中文分词器。关键词检索使用大小写/空白归一化、`LIKE` 和 `contains`。
该功能是语义检索，不是完整 RAG；只有在检索结果继续作为上下文交给生成模型时，才构成“检索增强生成”。

### 比赛个人复盘

- 后端聚合比赛题目、提交次数、通过状态、错误模式、题目标签和代表性代码片段。
- 先用确定性规则生成有证据的薄弱候选，再调用 AI 组织成学生可读的中文复盘。
- 未提交的题目不直接判断为薄弱。
- AI 调用失败时仍保留确定性候选和练习建议。
- 学生端拆分展示比赛概况、题目状态、薄弱点、原因、代码详情和下一步建议。

复盘当前主要使用结构化数据加 AI 对话分析，不依赖 Embedding。

### 学习画像和证据展示

- 将提交分析和比赛复盘结果归纳为结构化画像信号，例如薄弱主题、置信度、证据数量和当前状态。
- 推荐服务读取画像中的负向主题作为附加排序信号，而不是直接读取 AI 长文本。
- 学生端按同一次提交的 `sourceId` 聚合证据，减少重复展示。
- 过滤内部字段，改为展示题目、提交结果、语言、算法标签、根因、代码详情和学习建议。

## 3. 主要代码位置

```text
apps/web-user-react/src/views/DashboardView.tsx
apps/web-user-react/src/views/ProblemsView.tsx
apps/web-user-react/src/views/ContestStudentPostmortemView.tsx
apps/web-user-react/src/views/ProfileView.tsx
backend/problem-service/src/main/java/com/aioj/next/problem/domain/TutorRecommendationService.java
backend/problem-service/src/main/java/com/aioj/next/problem/domain/ProblemSemanticSearchService.java
backend/problem-service/src/main/java/com/aioj/next/problem/domain/ProblemIndexService.java
backend/ai-service/src/main/java/com/aioj/next/ai/domain/StudentPostmortemAnalysisService.java
backend/ai-service/src/main/java/com/aioj/next/ai/domain/AiLearningProfileService.java
```

相关数据库迁移位于 `backend/api-contract/src/main/resources/db/migration/`，其中包含题目语义索引表等迁移。

## 4. 验证建议

在仓库根目录执行：

```powershell
npm ci --include=optional
npm run typecheck:react
npm run test:auth
npm run build:user:react
npm run build:admin:react
mvn -f backend/pom.xml test
git diff --check
```

重点测试场景：已 AC 题排除、失败标签优先、冷启动、比赛提交隔离、画像别名匹配、Embedding 失败回退、公开权限过滤、复盘不生成未提交题目，以及同一提交证据不重复展示。

## 5. 当前限制和后续方向

- 中文关键词搜索还没有显式分词。
- 搜索权重和推荐分数仍是可调整的工程规则，需要用真实查询和用户反馈继续校准。
- 画像主题别名需要随着题库标签持续扩充。
- Embedding 索引需要持续关注批量处理、失败重试、模型变更和数据更新。
- 题目搜索目前只返回检索结果，不自动生成题解；后续如接入生成模型，需要明确上下文边界和引用来源。
