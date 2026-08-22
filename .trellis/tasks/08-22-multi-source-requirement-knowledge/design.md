# 多源需求知识统一管理与检索 — Design

> 基于 `docs/multi-source-requirement-knowledge-implementation-plan.md` 的可勾选设计清单。

## Phase 0：元数据与兼容层

- [ ] 扩展 `KnowledgeConflictModels.SourceType`：新增 `TEST_CASE、TEST_RESULT、PARAMETER_TABLE、DOUBT`
- [ ] 保留旧 `TEST` 枚举并增加 `SourceType.normalize(String)` 兼容映射 `TEST → TEST_CASE`
- [ ] 扩展 `Authority`：新增 `SECONDARY`
- [ ] 统一 Evidence ID 工具：`projectId + documentId + version + sourceType + sourceName + location + excerptHash`
- [ ] 给 Qdrant payload / 现有 Claim 增加来源、权威、状态元数据（最小兼容字段）
- [ ] 旧数据读取兼容：不强制重写，新数据禁止继续使用 `TEST`

## Phase 1：结构化数值表与结构化存疑

- [ ] 新增 `ParameterTableLoader`（基于 `ExcelKnowledgeLoader` 扩展）
- [ ] 表头别名识别（模块/参数/最小值/最大值/单位/版本/说明）
- [ ] 数值类型化（INTEGER/DECIMAL/PERCENTAGE/DURATION/COUNT/BOOLEAN/ENUM/TEXT）
- [ ] 保留 workbook/sheet/row/column 原始位置
- [ ] 新增结构化 `DoubtClaim`，状态 `OPEN/UNDER_DISCUSSION/RESOLVED/REJECTED/OBSOLETE`
- [ ] OPEN 存疑默认不进入普通规范检索

## Phase 2：测试用例与测试结果

- [ ] `TestCaseKnowledgeLoader`：Markdown/JSON/JUnit XML/pytest XML
- [ ] 字段：testCaseId/preconditions/steps/expectedResult/coveredClaimIds/framework
- [ ] `TestResultKnowledgeLoader`：testRunId/executionStatus/executedAt/environment/actualResult
- [ ] `TEST_CASE → REQUIREMENT`、`TEST_RESULT → TEST_CASE` 关联
- [ ] 测试结果“最近一次/按环境”语义

## Phase 3：统一 Claim 与冲突分析

- [ ] 扩展 `KnowledgeClaim`：subject/predicate/object/valueType/unit/status/effectiveFrom/effectiveTo
- [ ] 建立 `factKey` 生成与审核规则
- [ ] 同一 factKey 多源聚合
- [ ] 扩展 `KnowledgeConflictService`：需求-参数、需求-存疑、参数-测试、测试结果-预期、版本内部、来源过期、缺少验证
- [ ] 结论状态：`CONFIRMED/SUPPORTED/PARTIALLY_SUPPORTED/REVIEW_REQUIRED/CONFLICTED/NO_EVIDENCE/NO_RESULT`

## Phase 4：意图路由与多源检索

- [ ] `KnowledgeQueryIntentClassifier`（规则优先，LLM 回退）
- [ ] 来源过滤策略（NORMATIVE/VALIDATION/PARAMETER/DOUBT/CONSISTENCY/IMPACT）
- [ ] 统一候选 `KnowledgeCandidate` 与可解释打分（vector/text/intent/graph/evidence/freshness - conflictPenalty）
- [ ] 同 factKey 聚合响应；返回 `explanations`（复用语义图检索解释结构）

## Phase 5：评估与灰度

- [ ] 多源 Golden Dataset（NORMATIVE/PARAMETER/VALIDATION/DOUBT/CONSISTENCY/IMPACT）
- [ ] 门槛：原需求文档 Recall@10 不下降、参数 Unit Accuracy=100%、Evidence Hit Rate≥95%、Grounded Rate≥95%、跨版本污染=0
- [ ] 按项目灰度开关与回滚（保留已导入数据、切换 live alias）