# NEXUS 真实 RAG 企业评测指南

## 1. 目的与边界

本评测用于回答一个发布问题：在固定语料、固定代码提交和固定依赖配置下，NEXUS 的真实检索质量是否达到企业使用基线。

评测覆盖需求文档召回、代码召回、排序质量、无答案识别、降级率、基础设施失败和端到端延迟。它不会自动修改线上检索参数，不负责创建 Qdrant Collection，也不把外部服务密钥写入仓库。

默认 Maven 单元测试只校验数据契约、指标和门禁逻辑，不启动真实 Qdrant、Embedding 或 BGE。真实评测必须在依赖齐全的受控环境中显式执行。

## 2. 冻结基线

| 项目 | 值 |
|---|---|
| 评测模式 | `0.8.6-enterprise` |
| 数据集 | `src/test/resources/evaluation/retrieval-eval-enterprise-v2.jsonl` |
| 数据集 SHA-256 | `cb0bc61851faf1c3b2a694a6d25759f8044d727fbac4e1bfab328507aa9aa4f4` |
| 阈值文件 | `src/test/resources/evaluation/retrieval-threshold-enterprise-v0.8.6.json` |
| 拾光代码提交 | `d29f32589c5bd7c190a23eb3a84f27f0069f312f` |
| 项目配置 | `shiguang-eval` |
| 用例数 | 24 条：18 条 HIT、6 条 NO_RESULTS |

数据集覆盖业务语义、跨文档、多跳、历史版本、需求与代码联合、无答案六类问题。

## 3. 环境准备

执行前必须满足以下条件：

1. 使用 JDK 21，并可运行仓库内的 `./mvnw`。
2. Qdrant 可访问，且已有与冻结语料一致的 `requirements_shiguang_eval` 和代码 Collection。
3. Embedding 服务可访问，模型与构建索引时一致。
4. BGE 重排服务可访问，并符合 NEXUS `/rerank` 契约。
5. `SHIGUANG_REPOSITORY_PATH` 指向拾光仓库，仓库中存在冻结提交。
6. 代码 Collection 对应提交必须是 `d29f32589c5bd7c190a23eb3a84f27f0069f312f`。

常用环境变量如下，具体地址和密钥只在执行环境中配置：

```bash
export QDRANT_URL=http://127.0.0.1:6333
export SHIGUANG_CODE_COLLECTION=code_shiguang_eval
export SHIGUANG_REPOSITORY_PATH=/path/to/shiguang
export OPENAI_BASE_URL=https://your-gateway.example/v1
export OPENAI_API_KEY=your-token
export OPENAI_EMBEDDING_MODEL=text-embedding-v4
export BGE_RERANK_URL=http://127.0.0.1:8081
```

运行前建议分别检查 Qdrant Collection、Embedding 请求和 BGE 健康状态。依赖不可用时，评测会将其归为基础设施失败，而不是低召回。

## 4. 数据契约

企业数据集使用 JSONL schema v2，每行是一条独立用例，必须包含：

- `schemaVersion=2` 和六类之一的 `queryType`。
- 40 位小写 Git `sourceCommit`。
- `review.status=APPROVED`、非空审核人和带时区的审核时间。
- 唯一用例 ID，以及至少一个 HIT Gold 或明确的 `NO_RESULTS` 预期。
- 每个 Gold 都有稳定且不重复的 `evidenceId`。

需求证据 ID 格式：

```text
requirement:<projectId>:<version>:<filename>:<parentOrder|*>:<childOrder|*>
```

代码证据 ID 格式：

```text
code:<projectId>:<40位commit>:<repository-relative-filePath>:<symbolName>
```

证据 ID 是评测标签，不得使用绝对路径、临时 Qdrant point ID 或运行时随机值。

## 5. 执行评测

使用仓库脚本执行：

```bash
./tools/run-real-rag-evaluation.sh
```

可通过环境变量调整预热、重复次数和输出目录：

```bash
RETRIEVAL_EVAL_WARMUP_RUNS=1 \
RETRIEVAL_EVAL_REPETITIONS=3 \
RETRIEVAL_EVAL_OUTPUT_DIRECTORY=target/retrieval-evaluation/0.8.6-enterprise \
./tools/run-real-rag-evaluation.sh
```

默认报告：

```text
target/retrieval-evaluation/0.8.6-enterprise/report.json
target/retrieval-evaluation/0.8.6-enterprise/report.md
```

## 6. 指标与发布门禁

- `documentRecallAt10`：需要文档证据的唯一用例中，Gold 在 Top 10 命中的比例。
- `codeRecallAt10`：需要代码证据的唯一用例中，Gold 在 Top 10 命中的比例。
- `mrrAt10`：每个唯一用例第一次执行的首个 Gold 倒数排名均值。
- `ndcgAt10`：支持多 Gold 的排序质量，按每个唯一用例第一次执行计算。
- `noResultAccuracy`：NO_RESULTS 用例正确返回空结果的比例。
- `degradationRate`：出现稳定 warning 或 DEGRADED 诊断的唯一用例比例。
- `p95LatencyMs`：全部实际执行样本的端到端 P95 延迟；重复执行会参与延迟统计。
- `infrastructureFailureCases`：Qdrant、Embedding、BGE、仓库或配置等基础设施失败的用例数。

当前门禁要求：文档 Recall@10 不低于 0.85、代码 Recall@10 不低于 0.75、MRR@10 不低于 0.70、nDCG@10 不低于 0.75、无结果准确率不低于 0.90、降级率不高于 0.05、P95 不高于 5000ms，且基础设施失败必须为 0。

评测会一次汇总全部越界原因并令 Maven 失败。报告会先落盘，因此失败后应先查看 `report.md`。

## 7. 失败分类

基础设施失败表示评测环境不可信，例如连接失败、超时、仓库缺失或配置错误。此时不能用指标判断检索质量，应先修复环境并重跑。

质量失败表示真实请求已完成，但 Recall、MRR、nDCG、无结果准确率、降级率或延迟未达到阈值。此时应依据报告中的阶段归因区分候选召回缺失、重排损失、代码索引缺失和错误降级。

## 8. 更新数据集

1. 从真实开发问题中选择脱敏、可复现的问题，不能根据当前检索结果反推 Gold。
2. 固定代码提交、需求版本、模型和 Collection 构建参数。
3. 由领域人员确认 Gold，另一名审核人复核问题、证据和 NO_RESULTS 标签。
4. 更新 `review` 信息，确保所有 evidence ID 唯一且可追溯。
5. 执行 JSONL 解析、契约测试和完整评测。
6. 重新计算 SHA-256，并同步更新 `RetrievalEvaluationTest` 与本指南。
7. 阈值变化必须使用新的版本化阈值文件，保留旧文件以支持历史复现。

计算指纹：

```bash
LC_ALL=C shasum -a 256 src/test/resources/evaluation/retrieval-eval-enterprise-v2.jsonl
```

数据集、审核记录、冻结提交、SHA 和阈值必须在同一变更中评审。未经人工审核的数据不得进入发布门禁。
