# 需求语义图金标评测报告

- dataset: requirement-graph-gold (predictor=RULE)
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
- goldEvidenceOffsetValidityRate: 0.370
- goldEvidenceClaimSupportRate: 0.705
- predictionStatusCounts: {SUCCESS=84}
- predictionErrorCodeCounts: {}
- averageLatencyMs: 0
- predictionSuccessRate: 1.000（failedCaseCount=0，partialFailureRate=0.000，failedCaseEntityRecall=N/A）
- 所有输出口径 allOutput：实体F1=0.016 关系F1=0.000 ClaimF1=0.000 代码事实F1=0.000
- 严格口径 strict（非 SUCCESS 按空结果）：实体F1=0.016 关系F1=0.000 ClaimF1=0.000 代码事实F1=0.000
- 仅成功样本 successfulOnly：实体F1=0.016 关系F1=0.000 ClaimF1=0.000 代码事实F1=0.000
- 实体类型：typedEntityRate=0.000 entityTypedF1=0.000 entityTypeAccuracy=N/A
- 存疑：recall=0.462 precision=0.171
- 证据回查：quoteSourceMatchRate=0.802 windowOffsetValidityRate=0.370 sourceFileOffsetValidityRate=N/A
- 关系本体约束：ontologyAlignedRelationF1=0.000（gold 本体关系 2 条 / 非本体 58 条 / 边界约束 9 条）

## 按场景

| 场景 | 用例 | 实体F1 | 关系F1 | ClaimF1 | 负例错误率 | 存疑召回 | 代码事实召回 | 代码事实F1 | 漂移准确率 |
|---|---|---|---|---|---|---|---|---|---|
|SINGLE_UNIT|14|0.000|0.000|0.000|N/A|0.000|0.000|0.000|N/A|
|QA_CONFIRMED|8|0.000|0.000|0.000|N/A|0.000|0.000|0.000|N/A|
|DOUBT_NEGATIVE|6|0.000|0.000|0.000|0.000|1.000|0.000|0.000|N/A|
|CODE_VERIFIED|1|0.667|0.000|0.000|N/A|0.000|0.000|0.000|N/A|
|CODE_BOUNDARY_NEGATIVE|9|0.000|0.000|0.000|N/A|0.000|0.000|0.000|N/A|
|REAL_WINDOW_COMPOSITE|23|0.000|0.000|0.000|N/A|0.000|0.000|0.000|N/A|
|DOCUMENT_DRIFT_REVIEW|3|0.000|0.000|0.000|N/A|0.000|0.000|0.000|0.000|
|NO_DRIFT_CODE_BOUNDARY|1|0.000|0.000|0.000|N/A|0.000|0.000|0.000|0.000|
|DOCUMENT_CONFLICT|3|0.000|0.000|0.000|0.000|0.000|0.000|0.000|0.000|
|OPEN_DOUBT_NO_DRIFT|1|0.000|0.000|0.000|0.000|0.000|0.000|0.000|0.000|
| **OVERALL** |69|0.016|0.000|0.000|0.000|0.462|0.000|0.000|0.000|

## 评测器自检（Oracle / Empty）

| 预测器 | 实体F1 | 关系F1 | ClaimF1 | 负例错误率 | 存疑召回 | 代码事实召回 | 代码事实F1 | 漂移准确率 |
|---|---|---|---|---|---|---|---|---|
|Oracle|1.000|1.000|1.000|0.000|1.000|1.000|1.000|1.000|
|Empty|0.000|0.000|0.000|0.000|0.000|0.000|0.000|0.000|

> Oracle 必须接近 1.0、Empty 必须接近 0（本入口已作为 CI 门禁断言）；若 Oracle 未达标，说明评测器/匹配契约有问题，不能继续调模型。

> 统计口径：RETRIEVAL_TEST_CASE 不计入抽取 F1；REAL_WINDOW_COMPOSITE 需按 windowFamily 聚类后复核；全部记录仍需人工复核为 GOLD_ACCEPTED 才能作为正式门禁。

> 指标口径说明：代码事实指标（codeFactF1）衡量 input.codeFacts 的**回写能力（echo）**，不代表代码事实自主抽取能力；多窗口样本（REAL_WINDOW_COMPOSITE）按逐窗口独立抽取+合并评测，不再拼接后截断。
