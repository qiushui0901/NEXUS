# NEXUS RAGFlow 风格知识库与 GitLab 可视化实现方案

> 状态：Draft
> 编写日期：2026-08-17
> 建议目标版本：0.9
> 范围：知识库管理、文档/分块状态、GitLab 配置与同步可视化

## 1. 方案结论

本次改造不复制 RAGFlow 的品牌和视觉皮肤，而是借鉴它的核心信息架构：

1. 知识库列表。
2. 文档处理状态。
3. 文档详情与分块检查。
4. 检索测试。
5. 可重试的错误反馈。

结合 NEXUS 已有的“需求知识 + 代码知识 + 版本 Wiki”能力，建议新增统一的
`/knowledge` 管理工作台，并保留 `/wiki` 作为面向阅读者的已发布知识页面。

GitLab 管理由当前仅支持管理 API 的方式，升级为：

1. 五步接入向导。
2. 项目同步状态列表。
3. 单项目同步时间线。
4. Webhook 配置检查。
5. 连接测试、手动同步、失败重试和禁用操作。

前端继续使用 Spring Boot 内置静态资源，不引入 Node 构建链。复用项目已经打包的
Vue 3 WebJar，将新页面拆成独立的 HTML、CSS 和 JavaScript 文件。

## 2. 目标与非目标

### 2.1 目标

- 用户能在一个页面看清每个知识库、文档和分块的当前状态。
- 处理中的文档显示当前阶段、进度、耗时和当前文件。
- 失败的文档或分块显示稳定错误码、可读原因和重试入口。
- 用户能检查父块、子块、来源位置、内容哈希和索引状态。
- 用户能使用自然语言测试检索结果，并定位命中的具体分块。
- 管理员不再需要通过 `curl` 完成 GitLab 项目接入。
- GitLab 接入过程包含即时校验，避免提交后才发现 URL、分支、Token 或 Webhook 错误。
- 现有知识检索、Wiki、代码索引和 GitLab 同步行为保持兼容。
- 后台任务失败时，最后一个已成功发布的索引仍然可用。

### 2.2 非目标

- 不重写现有 RAG 检索算法。
- 不在首期增加 GitLab OAuth；继续使用当前管理员提交 PAT 的模式。
- 不在首期提供在线编辑分块内容。
- 不允许用户查看已保存的 PAT、Webhook Secret 或其密文。
- 不把运行日志原文直接暴露到前端。
- 不把 `/wiki` 改造成运维管理页面；它继续承担已发布知识的阅读职责。

## 3. 当前实现评估

本节结论来自 CodeGraph 对当前代码的分析。

### 3.1 已有能力

| 能力 | 当前实现 | 可直接复用 |
|---|---|---|
| 全局知识导入状态 | `BootstrapState` | 状态、阶段、文件进度、错误和时间 |
| 知识导入 | `KnowledgeBootstrapService` | ZIP 扫描、读取、等待 Qdrant、入库 |
| 父子分块 | `RequirementIngestionService` | 清洗、父子分块、内容去重 |
| 分块存储 | `QdrantHybridStore` | 批量写入、校验新点、删除旧点、滚动读取 |
| 知识监控 API | `MonitorController` | 健康状态、分块总数、全局导入状态 |
| GitLab 管理 API | `GitLabIntegrationController` | 创建、列表、详情、同步、重试、禁用 |
| GitLab 状态机 | `GitLabProjectStatus` | `PENDING` 至 `READY/FAILED/DISABLED` |
| GitLab 安全 | `GitLabCredentialCipher` 等 | 凭据加密、Host 白名单、URL 校验 |
| Wiki 阅读页 | `static/wiki.html` | 项目、版本、功能知识和证据浏览 |
| Vue 运行时 | Vue 3 WebJar | 无外部 CDN、无需新增前端构建工具 |

### 3.2 当前缺口

| 缺口 | 影响 |
|---|---|
| `BootstrapState` 是单个全局快照 | 多项目或多个任务并发时无法准确展示 |
| 未持久化导入任务 | 应用重启后无法查看历史和失败阶段 |
| 未持久化文档处理状态 | 只能看到总文件数，不能看到每个文件 |
| 未持久化分块状态 | 无法回答“哪个块失败、为何失败、是否已索引” |
| `IngestResponse` 只返回分块总数 | 前端无法呈现处理明细 |
| GitLab `View` 只有当前状态 | 没有阶段时间线、任务历史和阶段耗时 |
| GitLab 创建接口直接触发后台任务 | 缺少提交前连接、分支和权限检查 |
| 没有 GitLab 管理页面 | 当前必须手写 JSON 并调用管理 API |
| `/wiki` 偏向知识阅读 | 不适合承担数据集、文档和分块运维 |

### 3.3 现有知识导入阶段

```text
prepare -> scan -> zip -> wait-qdrant -> ingest -> done
```

`ingest` 内部实际还包含：

```text
text.clean
  -> parent_child.chunk
  -> content.deduplicate
  -> embedding
  -> qdrant.upsert
  -> verifyVersion
  -> stale point cleanup
```

