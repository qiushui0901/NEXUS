# 自动版本知识构建与统一证据检索

## Goal

为 NEXUS 0.2.0 建立统一的证据检索入口，并把指定需求版本与基线版本的增量自动整理成可审核、可追溯、不会覆盖正式 Wiki 的知识草稿。

## Requirements

### Unified RetrievalPipeline

- 定义 `RetrievalRequest`、`RetrievalProfile`、`RetrievalBundle` 和 `RetrievalPipeline`。
- `DEVELOPMENT_PLAN`、`REQUIREMENT_REVIEW`、`WIKI_BUILD` profile 明确声明需求证据和代码证据来源。
- 管线复用 `QueryRouter`、`QdrantHybridStore`、`CodeKnowledgeService`、`RagOutcome`、`RagWarning` 和 `RagStageDiagnostic`，不得创建第二套状态体系。
- 统一处理项目路由、需求召回、代码召回、去重、数量限制、阶段诊断和降级语义。
- 无证据且至少一个核心检索阶段失败时抛出 `RagUnavailableException`；正常零命中返回 `NO_RESULTS`。
- 同步和 SSE 开发方案必须使用同一管线，保持现有 HTTP/SSE 输出合同兼容。

### Version knowledge draft build

- 提供 `POST /api/knowledge/build`，接收项目、目标版本、基线版本、文档 ID 和代码 commit 元数据。
- 使用 Qdrant `scrollVersion` 读取目标版本与基线版本的 payload，只在内存中比较 `contentHash`，不得读取或写出向量。
- 按目标版本新增/变化的需求父块形成候选功能，并通过 `WIKI_BUILD` profile 补充需求与代码证据。
- 生成 `FeatureFactDraft`，包含产品规则摘录、代码符号、测试建议、需求/代码证据、冲突、置信度和审核状态。
- 构建结果只写入 `data/wiki-drafts/<project>/<version>/<buildId>/`，不得覆盖 `data/wiki-sources/` 或 `data/wiki/`。
- 同名或相似功能不得按语义自动合并；尤其成长基金和成长特价礼包必须保持不同 `featureId`。
- 没有代码证据或测试证据时必须计入 `missingCode`、`missingTests` 并标为待核验，不能伪造已验证状态。
- 草稿 JSON 禁止包含 vector、embedding、Qdrant point、snapshot、storage、凭据或授权字段。

### Version record

- NEXUS 平台版本更新为 `0.2.0-SNAPSHOT`，不得与游戏需求版本 5.1 混淆。
- 新增 `CHANGELOG.md`，记录功能、安全边界、API 和已知限制。
- README 更新统一检索和草稿审核工作流。

## Acceptance Criteria

- [x] `RetrievalPipeline` 覆盖成功、零命中、单侧失败降级、双侧无证据失败四类测试。
- [x] `DevelopmentPlanService` 与 `DevelopmentPlanStreamService` 不再自行编排路由和两类召回。
- [x] `POST /api/knowledge/build` 有权限标记、输入校验和 Controller 测试。
- [x] 构建器能根据 baseVersion 排除未变化父块，生成独立 featureId 和可审核草稿文件。
- [x] 路径穿越、禁用字段和正式 Wiki 不被覆盖有回归测试。
- [x] 默认 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify` 不依赖外部 Qdrant/模型且通过。
- [x] `git diff --check` 通过，Git 变更不包含 Qdrant storage、snapshot、向量或凭据。
- [x] 版本记录完成并提交、推送到 GitHub。

## Out of Scope

- 不自动发布草稿到正式 Wiki；发布仍由人工审核后的 `data/wiki-sources` 和现有 Wiki 生成器完成。
- 不使用 LLM 自动断言数值规则、上线结论或测试已通过。
- 不在本次任务中把需求评审的 LLM 重排逻辑全部迁移；profile 和边界先落地，后续迁移需用 Gold Dataset 比较质量。
- 不提交本地 `.idea`、`.codegraph`、Qdrant 运行数据或模型缓存。
