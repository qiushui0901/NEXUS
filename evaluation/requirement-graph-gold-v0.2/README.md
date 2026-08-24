# 需求语义图金标数据集 v0.2

## 状态

这是修订后的**金标候选集**，仍需人工复核。只有 `annotation.status=GOLD_ACCEPTED` 的记录可以进入正式 Precision/Recall/F1 计算。

## 相比 v0.1 的修订

1. 删除原来把“片段A/片段B”拼接到 input 中的合成跨窗口 case。
2. 新增 `REAL_WINDOW_COMPOSITE`：窗口来自真实 `福利-成长基金.html` 父块，按 `RequirementGraphWindowPlanner` 兼容算法计算，保留 `windowId/startOffset/endOffset/contentHash/text`。
3. 跨窗口样本从 2 条增加到 23 条，但它们来自同一真实父块的 4 种规划参数，因此必须按 `windowFamilyId` 聚类统计，不能宣称是 20 份独立文档。
4. 修正原有 evidence quote，使 quote 覆盖对应 claim 的事实内容。
5. 增加 `DOCUMENT_DRIFT_REVIEW`、`DOCUMENT_CONFLICT`、`OPEN_DOUBT_NO_DRIFT` 和 `NO_DRIFT_CODE_BOUNDARY`。
6. 将 8 条 QA 的谓词拆分为购买货币、有效期、隐藏、读配置、购买流程、记录聚合、实时性、作用范围等不同类型。
7. 接入 `src/test/resources/evaluation/retrieval-eval-v1.jsonl`，单独标记为 `RETRIEVAL_TEST_CASE`，不把测试问题误当成需求事实。
8. `CODE_BOUNDARY_NEGATIVE` 增加到 9 条，覆盖实体、别名、版本、证据和声明误合并。

## 统计原则

- 对 `REAL_WINDOW_COMPOSITE`：同时输出 raw 指标和按 `windowFamilyId` 去重后的 cluster 指标。
- 对 `RETRIEVAL_TEST_CASE`：只评估检索命中、版本和证据覆盖，不计入实体/关系抽取 F1。
- 对 `DOCUMENT_DRIFT_REVIEW`：正确结果可能是“需要人工审核”，不能简单把“报告了漂移”当作正确。
- 对 `DOCUMENT_CONFLICT`：Gold 是“保留冲突/不发布单一值”，不是选择某一份文档。
- 对 `OPEN_DOUBT_NO_DRIFT`：不得自动生成已确认配置值。

## 证据规则

1. `DIRECT` 必须由单个 quote 完整支持。
2. `COMPOSITE_SUPPORTED` 必须列出全部必要证据片段，且跨窗口 case 的每个片段必须带 `windowId`、绝对 offset 和 `contentHash`。
3. source quote 必须能回查到原始来源；如果来源只是测试用例，应标记 `TEST_CASE`，不能冒充需求证据。
4. QA/产品确认不能直接升级为 `CODE_VERIFIED`。
5. 需求与代码冲突时，分别保存 Requirement Claim 和 Code Fact。

## 人工复核顺序

1. 先复核 20 条 `REAL_WINDOW_COMPOSITE`，确认窗口和 offset 与 Java 规划器一致。
2. 再复核 8 条 `DOCUMENT_DRIFT_REVIEW/DOCUMENT_CONFLICT`，确认预期是“报告候选/保留冲突”，而不是强行判定。
3. 再复核代码边界负例，确保相似名称没有误合并。
4. 最后复核测试用例与 QA 来源。