新状态模型应展开这些阶段，但不能改变“新点验证成功后才删除旧点”的现有一致性语义。

## 4. 目标信息架构

```text
NEXUS
├── 知识库 /knowledge
│   ├── 知识库概览
│   ├── 文档
│   │   └── 文档详情
│   │       ├── 处理过程
│   │       ├── 分块
│   │       └── 错误与重试
│   ├── 检索测试
│   └── 设置
├── GitLab /settings/gitlab
│   ├── 项目列表
│   ├── 新建接入向导
│   └── 项目详情
│       ├── 同步概览
│       ├── 任务时间线
│       ├── Webhook
│       └── 配置
├── Wiki /wiki
├── 版本中心 /versions
└── 代码工作台 /monitor
```

知识库和 GitLab 页面是“管理面”，Wiki 和代码工作台是“使用面”。两者通过项目、
版本、commit 和分块 ID 相互跳转。

## 5. 知识库页面设计

### 5.1 知识库概览

路由：`GET /knowledge`

首屏使用紧凑表格，不使用大面积营销卡片。

```text
┌──────────────────────────────────────────────────────────────────────┐
│ NEXUS  知识库   GitLab   Wiki   版本中心   代码工作台               │
├──────────────────────────────────────────────────────────────────────┤
│ 知识库                                      [刷新] [重新构建]        │
│ 项目 [全部 v]  类型 [全部 v]  状态 [全部 v]  [搜索_______________] │
├──────────────┬──────┬────────┬────────────┬──────────┬──────────────┤
│ 名称         │ 类型 │ 状态   │ 文档/分块  │ 当前版本 │ 最近更新     │
├──────────────┼──────┼────────┼────────────┼──────────┼──────────────┤
│ 封神需求知识 │ 需求 │ ● 就绪 │ 128 / 3260 │ 5.1      │ 3 分钟前     │
│ 订单服务代码 │ 代码 │ ◐ 索引 │ 846 / 72%  │ a15c9d2  │ 正在处理     │
│ 历史需求包   │ 需求 │ ! 失败 │ 41 / 760   │ 4.8      │ 查看原因     │
└──────────────┴──────┴────────┴────────────┴──────────┴──────────────┘
```

每行展示：

- 知识库名称和 `projectId`。
- 类型：需求、代码、Wiki 派生知识。
- 汇总状态和当前阶段。
- 文档数、成功数、失败数和分块总数。
- 需求版本或 Git commit。
- 最近成功发布时间。
- 当前任务进度。
- 行操作：查看、重新构建、检索测试、设置。

顶部汇总只保留四项：

- 就绪知识库。
- 处理中任务。
- 失败文档。
- 可检索分块。

### 5.2 知识库文档列表

路由：`GET /knowledge/{knowledgeBaseId}/documents`

```text
┌──────────────────────────────────────────────────────────────────────┐
│ 封神需求知识 / 文档                                                  │
│ [文档] [检索测试] [设置]                          [重新构建知识库]  │
├──────────────────────────────────────────────────────────────────────┤
│ 状态 [全部 v]  来源 [全部 v]  [搜索文件名/路径___________________] │
├───────────────┬──────────┬──────────────┬──────┬────────┬───────────┤
│ 文档          │ 状态     │ 处理阶段     │ 分块 │ 耗时   │ 更新时间  │
├───────────────┼──────────┼──────────────┼──────┼────────┼───────────┤
│ 角色系统.html │ ● 就绪   │ 6/6 已完成   │ 42   │ 1.8s   │ 14:31     │
│ 战斗规则.html │ ◐ 处理中 │ 向量化 64%   │ 87   │ 3.2s   │ 刚刚      │
│ 道具说明.html │ ! 失败   │ 文档解析     │ 0    │ 0.4s   │ [重试]    │
└───────────────┴──────────┴──────────────┴──────┴────────┴───────────┘
```

支持：

- 按状态、来源类型、版本筛选。
- 按文件名、路径和错误码搜索。
- 批量重试失败文档。
- 查看当前运行任务。
- 展开行查看错误摘要，不在表格中展示长堆栈。
- 处理中行每 2 秒刷新，稳定状态停止轮询。

### 5.3 文档详情与分块检查

路由：`GET /knowledge/{knowledgeBaseId}/documents/{documentId}`

