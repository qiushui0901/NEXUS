# NEXUS 检索状态与降级契约（0.8.5, Phase 2）

> 目标：系统能明确告诉调用方「没搜到」还是「搜不了」。本文是 warning code 与状态语义的权威注册表。

## 1. 状态语义（三入口一致）

`RagOutcomeStatus` 四态在 REST / SSE / MCP 的映射：

| 状态 | 含义 | REST | SSE | MCP |
| --- | --- | --- | --- | --- |
| `SUCCESS` | 正常命中 | 2xx + `status=SUCCESS` | `completed` 事件 `status=SUCCESS` | 正常响应 |
| `NO_RESULTS` | 真正零命中（语料存在、无匹配） | 2xx + `status=NO_RESULTS` | `completed` 事件 `status=NO_RESULTS` | 空命中 + 正常状态 |
| `DEGRADED` | 非关键依赖失败，保留可用候选 | 2xx + `status=DEGRADED` + warnings | warning 事件 + `completed.status=DEGRADED` | 结果 + warnings + `DEGRADED` 记录 |
| `FAILED` | 核心依赖失败且无任何可用证据 | **503** + `outcome=FAILED` + warnings | `error` 事件 + `status=FAILED` 后结束 | `FAILED` 记录（MCP 层为 DEGRADED 响应） |

- 依赖故障**不得**转换为空命中；真实零命中**不得**表现为 5xx。
- 缓存只缓存 `SUCCESS` / `NO_RESULTS`，`DEGRADED` 不缓存。
- 所有对外 warning 消息不含底层异常原文、密钥、URL 内部细节或源码全文。

## 2. Warning code 注册表

| stage | code | 触发 |
| --- | --- | --- |
| `query.route` | `ROUTING_LLM_UNAVAILABLE` | 自动项目路由 LLM 不可用，回退默认项目 |
| `query.route` | `ROUTING_INVALID_RESULT` | 路由返回非法结果 |
| `qdrant.hybrid_search` | `DOCUMENT_RETRIEVAL_UNAVAILABLE` | 需求文档检索（Qdrant）失败 |
| `qdrant.document_corpus` | `DOCUMENT_CORPUS_UNAVAILABLE` | 版本正文检索失败 |
| `retrieval.code` | `CODE_RETRIEVAL_UNAVAILABLE` | 代码检索失败 |
| `rerank.bge` | `BGE_RERANK_UNAVAILABLE` | BGE 重排不可用（保留原始候选） |
| `rerank.llm` | `LLM_RERANK_UNAVAILABLE` | LLM 重排不可用（保留 BGE/原始候选） |
| `code-graph` | `CODE_GRAPH_DEGRADED` | 代码图谱缺失，影响分析降级到文件级 |
| `orchestration` | `ORCHESTRATION_NOT_RETRIEVABLE` | Agentic 编排无任何可检索结果 |
| `orchestration` | `ORCHESTRATION_INSUFFICIENT_EVIDENCE` | 编排证据不足 |
| `llm.generate.plan` | `PLAN_GENERATION_FALLBACK` | 开发方案生成失败，回退规则化内容 |
| `llm.generate.stream` | `STREAM_GENERATION_FAILED` | SSE 流式生成失败 |
| `knowledge.build` | `FEATURE_LIMIT_APPLIED` | 变化功能超过上限截断 |
| `mcp.response` | `MCP_RESPONSE_TRUNCATED` | MCP 响应被截断 |
| `version.diff` | `CONFLICT_INPUT_NORMALIZED` | 版本对比输入归一化 |

新增失败路径时必须登记 code，禁止复用语义不同的既有 code。

## 3. 代码索引发布语义（增量路径）

增量索引采用**文件级安全替换 + 最终一致**策略（MVP 决策，非原子发布）：

- 顺序：滚动快照旧 chunk ID → `git show` 读取目标 commit 内容 → upsert 新 chunk（新 ID）→ 只按**旧 ID** 删除；
- 删除前过滤本次 upsert 的新 ID——**部分失败后对同一 commit 范围重试不会删除刚写入的 chunk**；
- 旧 chunk 清理失败 → 抛出可重试的部分失败异常（新旧并存，查询可能短暂看到重复命中），重试同一范围收敛；
- 查询侧短期可能看到新旧并存（最终一致）；需要强一致时应升级为 staging collection + alias 原子切换（暂未实施）。

## 4. 部署限制：索引协调为单 JVM 内锁

`CodeIndexLockService` 的项目级锁是进程内实现（`ConcurrentHashMap` + `synchronized`）。
**多实例部署时，不同 JVM 的索引任务仍可能交错写入同一 live alias**（旧任务覆盖新任务、
互相删除对方 chunk）。当前缓解：

- 生产部署建议同一时间只有一个实例执行索引任务（单写者），或由外部调度串行化；
- 跨 JVM 的分布式协调（基于 Qdrant 的乐观锁或独立协调服务）是已知缺口，需要强一致时实施。

## 5. 实现位置与测试



- 状态机：`RetrievalPipeline`（核心阶段全失败且无证据 → `RagUnavailableException`；有 warning → DEGRADED；全空 → NO_RESULTS）
- HTTP 映射：`ApiExceptionHandler`（RagUnavailable → 503 + `outcome=FAILED`；EmbeddingUnavailable → 503）
- SSE：`DevelopmentPlanStreamService`（warning 事件、`completed.status`、核心失败 `error` 事件）
- MCP：`McpToolInvocationService`（依赖失败 → DEGRADED 响应 + warning 记录）
- 测试：`RetrievalPipelineTest`（DEGRADED/FAILED + code 可定位 + 不泄漏内部细节）、`DefaultRequirementRerankerTest`（BGE 降级）、`ApiExceptionHandlerTest`（503 契约）、`DevelopmentPlanServiceTest`（NO_RESULTS/DEGRADED/PLAN_GENERATION_FALLBACK）、`RetrievalQualityGateTest`（空语料必须 NO_RESULTS）
