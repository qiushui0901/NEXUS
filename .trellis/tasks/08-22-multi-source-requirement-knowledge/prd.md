# 多源需求知识统一管理与检索 PRD

## Goal

在统一知识平台中管理需求文档、测试用例、测试结果、数值表和需求存疑，保留不同来源的
语义、权威级别、生命周期和证据边界，支持按查询意图路由来源、显式冲突治理与多源融合检索，
同时保证现有需求文档检索与语义图检索完全兼容。

## Background

- 当前已有：需求文档分块/向量化、需求语义图、Excel/历史存疑加载、
  `KnowledgeConflictService` 结构化冲突、Qdrant+SQLite 混合检索。
- 当前问题：异构资料被当作同质文本直接混排，权威级别、状态、证据边界和版本隔离缺失，
  无法解释“结论来自哪个来源、适用哪个版本、是否有直接证据、是否存在冲突”。
- 方案文档：`docs/multi-source-requirement-knowledge-implementation-plan.md`。

## Requirements

- 扩展来源类型：`REQUIREMENT / TEST_CASE / TEST_RESULT / PARAMETER_TABLE / DOUBT / CODE / WIKI`，
  旧 `TEST` 兼容映射为 `TEST_CASE`。
- 增加权威级别 `PRIMARY / SECONDARY / DERIVED` 与知识状态、查询意图模型。
- 统一 Claim 模型（`factKey / subject / predicate / object_value / unit / sourceType / authority /
  status / effectiveFrom / effectiveTo / evidence`），并支持按来源扩展字段。
- 统一 Evidence（项目+版本+来源+位置+摘要 hash，服务端生成，禁止 LLM 伪造）。
- 数值表保留单位、范围、精度、行列位置；OPEN 存疑不得作为确认事实。
- 意图路由：`NORMATIVE / VALIDATION / PARAMETER / DOUBT / CONSISTENCY / IMPACT / GENERAL`。
- 多源同 `factKey` 聚合与冲突检测（不基于纯文本相似度）。
- 检索结果提供来源、Evidence、冲突状态和解释。

## Acceptance

- 旧需求文档检索结果与旧冲突接口行为不变。
- 新来源类型可序列化/反序列化，`TEST` 兼容读取。
- 数值表参数可回查原始表格位置且保留单位/边界。
- OPEN 存疑不会出现在普通规范检索的确认事实中。
- 多源检索不降低原有需求文档 Recall。

## Phase 清单（可勾选）

- [ ] Phase 0：元数据与兼容层（SourceType/Authority/Status/version/Evidence ID 工具）
- [ ] Phase 1：结构化数值表与结构化存疑
- [ ] Phase 2：测试用例与测试结果导入
- [ ] Phase 3：统一 Claim 与 factKey、冲突分析
- [ ] Phase 4：意图路由与多源融合检索
- [ ] Phase 5：多源 Golden Dataset 与灰度