这是本次改造的关键页面。

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ 战斗规则.html   ◐ 处理中  版本 5.1   87 个分块      [重试] [打开来源] │
├───────────────────┬─────────────────────────────────────────────────────┤
│ 处理过程          │ 分块  [全部 v] [状态 v] [搜索内容______________] │
│ ✓ 读取文件 0.1s   ├─────────────────────────────────────────────────────┤
│ ✓ 文本清洗 0.2s   │ #0001  ● 已索引  parent: p-001  child: 0           │
│ ✓ 父子分块 0.4s   │ 来源：rules/fight.html · section 3                  │
│ ✓ 内容去重 0.1s   │ “角色进入战斗后……”                                │
│ ◐ 向量化 64%      │ [查看父块] [复制 ID] [检索此块]                     │
│ ○ 写入 Qdrant     ├─────────────────────────────────────────────────────┤
│ ○ 发布版本        │ #0002  ! 向量化失败  EMBEDDING_TIMEOUT              │
│                   │ 已重试 2 次 · 最近失败 14:32:08                     │
│ 任务信息          │ [查看详情] [重试此块]                               │
│ run-20260817-001  │                                                     │
└───────────────────┴─────────────────────────────────────────────────────┘
```

分块行显示：

- 稳定分块 ID。
- 当前状态和阶段。
- `parentId`、父块顺序、子块顺序。
- 来源文件和来源定位。
- 子块正文摘要。
- 内容哈希短值。
- 稠密向量、稀疏向量是否成功生成。
- Qdrant 点是否已验证。
- 重试次数、最近错误码和错误摘要。

点击分块后打开右侧抽屉：

- 完整子块文本。
- 完整父块文本。
- 文档、版本、来源路径。
- 向量维度和索引时间，不返回原始向量。
- 被哪些 Wiki 页面或证据引用。
- 在当前版本中的检索测试入口。

### 5.4 处理阶段轨道

文档详情始终显示统一阶段轨道：

```text
DISCOVER -> PARSE -> CLEAN -> CHUNK -> DEDUPLICATE
         -> EMBED -> INDEX -> VERIFY -> PUBLISH
```

每一阶段展示：

- `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`SKIPPED`。
- 开始时间、结束时间和耗时。
- 输入数、输出数和过滤数。
- 稳定错误码和脱敏错误说明。

“发布”表示新版本已通过 Qdrant 校验并可被检索，不等同于仅完成 upsert。

### 5.5 检索测试

路由：`GET /knowledge/{knowledgeBaseId}/retrieval`

```text
┌──────────────────────────────────────────────────────────────────────┐
│ 检索测试                                                            │
│ 查询 [角色死亡后装备如何处理？________________________] [运行检索] │
│ 版本 [5.1 v]  Top K [10]  [✓] 稠密  [✓] 稀疏  [✓] 重排            │
├──────────────────────────────────────────────────────────────────────┤
│ 1  0.873  战斗规则.html / chunk #0042              [查看分块]      │
│    命中方式：Hybrid + Rerank · parent p-018                         │
│    “角色死亡后，已装备物品……”                                     │
├──────────────────────────────────────────────────────────────────────┤
│ 2  0.811  道具说明.html / chunk #0017              [查看分块]      │
└──────────────────────────────────────────────────────────────────────┘
```

首期复用现有检索链路，不新增算法。前端展示：

- 原始混合检索分数。
- 重排后顺序。
- 来源文档和分块 ID。
- 父块上下文。
- 检索阶段是否降级。
- 可选的调试信息，仅管理员可见。

## 6. 状态与视觉契约

状态不能只依靠颜色识别，必须同时提供图标和中文文本。

### 6.1 汇总状态

| 状态 | 中文 | 颜色 | 图标语义 | 说明 |
|---|---|---|---|---|
| `IDLE` | 未开始 | 灰 | 空心圆 | 尚未运行 |
| `QUEUED` | 排队中 | 蓝灰 | 时钟 | 已创建任务 |
| `RUNNING` | 处理中 | 蓝 | 旋转进度 | 后台正在执行 |
| `READY` | 就绪 | 绿 | 对勾圆 | 已发布且可检索 |
| `PARTIAL` | 部分完成 | 黄 | 半圆 | 部分文档失败 |
| `FAILED` | 失败 | 红 | 警告三角 | 当前任务失败 |
| `STALE` | 待更新 | 橙 | 刷新 | 来源已变更，索引未更新 |
| `DISABLED` | 已停用 | 灰 | 禁用 | 不再自动处理 |

### 6.2 分块状态

| 状态 | 含义 |
|---|---|
| `PENDING` | 已发现，尚未处理 |
| `CHUNKED` | 已产生父子分块 |
| `EMBEDDING` | 正在生成向量 |
| `INDEXING` | 正在写入 Qdrant |
| `READY` | 已写入并验证 |
| `FAILED` | 当前阶段失败 |
| `EXCLUDED` | 空文本或去重后被排除 |
| `STALE` | 来源已变化，仍使用旧索引 |

### 6.3 GitLab 状态映射

保留后端现有枚举，前端映射如下：

| 后端状态 | 前端文案 | 阶段 |
|---|---|---|
| `PENDING` | 等待同步 | 排队 |
| `CLONING` | 正在克隆仓库 | 仓库准备 |
| `SYNCING` | 正在同步分支 | 拉取 commit |
| `INDEXING` | 正在建立代码索引 | 发布索引 |
| `READY` | 已同步 | 完成 |
| `FAILED` | 同步失败 | 失败 |
| `DISABLED` | 已停用 | 终止 |

## 7. GitLab 可视化设计

### 7.1 GitLab 项目列表

路由：`GET /settings/gitlab`

