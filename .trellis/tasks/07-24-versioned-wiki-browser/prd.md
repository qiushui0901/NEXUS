# 版本化 Wiki 与知识库浏览

## Goal

把 NEXUS 从单纯问答系统扩展为“版本化需求、代码和测试知识平台”：保留原始需求与 Git 代码作为可信证据，自动把结构化事实生成可版本管理、可浏览、可审核的 Wiki 页面，为产品、开发和测试提供同一份功能真相。

## Requirements

### Versioned knowledge model

- Wiki 必须以 `projectId + version + featureId` 为稳定主键，不能只靠中文名称关联功能。
- 页面包含概览、产品规则、代码实现、测试点、风险/存疑、关系和原始证据。
- 页面状态至少支持草稿、需求已核验、代码已核验、全部核验、冲突、过期、缺少实现、缺少需求和已驳回。
- 代码证据记录文件、符号和 commit；需求证据记录文档、版本、定位和摘录。
- “成长基金（grow-fund）”与“成长特价礼包（grow-discount）”必须使用不同 featureId，并显示容易混淆但不是同一功能。

### Wiki generator

- 从版本化 JSON 源定义生成 Markdown 页面和机器可读 JSON 索引。
- 输出目录按项目和版本隔离，生成过程使用临时目录后原子替换，避免读到半成品。
- 校验 projectId/version/featureId，拒绝路径穿越、重复 featureId 和跨版本错误。
- 生成结果不得包含向量、Qdrant point、Qdrant storage、snapshot 或凭据。
- 提供管理 API 触发指定项目版本的生成，并返回页面数量和输出位置。

### Browser

- 提供独立 `/wiki` 页面，不依赖外部 CDN。
- 可以选择项目和版本、搜索页面、按状态/分类筛选。
- 功能详情按概览、产品、开发、测试、证据展示，并显示版本、状态、commit、关系和来源。
- 页面空状态、加载失败和无结果状态必须清晰。
- 监控工作台提供进入知识库的入口。

### Seed content

- 提供 `immortal-game-service` 5.1 的可复现源定义和生成结果。
- 首批包含版本概览、成长基金、成长特价礼包和两者边界说明。
- 未经原始需求确认的业务规则不得编造，必须标记为待核验。

## Acceptance Criteria

- [x] `POST /api/wiki/generate?projectId=immortal-game-service&version=5.1` 可从源定义生成 Wiki。
- [x] `GET /api/wiki/projects`、版本列表、版本详情和页面详情 API 可读取生成结果。
- [x] `/wiki` 可浏览 5.1 页面，并在不启动 Qdrant/Ollama/BGE 时正常工作。
- [x] 成长基金页面只引用 GrowFund 相关符号；成长特价礼包只引用 GrowDiscount 相关符号。
- [x] 页面展示产品、开发、测试和证据视图及审核状态。
- [x] 非法路径、重复 featureId、缺失源定义和缺失页面有确定性错误响应。
- [x] 生成的 Markdown/JSON 不含向量数据、凭据或运行时 Qdrant 数据。
- [x] 默认 Maven verify 在无外部服务时通过，静态页面有结构测试。

## Out of Scope

- 第一版不提供在线 Markdown 编辑器、多人评论、复杂 RBAC 审批流或知识图谱布局。
- 第一版不自动调用 LLM 从全部文档中发现所有功能；生成器先消费受版本约束的结构化事实，后续再由 RetrievalPipeline/LLM 自动产出该事实层。
- 不提交原始大型需求文件或向量数据库数据。
