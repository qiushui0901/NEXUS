# 真实 RAG 评测基线实施计划

## 实施清单

- [x] 扩展 `RetrievalEvaluationCase`，增加兼容的 v2 provenance、问题类型、审核和证据 ID 字段。
- [x] 扩展 `RetrievalEvaluationDataset`，对 v2 执行严格校验并保持 v1 兼容。
- [x] 新增冻结企业数据集与阈值文件，覆盖六类真实问题。
- [x] 扩展 `RetrievalEvaluationReport`，增加 nDCG@10 与降级率。
- [x] 新增 `RetrievalEvaluationQualityGate`，读取阈值并汇总全部越界原因。
- [x] 在 `RetrievalEvaluationIT` 中接入可选质量门，并增加企业评测模式。
- [x] 新增默认 CI 单元测试：v2 契约、SHA、指标、门禁通过/失败。
- [x] 新增 `tools/run-real-rag-evaluation.sh` 与简体中文文档。
- [x] 更新 `CHANGELOG.md` 当前版本。

## 验证命令

```bash
./mvnw -q -Dtest=RetrievalEvaluationTest,RetrievalEvaluationQualityGateTest test
./mvnw -q test
./tools/verify-report.sh
```

真实依赖验收由具备拾光仓库、Qdrant、Embedding 和 BGE 的环境执行：

```bash
RUN_RETRIEVAL_EVAL=true ./tools/run-real-rag-evaluation.sh
```

## 风险与回滚点

- 数据契约修改可能破坏旧 JSONL：先完成兼容构造与 v1 回归测试，再引入 v2。
- 指标分母容易混淆：只在 unique-case summary 中作为发布口径，并为多 Gold 建立确定性单测。
- 真实门禁受环境抖动影响：基础设施失败独立统计；P95 阈值由版本化文件控制，不写死在代码中。
- 不修改生产检索算法，出现问题可按文件级回滚评测扩展。