```text
┌──────────────────────────────────────────────────────────────────────┐
│ GitLab 项目                                      [接入 GitLab 项目] │
├──────────────────────────────────────────────────────────────────────┤
│ 项目          │ 分支 │ 状态      │ 索引 commit │ 目标 commit │ 操作 │
├───────────────┼──────┼───────────┼─────────────┼─────────────┼──────┤
│ order-service │ main │ ● 已同步  │ a15c9d2     │ a15c9d2     │ ...  │
│ game-client   │ dev  │ ◐ 索引中  │ b827e10     │ c933fa1     │ 查看 │
│ admin-web     │ main │ ! 失败    │ 68d991a     │ 7ae30bb     │ 重试 │
└───────────────┴──────┴───────────┴─────────────┴─────────────┴──────┘
```

差异状态必须直观显示：

- `lastIndexedSha == targetSha`：已同步。
- `lastIndexedSha != targetSha`：存在版本偏离。
- `lastIndexedSha == null`：尚无可用索引。
- 失败但存在 `lastIndexedSha`：旧索引仍可用。

### 7.2 五步接入向导

路由：`GET /settings/gitlab/new`

#### 步骤 1：连接

字段：

- GitLab Clone URL。
- Personal Access Token。
- Webhook Secret，可点击生成。

即时检查：

- 必须是 HTTPS。
- URL 不得包含用户名、密码、查询参数或 fragment。
- Host 必须在服务端白名单。
- Token 不回显，离开当前步骤后只显示“已提供”。

操作：

- “测试连接”调用后端校验远端仓库和 Token。
- 显示 GitLab Host、仓库路径和可读取结果。

#### 步骤 2：项目与分支

字段：

- NEXUS `projectId`，由仓库名生成，可修改。
- 显示名称。
- 项目分组和端类型。
- 分支。
- Git path。

即时检查：

- `projectId` 是否与静态项目或已接入项目冲突。
- 分支是否存在。
- 目标 commit SHA。
- 仓库是否为空。

#### 步骤 3：索引配置

字段：

- 需求 collection。
- 代码 collection。
- 默认排除目录，只提供勾选和高级编辑。
- 首次同步策略：立即同步或仅保存配置。

默认值由 `projectId` 自动生成，普通用户不需要理解 Qdrant collection。
高级设置默认折叠。

#### 步骤 4：Webhook

页面生成：

- Webhook URL。
- Secret 的当前会话副本。
- 需要勾选的 GitLab 事件。
- SSL verification 提示。

提供：

- 复制按钮。
- “我已配置，检查 Webhook”操作。
- 最近一次 Webhook 接收时间和结果。

服务端无法主动证明 Webhook 已在 GitLab 创建，因此检查结果分为：

- 尚未收到事件。
- 最近验证成功。
- Token 不匹配。
- 分支被忽略。
- 请求格式错误。

#### 步骤 5：确认与首次同步

提交前展示脱敏摘要。创建成功后不立即关闭向导，而是进入实时阶段页：

```text
✓ 保存项目
✓ 注册项目
◐ 克隆仓库  68%
○ 解析代码
○ 建立索引
○ 发布项目
```

### 7.3 GitLab 项目详情

路由：`GET /settings/gitlab/{projectId}`

页面包含：

- 当前状态。
- 跟踪分支。
- 当前远端目标 commit。
- 最后成功索引 commit。
- 最近成功同步时间。
- 旧索引是否仍可用。
- 仓库、索引和 Webhook 三组健康状态。
- 手动同步、失败重试、停用操作。

任务时间线示例：

```text
14:31:08  Push webhook accepted         c933fa1
14:31:09  Repository sync started
14:31:11  Fast-forward completed        b827e10 -> c933fa1
14:31:12  Incremental index started     18 changed files
14:31:18  Index verified
14:31:19  Project READY                 c933fa1
```

失败节点只显示稳定错误码和脱敏信息；管理员可通过日志关联 ID 查服务端日志。

## 8. 前端实现方式

### 8.1 技术选择

继续使用：

- Spring Boot 静态资源。
- Vue 3 WebJar：`/webjars/vue/3.5.13/dist/vue.global.prod.js`。
- 原生 `fetch`。
- 项目现有 API Key / Gateway Header 认证。

不新增：

- npm。
- Vite/Webpack。
- 外部 CDN。
- 新的前端 UI 框架。

### 8.2 文件结构

```text
src/main/resources/static/
├── knowledge.html
├── gitlab-settings.html
├── assets/
│   ├── knowledge-app.css
│   ├── knowledge-api.js
│   ├── knowledge-app.js
│   ├── gitlab-app.js
│   ├── status-contract.js
│   └── icons.js
├── wiki.html
├── monitor.html
└── home.html
```

`icons.js` 使用项目内可用的图标资源；若没有图标库，则使用简洁字符图标并补充
`aria-label`，不手绘复杂 SVG。

### 8.3 页面路由

新增轻量页面控制器：

```text
GET /knowledge                 -> knowledge.html
GET /knowledge/**              -> knowledge.html
GET /settings/gitlab           -> gitlab-settings.html
GET /settings/gitlab/**        -> gitlab-settings.html
```

前端从 `location.pathname` 解析当前子页面。保留 `/wiki` 原路由。

### 8.4 状态管理

页面状态分为：

