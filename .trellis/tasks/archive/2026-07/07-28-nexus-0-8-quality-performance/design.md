# NEXUS 0.8 Technical Design

## Current State

- `RetrievalPipeline` 顺序调用需求检索、版本语料滚动读取和代码检索。
- `DoubtReviewService` 在管线返回后再次执行 BGE 和 LLM 重排，其他 profile 没有同等质量处理。
- `EmbeddingBatcher`、检索结果和 `WikiRepository` 都没有缓存。
- 已有 10 条 JSONL 评测用例及匹配/报告基础，但未形成 50 条数据集与 CI 回归门禁。
- 已有确定性 `KnowledgeConflictService`，但 MCP 只暴露 9 个工具。

## Architecture

### 1. Pipeline orchestration

`RetrievalPipeline` 负责完整阶段编排：

1. 校验请求并解析 project/document/version。
2. 生成包含配置指纹的 `RetrievalCacheKey`，尝试读取稳定结果缓存。
3. 根据 profile 向专用 executor 提交 requirement search、corpus scroll、code search。
4. 每个 future 使用独立 timeout；异常转换为阶段 warning。
5. 对需求候选去重后在管线内执行 BGE 重排；可配置的 LLM 精排通过独立接口接入。
6. 聚合 `RagOutcome`，只缓存 `SUCCESS` 和 `NO_RESULTS`。

线程池必须有固定上限和有界队列。Spring Bean 负责生命周期关闭，测试可注入直接/自定义 executor。

### 2. Rerank boundary

- 保留 `BgeReranker` 的 `ChunkRecord` 契约。
- 新增 `RequirementRerankService` 作为统一策略层：BGE 必选阶段、LLM 可配置阶段、失败返回原顺序与 warning。
- 从 `DoubtReviewService` 移除 BGE 依赖和私有 LLM 重排调用，直接消费 `bundle.requirementEvidence()`。
- 代码证据保留代码混合检索排序；“三 profile 重排”指三种 profile 的 requirement evidence 都经过相同策略。

### 3. Cache design

- 实现一个无第三方依赖的并发、TTL、最大容量缓存；惰性清理过期项，超容量时删除最旧项。
- `CachingEmbeddingModel` 装饰 Spring AI `EmbeddingModel`。键为 model/config fingerprint + 输入文本；批量请求逐项复用缓存，只向 delegate 发送 miss。
- `RetrievalResultCache` 保存不可变 `RagOutcome<RetrievalBundle>`；键显式包含所有作用域与排序参数。
- `WikiRepository` 缓存 index/page；`invalidate(project, version)` 在 Wiki 原子发布成功后调用。

### 4. Evaluation and CI

- 扩展 `retrieval-eval-v1.jsonl` 至 50+ 稳定标签用例。
- 新增离线数据集门禁测试：数量、类别覆盖、无跨项目/版本不稳定 ID。
- 新增确定性排序回归 fixture，比较 0.8 rerank 结果与已提交 baseline，断言 Recall@10/MRR 不下降。
- 新增并行延迟测试，以受控 fake dependency 验证 P95/单次关键路径相对顺序基线至少下降 30%。
- JaCoCo 在 `verify` 阶段执行 check；阈值依据当前可重复测试基线设置，并保留后续上调空间。
- OWASP dependency-check 放入 CI 独立 job/profile，扫描失败阻断构建，避免本地日常测试强制联网。

### 5. MCP

- `NexusMcpTools` 注入 `KnowledgeConflictService`，新增只读、幂等、闭世界 `nexus_conflict_check`。
- 输入使用现有 `AnalyzeRequest`/`KnowledgeClaim` 类型，输出使用 `KnowledgeConflictReport`，调用仍经过 `McpToolInvocationService` 的权限、审计和项目作用域解析。
- 新建 `NexusMcpPrompts`，使用 Spring AI 2.0 MCP prompt annotation 暴露三个模板；模板只描述工具编排，不直接执行副作用。

## Failure Semantics

- 单召回分支 timeout/error：返回空分支 + `DEGRADED` warning。
- 重排 error：保留混合检索顺序 + `DEGRADED` warning。
- 缓存异常：视为 miss，不影响主链路。
- 全部分支无有效结果且存在失败：`FAILED`；无失败则 `NO_RESULTS`。
- 不缓存 `DEGRADED`/`FAILED`，避免依赖恢复后继续返回陈旧降级结果。

## Compatibility and Rollback

- 现有 `RetrievalRequest` 和 `RetrievalBundle` 保持字段兼容。
- 新配置全部提供默认值；旧部署无需新增环境变量即可启动。
- 可通过配置分别关闭 pipeline cache、embedding cache、Wiki cache、LLM rerank。
- 回滚时可关闭并行和缓存而无需迁移数据。

## Verification

- 单元测试：缓存 TTL/容量/隔离、并行超时、rerank fallback、Wiki invalidation、MCP 契约。
- 集成测试：三 profile、`DoubtReviewService` 管线消费、应用上下文。
- 质量门禁：数据集 ≥50、类别覆盖、Recall@10/MRR baseline、JaCoCo。
- 冒烟：`./mvnw verify`，MCP capability 列表及核心 REST/MCP happy path。
