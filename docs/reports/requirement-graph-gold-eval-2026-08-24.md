# 需求语义图金标评测报告

- dataset: requirement-graph-gold (predictor=LLM)
- sourceContext: immortal-game-service/fengshen/5.1（来源 84 条）
- evaluatedCases: 84
- acceptedCases: 0
- formalEvaluation: false
- totalCases: 84
- extractionCases: 69
- retrievalTestCases: 15（不计入抽取 F1）
- 匹配口径：一对一匹配 / Claim=factKey AND value / 代码事实=repo+commit+key+value
- goldEvidenceFieldCompletenessRate: 1.000
- goldEvidenceSourceMatchRate: 0.802
- goldEvidenceOffsetValidityRate: 0.000
- goldEvidenceClaimSupportRate: 0.705
- predictionStatusCounts: {SUCCESS=32, MODEL_TIMEOUT=1, FAILURE=51}
- predictionErrorCodeCounts: {MODEL_TIMEOUT=1, FAILURE=51}
- averageLatencyMs: 25788
- predictionSuccessRate: 0.381（failedCaseCount=37，partialFailureRate=0.440，failedCaseEntityRecall=0.000）
- 严格口径 strict：实体F1=0.210 关系F1=0.000 ClaimF1=0.012 代码事实F1=0.182
- 仅成功样本 successfulOnly：实体F1=0.268 关系F1=0.000 ClaimF1=0.016 代码事实F1=0.667
- 关系本体约束：ontologyAlignedRelationF1=0.000（gold 本体关系 2 条 / 非本体 58 条 / 边界约束 9 条）

## 按场景

| 场景 | 用例 | 实体F1 | 关系F1 | ClaimF1 | 负例错误率 | 存疑召回 | 代码事实召回 | 代码事实F1 | 漂移准确率 |
|---|---|---|---|---|---|---|---|---|---|
|SINGLE_UNIT|14|0.233|0.000|0.000|N/A|0.000|0.000|0.000|N/A|
|QA_CONFIRMED|8|0.429|0.000|0.000|N/A|0.000|0.000|0.000|N/A|
|DOUBT_NEGATIVE|6|0.364|0.000|0.000|0.833|0.000|0.000|0.000|N/A|
|CODE_VERIFIED|1|0.333|0.000|1.000|N/A|0.000|1.000|1.000|N/A|
|CODE_BOUNDARY_NEGATIVE|9|0.200|0.000|0.000|N/A|0.000|0.000|0.000|N/A|
|REAL_WINDOW_COMPOSITE|23|0.043|0.000|0.000|N/A|0.000|0.000|0.000|N/A|
|DOCUMENT_DRIFT_REVIEW|3|0.000|0.000|0.000|N/A|0.000|0.000|0.000|0.000|
|NO_DRIFT_CODE_BOUNDARY|1|0.000|0.000|0.000|N/A|0.000|0.000|0.000|0.000|
|DOCUMENT_CONFLICT|3|0.000|0.000|0.000|0.000|0.000|0.000|0.000|0.000|
|OPEN_DOUBT_NO_DRIFT|1|0.000|0.000|0.000|0.000|0.000|0.000|0.000|0.000|
| **OVERALL** |69|0.210|0.000|0.012|0.500|0.000|0.100|0.182|0.000|

## 评测器自检（Oracle / Empty）

| 预测器 | 实体F1 | 关系F1 | ClaimF1 | 负例错误率 | 存疑召回 | 代码事实召回 | 代码事实F1 | 漂移准确率 |
|---|---|---|---|---|---|---|---|---|
|Oracle|1.000|1.000|1.000|0.000|1.000|1.000|1.000|1.000|
|Empty|0.000|0.000|0.000|0.000|0.000|0.000|0.000|0.000|

> Oracle 必须接近 1.0、Empty 必须接近 0（本入口已作为 CI 门禁断言）；若 Oracle 未达标，说明评测器/匹配契约有问题，不能继续调模型。

> 统计口径：RETRIEVAL_TEST_CASE 不计入抽取 F1；REAL_WINDOW_COMPOSITE 需按 windowFamily 聚类后复核；全部记录仍需人工复核为 GOLD_ACCEPTED 才能作为正式门禁。