- URL 状态：项目、知识库、文档、分块、筛选条件。
- 服务端状态：任务、文档和分块状态。
- 临时状态：抽屉、确认框、表单步骤。

筛选条件写入 query string，刷新后保持。PAT 和 Webhook Secret 只保存在当前表单内存，
不写入 URL、`localStorage` 或日志。

### 8.5 刷新策略

MVP 采用自适应轮询：

- 运行中：每 2 秒。
- 排队中：每 3 秒。
- 稳定状态：停止轮询，保留手动刷新。
- 页面不可见：暂停轮询。
- 连续三次失败：退避到 10 秒并显示连接提示。

V2 可增加 SSE：

```text
GET /api/knowledge/events?projectId=...
GET /api/integrations/gitlab/projects/{projectId}/events
```

首期不以 SSE 作为上线阻塞项。

## 9. 后端领域模型

Qdrant 继续存储可检索内容，但不能作为任务状态的唯一数据源。新增轻量状态目录，
建议首期使用 SQLite，并通过 Repository 接口隔离实现。

### 9.1 `knowledge_base`

| 字段 | 说明 |
|---|---|
| `id` | 知识库 ID |
| `project_id` | NEXUS 项目 ID |
| `name` | 显示名称 |
| `type` | `REQUIREMENT` / `CODE` / `WIKI` |
| `collection` | Qdrant collection |
| `source_type` | `ZIP` / `GITLAB` / `GENERATED` |
| `status` | 汇总状态 |
| `published_revision` | 已发布版本或 commit |
| `target_revision` | 当前目标版本或 commit |
| `last_published_at` | 最近发布时间 |
| `created_at` / `updated_at` | 审计时间 |

唯一约束：

```text
UNIQUE(project_id, type)
UNIQUE(collection)
```

### 9.2 `knowledge_ingestion_run`

| 字段 | 说明 |
|---|---|
| `id` | 任务 ID |
| `knowledge_base_id` | 所属知识库 |
| `trigger_type` | `BOOTSTRAP` / `MANUAL` / `GITLAB` / `RETRY` |
| `status` | 任务状态 |
| `phase` | 当前阶段 |
| `target_revision` | 目标版本 |
| `files_total` / `files_processed` | 文件进度 |
| `chunks_total` / `chunks_ready` / `chunks_failed` | 分块进度 |
| `error_code` / `error_message` | 脱敏错误 |
| `started_at` / `finished_at` | 时间 |
| `correlation_id` | 日志关联 ID |

### 9.3 `knowledge_document`

| 字段 | 说明 |
|---|---|
| `id` | 稳定文档 ID |
| `knowledge_base_id` | 所属知识库 |
| `run_id` | 最近处理任务 |
| `source_path` | 来源路径 |
| `source_hash` | 来源内容哈希 |
| `revision` | 版本或 commit |
| `status` / `phase` | 当前状态和阶段 |
| `chunk_count` | 产生的分块数 |
| `excluded_chunk_count` | 去重或空内容数量 |
| `error_code` / `error_message` | 错误 |
| `started_at` / `finished_at` | 时间 |

稳定 ID 建议：

```text
sha256(knowledgeBaseId + ":" + normalizedSourcePath)
```

### 9.4 `knowledge_chunk_status`

| 字段 | 说明 |
|---|---|
| `chunk_id` | 复用现有 `ChunkRecord.id` |
| `document_id` | 所属文档 |
| `run_id` | 产生该块的任务 |
| `parent_id` | 父块 ID |
| `parent_order` / `child_order` | 顺序 |
| `content_hash` | 内容哈希 |
| `status` / `phase` | 状态和阶段 |
| `dense_ready` / `sparse_ready` | 向量状态 |
| `qdrant_verified` | 是否已验证存在 |
| `retry_count` | 重试次数 |
| `error_code` / `error_message` | 错误 |
| `indexed_at` | 索引时间 |

正文不在 SQLite 重复保存。详情接口从 Qdrant 读取已发布内容；运行中的暂存文本只在必要时
保存受限摘要，避免状态库膨胀。

### 9.5 `knowledge_stage_event`

记录任务、文档或分块的阶段事件：

```text
id, run_id, entity_type, entity_id, stage, status,
input_count, output_count, excluded_count,
error_code, error_message, occurred_at
```

事件表用于时间线和排障；当前状态字段用于快速列表查询。

## 10. 后端 API 设计

### 10.1 知识库 API

```text
GET  /api/knowledge-bases
GET  /api/knowledge-bases/{id}
POST /api/knowledge-bases/{id}/rebuild

GET  /api/knowledge-bases/{id}/runs
GET  /api/knowledge-bases/{id}/runs/{runId}

GET  /api/knowledge-bases/{id}/documents
GET  /api/knowledge-bases/{id}/documents/{documentId}
POST /api/knowledge-bases/{id}/documents/{documentId}/retry

GET  /api/knowledge-bases/{id}/documents/{documentId}/chunks
GET  /api/knowledge-bases/{id}/chunks/{chunkId}
POST /api/knowledge-bases/{id}/chunks/{chunkId}/retry

POST /api/knowledge-bases/{id}/retrieval-tests
```

