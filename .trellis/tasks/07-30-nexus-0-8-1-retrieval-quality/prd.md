# NEXUS 0.8.1：检索质量提升

## Goal

在不引入 GraphRAG 或新共享基础设施的前提下，修复 0.8 检索链路中候选过早折叠、重排覆盖不足和缺少分阶段诊断的问题，使固定拾光评测集上的 Recall@10、MRR@10 和真实延迟获得可复现、可解释的改善。

## Confirmed Baseline

- 正式数据集为 `src/test/resources/evaluation/retrieval-eval-shiguang-v1.jsonl`，54 个唯一 case，SHA-256 为 `1ff996579588bfc5b859b5a483427c255325265b211e452af5eaff6471a61b18`。
- 固定语料 commit 为 `d29f32589c5bd7c190a23eb3a84f27f0069f312f`。
- 0.8 正式结果：Document Recall@10 `0.354167`、Code Recall@10 `0.738095`、MRR@10 `0.425617`、真实 P95 `5131 ms`。
- 0.8 的 BGE 144 次调用全部成功，但质量指标与 0.7 完全相同。
- 当前需求候选在 BGE 前按 parent 去重；正式报告的 144 次文档执行最终均只有 1 个 parent 候选。
- 当前代码检索没有经过统一 BGE 重排。

## Requirements

### R1 — Child-first requirement rerank

- 需求检索必须保留 RRF 返回的 child 候选进入重排，不得在 BGE 前按 parent 折叠。
- 重排后再按稳定 parent key 聚合，并返回不同 parent 的最终 Top-K。
- 重排失败时必须保留原始混合检索顺序并沿用现有结构化降级语义。

### R2 — Better rerank passages and bounded work

- BGE passage 应包含文件名、父块上下文和命中的 child 文本，避免只使用无上下文 child 文本。
- 候选数、文本长度和批量大小必须有明确上限，不能用无限候选换取质量。
- 不记录业务正文、模型输入或异常原文到公开 warning/manifest。

### R3 — Code quality improvement

- 代码召回应保留较大的融合候选池，再执行有界、可降级的代码重排或确定性精排。
- passage/排序特征至少利用项目、路径、符号名、语言和源码上下文。
- 精确类名、方法名、路径词等 lexical 信号不得被语义排序淹没。

### R4 — Stage diagnostics and failure attribution

- 正式评测必须记录重排前后候选数、顺序变化和黄金结果升降情况。
- 能够区分语料缺失、候选召回失败、parent 聚合损失和 rerank 排名损失。
- 质量统计以 54 个唯一 case 为主要口径；重复运行用于延迟统计并保持原始报告兼容。

### R5 — Reproducible 0.8 → 0.8.1 comparison

- 固定同一语料、黄金集、profile、Top-K、模型、运行环境、缓存和预热/重复配置。
- 产出独立的 0.8 baseline、0.8.1 candidate、comparison 和 manifest。
- 更新 `docs/retrieval-evaluation-history.md`，不得把非回退写成质量提升。

### R6 — Compatibility and safety

- REST、MCP 和现有 `RetrievalBundle` 对外字段保持兼容。
- 项目、documentId、version 过滤保持不变，跨项目/跨版本污染率必须为 0。
- Qdrant、BGE 或代码检索依赖不可用时，保持现有分支隔离和安全 warning。

## Non-goals

- 不在 0.8.1 引入 Neo4j、GraphRAG、Redis 或外部搜索集群。
- 不更换固定黄金集来制造指标提升；标签勘误必须单独披露。
- 不以关闭 reranker、减少评测范围或放宽成功条件换取延迟/通过状态。
- 不承诺本轮实现完整 query rewrite 或多跳图检索。

## Acceptance Criteria

- [x] Document Recall@10 相对 0.8 基线至少提升 `0.15` 个绝对值，即达到 `>= 0.504167`。
- [x] Code Recall@10 相对 0.8 基线至少提升 `0.05` 个绝对值，即达到 `>= 0.788095`。
- [x] MRR@10 相对 0.8 基线至少提升 `0.10` 个绝对值，即达到 `>= 0.525617`。
- [x] No-result accuracy 保持 `1.0`，跨项目/跨版本污染率保持 `0`。
- [x] 真实 0.8.1 P95 不高于 0.8 的 `5131 ms`；若质量门槛已达成，目标进一步压到 `<= 4500 ms`。
- [x] BGE 健康运行且无意外 degradation；候选为空的 skip 仍可作为正常状态。
- [x] 报告包含分阶段候选与 rerank 诊断，能够解释所有未命中发生在哪个阶段。
- [x] 新增/修改行为有单元或集成回归测试，Java 21 `./mvnw -B verify`、Python 测试、脚本语法和 `git diff --check` 全部通过。
