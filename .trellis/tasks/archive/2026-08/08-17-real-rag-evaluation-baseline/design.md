# 真实 RAG 评测基线技术设计

## 1. 边界

本任务扩展现有测试侧评测框架，不新增生产接口：

1. `RetrievalEvaluationCase` / `RetrievalEvaluationDataset` 负责兼容读取 v1，并严格校验 v2。
2. `RetrievalEvaluationMatcher` 保持 Gold 匹配职责。
3. `RetrievalEvaluationReport` 汇总并输出新增指标。
4. 新增质量门组件读取版本化阈值，并由 `RetrievalEvaluationIT` 在真实运行末尾调用。
5. 默认 CI 只运行纯 JVM 数据与门禁测试；真实依赖仍由 `RUN_RETRIEVAL_EVAL=true` 显式启用。

## 2. v2 数据契约

在现有 case 顶层增加可选字段：

- `schemaVersion`: v2 固定为 `2`；缺失视为兼容 v1。
- `queryType`: `BUSINESS_SEMANTIC`、`CROSS_DOCUMENT`、`MULTI_HOP`、`HISTORICAL_VERSION`、`NO_ANSWER`、`REQUIREMENT_CODE_JOINT`。
- `sourceCommit`: 40 位小写 Git SHA。
- `review`: `{status, reviewer, reviewedAt}`，v2 只接受 `APPROVED`。

Gold 增加可选 `evidenceId`：

- 需求证据：`requirement:<project>:<version>:<filename>:<parent>:<child>`
- 代码证据：`code:<project>:<commit>:<path>:<symbol>`

v1 缺失这些字段时继续按旧规则读取；只对 `schemaVersion=2` 执行严格验证。

## 3. 冻结数据集

新增 `retrieval-eval-enterprise-v2.jsonl`，基于已脱敏的拾光业务语料构建，不复制私有源码。数据集：

- 固定拾光语料 commit `d29f32589c5bd7c190a23eb3a84f27f0069f312f`。
- 保留业务自然语言问法。
- 覆盖六种 `queryType`。
- 每条 Gold 由结构化审核信息和证据 ID 约束。
- 由单元测试锁定 SHA-256，任何变更必须显式更新审核与指纹。

## 4. 指标

在 unique-case 口径增加：

- `nDCG@10`: 需求 Gold 和代码 Gold 分别作为相关项；命中位置按 `1/log2(rank+1)` 累加，再除以理想 DCG。
- `degradationRate`: 含 warning 或 `DEGRADED` 诊断的唯一用例数 / 唯一用例数。

继续保留：

- File/Section/Child/Code Recall@10
- MRR@10
- No-result accuracy
- P50/P95
- infrastructure failure count

## 5. 质量门

阈值 JSON 使用稳定字段：

```json
{
  "schemaVersion": 1,
  "dataset": "evaluation/retrieval-eval-enterprise-v2.jsonl",
  "minimum": {
    "documentRecallAt10": 0.85,
    "codeRecallAt10": 0.75,
    "mrrAt10": 0.70,
    "ndcgAt10": 0.75,
    "noResultAccuracy": 0.90
  },
  "maximum": {
    "degradationRate": 0.05,
    "p95LatencyMs": 5000
  }
}
```

门禁返回全部失败原因，便于一次修复多个问题。没有配置 baseline 时保持当前校准行为；企业运行脚本默认配置企业阈值文件。

## 6. 兼容、发布与回滚

- 旧 JSONL、旧模式和旧 baseline 文件不修改。
- 新字段均为 Jackson 可选字段，旧测试构造器通过兼容构造器保持源码兼容。
- 质量门只在显式 baseline resource 存在时开启。
- 回滚可删除 v2 资源、企业模式和门禁调用，不影响生产检索链路。