列表 API 必须支持分页，禁止一次返回整个知识库：

```text
page, size, status, phase, sourceType, query, sort
```

### 10.2 文档列表响应示例

```json
{
  "items": [
    {
      "documentId": "doc_...",
      "sourcePath": "rules/fight.html",
      "revision": "5.1",
      "status": "RUNNING",
      "phase": "EMBED",
      "progress": {
        "completed": 56,
        "total": 87,
        "percent": 64
      },
      "chunkCount": 87,
      "excludedChunkCount": 4,
      "error": null,
      "startedAt": "2026-08-17T06:31:02Z",
      "updatedAt": "2026-08-17T06:31:08Z"
    }
  ],
  "page": 0,
  "size": 50,
  "total": 128
}
```

### 10.3 GitLab 现有 API

下列接口已经存在，应保持兼容：

```text
POST   /api/integrations/gitlab/projects
GET    /api/integrations/gitlab/projects
GET    /api/integrations/gitlab/projects/{projectId}
POST   /api/integrations/gitlab/projects/{projectId}/sync
POST   /api/integrations/gitlab/projects/{projectId}/retry
DELETE /api/integrations/gitlab/projects/{projectId}
```

### 10.4 GitLab 新增 API

```text
POST /api/integrations/gitlab/validate-connection
POST /api/integrations/gitlab/validate-project
POST /api/integrations/gitlab/projects/validate-config

GET  /api/integrations/gitlab/projects/{projectId}/jobs
GET  /api/integrations/gitlab/projects/{projectId}/jobs/{jobId}
GET  /api/integrations/gitlab/projects/{projectId}/webhook-status
POST /api/integrations/gitlab/projects/{projectId}/webhook-secret/rotate
```

校验接口只返回脱敏结果：

```json
{
  "valid": true,
  "host": "gitlab.example.com",
  "repository": "commerce/order-service",
  "branch": "main",
  "targetSha": "c933fa1...",
  "checks": [
    {"code": "HOST_ALLOWED", "status": "PASSED", "message": "Host 已允许"},
    {"code": "TOKEN_READABLE", "status": "PASSED", "message": "仓库可读取"}
  ]
}
```

不得返回：

- Token。
- Webhook Secret。
- 带凭据的 URL。
- 服务端绝对仓库路径。
- 原始 Git 命令输出。

## 11. 导入链路改造

### 11.1 状态记录器

新增：

```java
public interface KnowledgeIngestionTracker {
    RunHandle startRun(...);
    void documentDiscovered(...);
    void stageStarted(...);
    void stageSucceeded(...);
    void stageFailed(...);
    void chunksCreated(...);
    void chunksVerified(...);
    void runPublished(...);
}
```

业务服务只依赖该接口。SQLite 实现负责事务、当前状态和事件历史。

### 11.2 `KnowledgeBootstrapService`

改造点：

- 用持久化 run 替代仅依赖全局 `BootstrapState`。
- `BootstrapState` 暂时保留为兼容适配器。
- 每发现一个 ZIP 条目创建或更新 `knowledge_document`。
- 将当前文件、总数和阶段写入 run。
- 重启后把未结束任务标记为 `INTERRUPTED`，不伪装成仍在运行。

### 11.3 `RequirementIngestionService`

改造点：

- 支持传入 `runId` 和文档上下文。
- 每个 `KnowledgeEntry` 单独记录清洗、分块和去重结果。
- 产生 `ChunkRecord` 后批量写入分块状态。
- 保留现有稳定分块 ID 算法。
- 空文本和重复块记录为统计或 `EXCLUDED`，不写入 Qdrant。

### 11.4 `QdrantHybridStore`

保持当前发布顺序：

1. 收集旧点 ID。
2. 分批生成向量并 upsert 新点。
3. 校验所有新点存在。
4. 将新分块标记为 `READY`。
5. 删除不再存在的旧点。
6. 发布 run。

为支持分块进度，给 `writePointBatches` 增加批次回调，但不把前端状态逻辑写入存储层。

若某个批次失败：

- run 标记为 `FAILED`。
- 已完成批次标记为 `INDEXED_UNPUBLISHED` 或统一映射为 `FAILED`。
- 前端明确显示“本次发布失败，上一版索引仍可用”。
- 不删除旧点。

首期的“重试单个分块”可内部重新执行整个文档，避免破坏文档级去重和版本发布语义。
前端按钮可叫“从此分块错误重试文档”，而不是承诺独立发布一个块。

## 12. GitLab 链路改造

### 12.1 扩展只读视图

`GitLabManagedProject.View` 建议新增：

```text
syncAvailable
indexAvailable
revisionDrift
lastSuccessfulSyncAt
lastWebhookAt
activeJobId
activePhase
errorCode
errorMessage
```

不直接用 `lastError` 承担所有错误信息，应拆为稳定错误码和脱敏说明。

### 12.2 任务历史

为 GitLab 同步增加持久化 job：

```text
jobId, projectId, triggerType, status, phase,
sourceSha, targetSha, changedFiles,
startedAt, finishedAt, errorCode, errorMessage, correlationId
```

