# NEXUS 0.8.1 Technical Design

## Problem

0.8 已证明 Python/Transformers BGE 服务可用，但候选生命周期不合理：需求 child 在 BGE 前按 parent 折叠，代码结果不经过统一重排，评测又只记录最终候选，导致质量损失不可定位。0.8.1 需要先修复候选生命周期，再用固定评测迭代，而不是直接更换模型或引入图数据库。

## Architecture

### 1. Requirement candidate lifecycle

`QdrantHybridStore` 继续执行 dense + sparse prefetch 和 RRF。`RetrievalPipeline` 将原始 child 列表直接交给 requirement reranker。重排器对有界 child 列表评分后，以稳定 parent key 聚合：每个 parent 保留得分最高 child 所在的 `ChunkRecord`，最终按最高 child 排名返回不同 parent。

为了兼容现有 `BgeReranker` 接口，第一阶段允许按重排结果顺序推导 parent 顺序；若评测证明需要显式分数，再扩展内部 scored 契约，但不改变 REST/MCP 输出。

### 2. Passage construction

HTTP BGE 请求不再只发送 `childText`，而是发送有界 passage：filename、parent excerpt、child text。父块和 child 重复时去重，所有字段做空值处理和字符上限控制。fallback 仍返回原候选顺序。

### 3. Code ranking

代码检索分为 candidate recall 与 final limit。`CodeQdrantStore` 保持现有 dense/sparse/RRF 和 lexical scoring，`CodeKnowledgeService` 允许按配置/倍率召回大于最终 Top-K 的候选。新增代码 rerank boundary，使用同一 BGE 服务或轻量确定性精排；失败时返回原排序。为控制 CPU 延迟，代码与需求候选均设置上限，并优先验证一次批量调用能否满足质量门槛。

### 4. Evaluation diagnostics

评测 case 记录：raw requirement child count、post-rerank parent count、code pre-final count、rerank order changed、gold rank before/after。comparison 汇总 promotion/unchanged/demotion 和阶段失败分类。新增字段保持向后兼容，旧报告仍可读取。

### 5. Performance strategy

- 不增加网络往返次数，优先扩大单次批量而非逐条调用。
- passage 截断，避免 parent 全文导致 CPU token 数暴涨。
- 需求和代码分支继续并行；若代码需要 BGE，评估合并调用或仅对高价值候选启用。
- 每次质量改动后用固定 54-case 数据集测真实 P50/P95；达标前按失败归因调参，不盲目堆大 Top-K。

## Compatibility

- `RetrievalRequest`、`RetrievalBundle`、MCP 输出保持兼容。
- 新诊断字段只增加到测试报告/内部模型。
- 旧配置缺少新参数时使用有界默认值。
- 可通过配置关闭 0.8.1 rerank 增强，回退到 0.8 行为。

## Rollback

- requirement child-first rerank 可通过策略开关回退为 passthrough/旧聚合。
- code rerank 可独立关闭。
- 评测报告保留 0.8 baseline，不覆盖原产物。
