# RAGFlow 风格知识库与 GitLab 管理工作台

## Goal

按照 `docs/ragflow-style-knowledge-gitlab-ux-implementation-plan.md` 建立 NEXUS 的统一知识库管理面和 GitLab 可视化接入面。管理员应能看到真实的文档/分块处理状态、定位失败原因、测试检索效果，并在不使用 `curl` 的情况下完成 GitLab 项目接入、同步观察和失败处理。

## Background

- 当前 `BootstrapState` 只保存一个内存快照，无法展示多项目历史、逐文档状态和应用重启前的任务。
- `RequirementIngestionService` 只在整批完成后返回分块总数，处理中间阶段不可查询。
- Qdrant 已实现“先写新点、验证成功后再清理旧点”的发布语义，新状态能力不得破坏该语义。
- GitLab 自动接入已有项目 CRUD、同步、重试、停用和 Webhook，但缺少提交前校验、任务历史和管理页面。
- 前端使用 Spring Boot 静态资源、Vue 3 WebJar 和原生 `fetch`，不引入 Node 构建链。
- 当前工作区包含尚未提交的 GitLab Review 修复，本任务必须保留并兼容这些改动。

## Requirements

### R1 知识状态目录

- 新增可配置、可持久化的 SQLite 状态目录，保存知识库、导入任务、文档、分块和阶段事件。
- 应用启动时将未完成任务标记为 `INTERRUPTED`，不得继续显示为运行中。
- 状态库是旁路元数据；记录失败应被日志捕获，不得阻断原有索引发布。
- 分块正文继续由 Qdrant 保存，SQLite 只保存状态、哈希、来源、顺序和错误摘要。

### R2 导入链路埋点

- `KnowledgeBootstrapService` 创建任务并记录扫描、读取、等待 Qdrant、导入和发布阶段。
- `RequirementIngestionService` 按 `KnowledgeEntry` 记录清洗、分块、去重结果，并保存稳定文档 ID 和分块状态。
- `QdrantHybridStore` 提供批次进度回调，在写入和验证成功后更新分块状态。
- 新版本校验失败时旧索引仍然可用，状态和页面必须明确表达这一点。

### R3 知识查询与操作 API

- 提供知识库、任务、文档、分块的分页查询和详情 API。
- 提供知识库重建、文档重试和分块错误触发的文档级重试。
- 提供复用现有检索链路的检索测试 API，并返回来源、分块、父块、原始分数、最终顺序和降级信息。
- 所有项目级 API 必须执行现有项目访问校验。

### R4 知识库管理页面

- 新增 `/knowledge` 及子路径页面，包含知识库概览、文档列表、文档详情、阶段轨道、分块抽屉和检索测试。
- 列表使用服务端分页；运行中自适应轮询，页面不可见时暂停，稳定状态停止轮询。
- 页面必须有加载、空、失败和重试状态；状态同时使用图标与中文文本表达。
- 修改首页、Wiki 和监控页入口，保留 `/wiki` 的阅读职责。

### R5 GitLab 可视化能力

- 新增 Clone URL/Token/分支/项目 ID/collection 的提交前校验 API，复用正式接入的安全规则。
- 持久化 GitLab 同步任务和阶段事件，项目视图显示 revision drift、旧索引可用性和最近成功时间。
- 记录最近 Webhook 接收状态；支持查询状态和轮换 Webhook Secret，Secret 只在创建或轮换响应中出现一次。
- 新增 `/settings/gitlab` 及子路径页面，包含项目列表、五步接入向导、项目详情、任务时间线和 Webhook 状态。
- PAT、Webhook Secret 不得写入 URL、`localStorage`、日志或可读取的项目详情响应。

### R6 配置、兼容与发布

- 新增 `app.knowledge-management.enabled/database-path` 和 `app.rag.gitlab.ui-enabled` 功能开关。
- 功能关闭时，现有 Wiki、版本中心、监控、静态项目和 GitLab 管理 API 保持可用。
- 更新 `.env.example`、用户指南、GitLab 指南、手工冒烟文档和 `CHANGELOG.md`。
- 不修改个人文件 `面试问答全解-NEXUS.md`。

## Out Of Scope

- GitLab OAuth 和服务端自动创建 GitLab Webhook。
- SSE 实时推送，首版使用自适应轮询。
- 在线编辑分块正文。
- 独立发布单个分块；首版分块重试重新执行所属文档。
- 将运行日志原文、原始向量、PAT 或 Webhook Secret 暴露给前端。

## Acceptance Criteria

- [ ] 应用重启后仍能查询历史任务，未结束任务被标记为 `INTERRUPTED`。
- [ ] 管理员能从知识库列表在三次点击内进入失败文档或分块详情。
- [ ] 文档处理中可见当前阶段、进度、耗时、当前文件和稳定错误码。
- [ ] 已发布分块可查看来源、父子关系、哈希、向量状态和 Qdrant 验证状态。
- [ ] 状态写入失败不阻断现有导入；Qdrant 校验失败不删除旧点。
- [ ] 所有列表 API 分页，项目隔离和权限校验正确。
- [ ] 检索测试复用现有混合检索/重排链路，并能跳转到命中分块。
- [ ] 管理员可在页面完成 GitLab 项目配置、连接测试、创建、首次同步观察、手动同步、重试和停用。
- [ ] GitLab 项目详情显示 `lastIndexedSha`、`targetSha`、revision drift、旧索引可用性和任务时间线。
- [ ] API、HTML 和 JavaScript 均不回显或持久化明文 PAT；Secret 只在创建或轮换时返回一次。
- [ ] `/wiki`、`/versions`、`/monitor` 和现有 GitLab API 的主要行为无回归。
- [ ] 后端单元/控制器测试、静态页面契约测试、完整 Maven verify 和桌面/移动浏览器验收通过。