`GitLabSyncService` 每次 `enqueue` 创建 job，并在：

- clone 开始/结束。
- fetch 开始/结束。
- checkout 完成。
- 全量或增量索引开始/结束。
- 项目发布。

写入阶段事件。

### 12.3 提交前校验

创建项目之前依次执行：

1. Clone URL 结构校验。
2. Host 白名单和地址策略校验。
3. Token 只读访问校验。
4. 分支存在性校验。
5. `projectId` 冲突校验。
6. collection 冲突校验。
7. Webhook Secret 强度校验。

校验与正式 clone 复用同一安全组件，避免前后规则漂移。

## 13. 权限与安全

| 操作 | 建议权限 |
|---|---|
| 查看知识库和已发布分块 | `PUBLIC_READ` 或项目读权限 |
| 查看运行中任务和错误摘要 | 项目读权限 |
| 触发重建、文档重试 | `WRITE` |
| 查看 GitLab 项目状态 | `ADMIN` |
| 创建、重试、同步、停用 GitLab | `ADMIN` |
| 轮换 Webhook Secret | `ADMIN` |

安全要求：

- 所有列表和详情 API 执行项目访问校验。
- 错误消息经过统一 sanitizer。
- PAT、Webhook Secret 使用 `password` 输入框且不可读取回显。
- Secret 只在创建或轮换时显示一次。
- 前端不记录敏感表单，不发送到监控日志。
- 所有同步、重试、停用和 Secret 轮换写审计事件。
- GitLab 前端只在 `app.rag.gitlab.enabled=true` 时显示入口。

## 14. 与现有页面的关系

### 14.1 `home.html`

- 将“知识库”入口从 `/wiki` 调整为 `/knowledge`。
- 同时保留“浏览 Wiki”快捷入口。
- 项目状态摘要可跳转到对应知识库或 GitLab 项目详情。

### 14.2 `wiki.html`

- 保留当前项目/版本/功能阅读体验。
- 增加“管理知识库”链接。
- 来源证据可以跳转到分块详情。
- 已发布页面不展示运行中、未发布的分块。

### 14.3 `monitor.html`

- 保留代码图和开发计划能力。
- 项目索引状态链接到 GitLab 项目详情或代码知识库详情。
- 不在该页面重复实现 GitLab 配置表单。

## 15. 测试方案

### 15.1 后端单元测试

- 状态机只允许合法转换。
- 任务、文档和分块汇总状态计算正确。
- 分页、状态筛选和项目隔离正确。
- Qdrant 批次成功时逐步更新进度。
- Qdrant 校验失败时不删除旧点。
- 重试文档不会生成重复分块 ID。
- GitLab 校验接口不泄露 Secret 和绝对路径。
- GitLab 状态映射和 revision drift 计算正确。

### 15.2 控制器测试

- 权限注解和项目访问守卫。
- `404`、`409`、`422` 状态契约。
- 分页响应结构。
- 失败响应包含稳定错误码和 correlation ID。
- GitLab 功能关闭时页面入口和 API 均不可用。

### 15.3 前端静态契约测试

沿用当前 `ClassPathResource` 测试风格，新增：

```text
KnowledgeManagementPageTest
GitLabSettingsPageTest
```

检查：

- Vue 从 WebJar 加载，不使用 CDN。
- 页面包含知识库、文档、分块和检索 API。
- GitLab 向导不把 Token 写入 `localStorage`。
- 页面有空状态、加载状态、失败状态和重试操作。
- 状态不是只靠 CSS 颜色表达。
- 所有动态文本经过转义，不使用不受控 `v-html`。

### 15.4 浏览器验收

桌面：

- 1440 × 900。
- 1280 × 720。

移动端：

- 390 × 844。

必须验证：

- 表格在窄屏转为可读列表。
- 长路径、commit、错误信息不溢出。
- 分块抽屉不遮挡主要操作。
- 轮询不会造成布局跳动。
- 键盘可完成筛选、打开详情和关闭抽屉。
- `prefers-reduced-motion` 下无强制动画。

## 16. 分阶段实施

### Phase 0：契约与原型，1 至 2 人日

- 确认状态枚举和错误码。
- 确认知识库、文档、分块 API 响应。
- 完成知识库列表、文档详情和 GitLab 向导低保真原型。
- 确认 `/knowledge` 与 `/wiki` 的职责边界。

交付标准：前后端可基于固定 JSON 并行开发。

### Phase 1：持久化状态与查询 API，4 至 6 人日

- 新增知识状态 Repository 和 SQLite schema。
- 新增 run、document、chunk、stage event 模型。
- 接入 `KnowledgeBootstrapService` 和 `RequirementIngestionService`。
- 给 Qdrant 批量写入增加进度回调。
- 新增知识库、文档、分块查询 API。
- 保留 `BootstrapState` 兼容接口。

交付标准：不依赖前端即可通过 API 查看每个文档和分块状态。

### Phase 2：知识库管理前端，4 至 5 人日

- 新增 `/knowledge` 页面和静态资源。
- 实现知识库列表、文档列表、阶段轨道和分块抽屉。
- 实现筛选、分页、自适应轮询和重试。
- 实现检索测试页。
- 修改首页和 Wiki 的跳转入口。

