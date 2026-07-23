# 统一检索与重排

## Goal

建立需求评审、开发方案和代码检索共用的可配置检索管线，并用脱敏金标集证明质量没有回退。

## Requirements

- 建立约 50 条脱敏真实查询评测集，记录文档/代码证据 ID 和 no-answer。
- 抽取 RetrievalRequest、RetrievalProfile、RetrievalOutcome 和 RetrievalPipeline。
- 需求评审与开发方案复用需求管线；代码检索保留描述 Dense、源码 Dense、Sparse、关键词召回。
- 支持父块恢复、BGE/LLM 可选重排、上下文预算和阶段诊断。

## Acceptance Criteria

- [ ] 旧管线基线可重复生成。
- [ ] 新管线 Recall@10、MRR/nDCG 回退不超过 5%。
- [ ] 需求评审和开发方案 profile 差异有测试覆盖。
- [ ] 零命中与候选排序测试通过。