交付标准：管理员能够定位失败文档和具体分块，普通读者仍可使用 Wiki。

### Phase 3：GitLab 可视化，3 至 5 人日

- 新增连接、项目和配置校验 API。
- 新增 GitLab job 与阶段事件。
- 扩展项目只读视图。
- 实现项目列表、五步向导和项目详情。
- 实现 Webhook 状态与 Secret 轮换。

交付标准：管理员不使用命令行即可完成接入、观察首次索引并处理失败。

### Phase 4：质量、迁移与发布，2 至 3 人日

- 增加单元、控制器和静态页面测试。
- 完成 Playwright 桌面/移动验收。
- 为旧项目生成初始 `knowledge_base` 记录。
- 增加功能开关和回滚说明。
- 更新用户文档和 `CHANGELOG.md`。

预计总工作量：14 至 21 人日。前后端并行时可压缩日历时间。

## 17. 建议任务拆分

| 优先级 | 任务 | 主要文件/模块 |
|---|---|---|
| P0 | 状态模型和 SQLite schema | `knowledge/status` 新包 |
| P0 | 导入链路埋点 | `KnowledgeBootstrapService`、`RequirementIngestionService` |
| P0 | Qdrant 批次进度 | `QdrantHybridStore` |
| P0 | 知识查询 API | `web`、`knowledge/status` |
| P0 | 知识库管理页面 | `static/knowledge.html`、`static/assets/*` |
| P0 | GitLab 校验 API | `integration/gitlab` |
| P0 | GitLab 管理页面 | `static/gitlab-settings.html` |
| P1 | GitLab job 时间线 | `GitLabSyncService`、项目存储 |
| P1 | 检索测试页 | 复用现有 retrieval pipeline |
| P1 | Wiki 到分块的深链 | `wiki.html` |
| P1 | Webhook 状态与 Secret 轮换 | GitLab controller/store |
| P2 | SSE 实时推送 | 新 event controller |
| P2 | 分块内容编辑与禁用 | 后续版本 |

## 18. 验收标准

### 18.1 知识库

- 用户能在三次点击内从知识库列表进入任意失败分块详情。
- 文档处理时能看到当前阶段和百分比。
- 每个已发布分块都能看到来源、父块、子块和索引状态。
- 文档失败后可以从页面重试。
- 新任务失败时，页面明确标识上一版索引是否仍可用。
- 10 万分块规模下，列表使用服务端分页，不一次加载全部数据。
- 应用重启后仍可查看历史任务，未完成任务显示为中断或恢复中。

### 18.2 GitLab

- 管理员能通过表单完成当前 `CreateProject` 的全部字段配置。
- Clone URL、Host、Token、分支和项目 ID 在提交前可验证。
- 首次同步能显示 `PENDING -> CLONING -> SYNCING -> INDEXING -> READY`。
- `lastIndexedSha` 与 `targetSha` 不一致时有明确偏离提示。
- 失败时可重试，停用时需要确认。
- API 和前端均不返回或持久化明文 Secret。
- Webhook 最近状态、目标分支和处理结果可见。

### 18.3 兼容性

- `/wiki`、`/versions`、`/monitor` 原有主要行为不回归。
- 现有 GitLab 管理 API 保持兼容。
- GitLab 功能关闭时，现有静态项目正常工作。
- 现有 `MonitorController.status` 在过渡期继续可用。

## 19. 发布与回滚

建议增加功能开关：

```yaml
app:
  knowledge-management:
    enabled: false
    database-path: ./data/knowledge-management.db
  rag:
    gitlab:
      ui-enabled: false
```

发布步骤：

1. 先上线状态表和写入逻辑，不开放新页面。
2. 验证状态记录不影响现有导入耗时和 Qdrant 发布。
3. 对管理员开放知识库页面。
4. 对管理员开放 GitLab 页面。
5. 稳定后将首页“知识库”入口切到 `/knowledge`。

回滚：

- 关闭两个 UI 开关后恢复旧入口。
- 新状态库是旁路元数据，不作为检索必需依赖。
- 状态写入失败应降级记录日志，不能阻断原有索引发布；稳定后再提高一致性要求。
- 不删除旧 `BootstrapState` 和 GitLab API，至少保留一个版本。

## 20. 需要同步更新的文档

实现时同步更新：

- `docs/user-guide.md`
- `docs/gitlab-auto-onboarding-guide.md`
- `docs/manual-smoke-test.md`
- `docs/retrieval-status-contract.md`
- `CHANGELOG.md`

## 21. 推荐实施顺序

优先完成“状态数据可靠”，再做视觉层：

```text
状态契约
  -> 持久化任务/文档/分块
  -> 查询 API
  -> 知识库 UI
  -> GitLab 校验与任务历史
  -> GitLab UI
  -> 深链、SSE 和体验增强
```

这样可以避免先做出漂亮页面，却仍然只能显示全局模拟进度。首个可交付里程碑应是：
**管理员能看到每个文档的真实处理阶段，并能进入每个已发布分块查看来源和索引状态。**
