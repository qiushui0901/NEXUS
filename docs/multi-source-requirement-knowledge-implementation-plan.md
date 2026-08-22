# 多源需求知识统一管理与检索实现方案

**状态：** Proposed
**版本：** v1.0
**编制日期：** 2026-08-22
**适用范围：** 需求文档、测试用例、测试结果、数值表、需求存疑、需求语义图及冲突分析
**主要目标：** 在统一知识平台中管理多种需求相关资料，同时保留不同来源的语义、权威级别、生命周期和证据边界，避免将异构知识混成不可解释的文本结果。

---

## 1. 背景

当前系统已经具备以下能力：

- 需求文档导入、分块和向量化；
- 需求语义图构建、Evidence 回查和关系检索；
- Excel 工作表读取和历史需求存疑加载；
- `KnowledgeConflictService` 对结构化 Claim 做项目、版本和来源冲突检测；
- 需求、代码、测试和 Wiki 来源的基础冲突模型；
- 文本、向量、重排和图谱混合检索。

目前的问题不是“能不能把这些文件放进知识库”，而是：

1. 需求文档、测试用例、数值表和需求存疑的语义不同；
2. 它们的权威级别不同，不能用相同分数直接排序；
3. 测试用例证明的是验证方式，不一定等于产品规范；
4. 数值表需要保留单位、边界、精度和行列位置；
5. 需求存疑只能作为未决问题，不能当作确定事实；
6. 不同版本之间可能出现同一事实的不同结论；
7. 直接混合召回会导致答案既不稳定，也无法解释来源。

因此，本方案采用以下总体策略：

> **统一知识平台、分类型解析、统一 Claim 模型、独立 Evidence 链路、意图驱动检索、版本隔离和冲突治理。**

---

## 2. 目标与非目标

## 2.1 目标

1. 支持需求文档、测试用例、测试结果、数值表和需求存疑统一接入。
2. 为所有知识保留 `projectId`、版本、来源类型、状态和 Evidence。
3. 将文本事实、结构化参数、测试验证和未决问题分开建模。
4. 支持按查询意图选择知识来源和检索权重。
5. 支持需求与测试、需求与代码、需求与数值表之间的冲突检测。
6. 支持回答中展示来源、证据、版本和冲突状态。
7. 保持现有需求文档检索和语义图检索兼容。
8. 在不影响当前线上链路的前提下逐步灰度启用多源检索。

## 2.2 非目标

本阶段不做以下事情：

- 不将所有来源转换成没有类型的纯文本块；
- 不用测试结果覆盖需求规范；
- 不把开放存疑直接写成已确认 Claim；
- 不立即替换现有 Qdrant 或 SQLite 存储；
- 不一次性实现完整的自动需求裁决；
- 不让 LLM 自由决定来源权威级别；
- 不在没有 Evidence 的情况下发布混合来源结论。

---

## 3. 领域定义

### 3.1 需求文档

回答：

```text
系统应该怎样做？
业务规则是什么？
有哪些约束和验收标准？
```

建议来源类型：

```text
REQUIREMENT
```

通常属于：

```text
PRIMARY
```

### 3.2 测试用例

回答：

```text
如何验证需求？
给定什么前置条件？
预期结果是什么？
当前是否存在覆盖？
```

建议拆成：

```text
TEST_CASE      测试定义、步骤和预期结果
TEST_RESULT    实际执行结果、时间、环境和状态
```

测试用例不能自动等同于需求规范；测试通过也不能证明测试覆盖了最新需求。

### 3.3 数值表

回答：

```text
参数值是多少？
取值范围是多少？
单位、精度和边界是什么？
哪个版本生效？
```

建议来源类型：

```text
PARAMETER_TABLE
```

数值必须以结构化形式保存，不能只拼接成自然语言。

### 3.4 需求存疑

回答：

```text
目前有哪些未决问题？
哪些规则尚未确认？
哪些内容需要产品或业务负责人决策？
```

建议来源类型：

```text
DOUBT
```

状态不能直接映射为已确认事实，至少支持：

```text
OPEN
UNDER_DISCUSSION
RESOLVED
REJECTED
OBSOLETE
```

---

## 4. 总体架构

```text
                         +--------------------+
                         | 多源资料接入层     |
                         +---------+----------+
                                   |
        +--------------------------+--------------------------+
        |                          |                          |
        v                          v                          v
+---------------+          +---------------+          +---------------+
| Requirement   |          | Test / Result |          | Excel / Table |
| Parser        |          | Parser        |          | Parser        |
+-------+-------+          +-------+-------+          +-------+-------+
        |                          |                          |
        +--------------------------+--------------------------+
                                   v
                         +--------------------+
                         | Canonical Claim    |
                         | Normalization      |
                         +---------+----------+
                                   |
                   +---------------+----------------+
                   |                                |
                   v                                v
         +--------------------+           +--------------------+
         | Evidence Registry  |           | Conflict Analyzer  |
         | source/quote/row   |           | version/source     |
         +---------+----------+           +---------+----------+
                   |                                |
                   +---------------+----------------+
                                   v
                         +--------------------+
                         | Knowledge Store    |
                         | SQLite + Qdrant    |
                         +---------+----------+
                                   |
                         +---------v----------+
                         | Intent Router      |
                         | source filters     |
                         +---------+----------+
                                   |
             +---------------------+---------------------+
             |                     |                     |
             v                     v                     v
      规范检索 NORMATIVE     验证检索 VALIDATION   风险检索 DOUBT
             |                     |                     |
             +---------------------+---------------------+
                                   v
                         +--------------------+
                         | Evidence-backed   |
                         | Answer / Compare   |
                         +--------------------+
```

底层可以复用现有 Qdrant 和 SQLite，但所有记录必须增加或明确以下元数据：

```text
projectId
documentId
requirementVersion
sourceType
authority
status
effectiveFrom
effectiveTo
knowledgeType
module
factKey
evidenceId
```

---

## 5. 数据模型设计

## 5.1 SourceType 扩展

当前冲突模型已有 `REQUIREMENT`、`CODE`、`TEST`、`WIKI`。建议扩展为：

```java
public enum SourceType {
    REQUIREMENT,
    TEST_CASE,
    TEST_RESULT,
    PARAMETER_TABLE,
    DOUBT,
    CODE,
    WIKI
}
```

兼容策略：

- 旧 `TEST` 数据读取时映射为 `TEST_CASE`；
- 旧 `REQUIREMENT`、`CODE`、`WIKI` 保持不变；
- 旧数据不强制立即重写；
- 新数据禁止继续使用语义不明确的 `TEST`。

## 5.2 权威级别

```java
public enum Authority {
    PRIMARY,
    SECONDARY,
    DERIVED
}
```

建议默认映射：

| 来源 | 默认权威级别 | 说明 |
|---|---|---|
| 需求文档 | `PRIMARY` | 业务规范的原始来源 |
| 数值表 | `PRIMARY` | 参数事实的原始来源 |
| 测试用例 | `SECONDARY` | 验证定义，不直接覆盖规范 |
| 测试结果 | `SECONDARY` | 当前实现行为证据 |
| 代码 | `SECONDARY` | 实际实现证据 |
| 需求存疑 | `DERIVED` 或独立未决状态 | 不能作为确认事实 |
| Wiki | `DERIVED` | 必须关联原始 Evidence |

权威级别不能单独决定答案，还必须结合查询意图、版本和状态。

## 5.3 知识状态

```java
public enum KnowledgeStatus {
    DRAFT,
    EXTRACTED,
    SUPPORTED,
    VERIFIED,
    PASSED,
    FAILED,
    OPEN,
    RESOLVED,
    REJECTED,
    CONFLICTED,
    STALE,
    OBSOLETE
}
```

## 5.4 查询意图

```java
public enum KnowledgeQueryIntent {
    NORMATIVE,
    VALIDATION,
    PARAMETER,
    DOUBT,
    CONSISTENCY,
    IMPACT,
    GENERAL
}
```

## 5.5 统一 Claim 模型

建议在现有 `KnowledgeConflictModels.KnowledgeClaim` 基础上扩展：

```json
{
  "claimId": "claim-001",
  "projectId": "fengshen",
  "documentId": "order-requirement",
  "version": "5.1",
  "factKey": "permission.revocation.propagation_time",
  "subject": "权限撤销",
  "predicate": "MAX_DURATION",
  "object": "5",
  "valueType": "NUMBER",
  "unit": "MINUTE",
  "sourceType": "PARAMETER_TABLE",
  "authority": "PRIMARY",
  "status": "VERIFIED",
  "effectiveFrom": "5.1",
  "effectiveTo": null,
  "evidence": {
    "evidenceId": "sheet-5.1-row-12",
    "source": "封神5.1存疑.xlsx",
    "location": "5.1!B12:F12",
    "excerpt": "权限撤销传播时间最大为5分钟"
  },
  "supportingClaimIds": [],
  "conflictSetIds": [],
  "reviewReason": null
}
```

### 5.5.1 需求 Claim

额外字段：

```text
sectionPath
heading
quote
requirementId
acceptanceCriteria
```

### 5.5.2 测试用例 Claim

额外字段：

```text
testCaseId
preconditions
steps
expectedResult
coveredClaimIds
framework
```

### 5.5.3 测试结果 Claim

额外字段：

```text
testRunId
executionStatus
executedAt
environment
actualResult
failureMessage
```

### 5.5.4 数值表 Claim

额外字段：

```text
workbook
sheetName
rowNumber
columnRange
rawValue
normalizedValue
unit
minValue
maxValue
precision
inclusiveBoundary
```

### 5.5.5 存疑 Claim

额外字段：

```text
doubtId
question
context
owner
severity
dueDate
resolutionStatus
proposedOptions
relatedClaimIds
```

---

## 6. Evidence 设计

## 6.1 Evidence 统一原则

不同来源的 Evidence 位置不同，但必须统一为可回查的 Evidence 记录。

```text
需求文档：filename + section + startOffset + endOffset
测试用例：filePath + testCaseId + lineRange
测试结果：testRunId + resultLocation
数值表：workbook + sheet + row + column
需求存疑：xlsx + sheet + row 或文档段落
```

统一结构：

```json
{
  "evidenceId": "ev-001",
  "projectId": "fengshen",
  "version": "5.1",
  "sourceType": "PARAMETER_TABLE",
  "sourceName": "封神5.1存疑.xlsx",
  "location": "5.1!B12:F12",
  "excerpt": "权限撤销传播时间最大为5分钟",
  "contentHash": "sha256...",
  "resolutionStatus": "RESOLVED"
}
```

## 6.2 Evidence ID

Evidence ID 由服务端生成，不允许 LLM 直接生成。

建议：

```text
hash(
  projectId,
  documentId,
  version,
  sourceType,
  sourceName,
  location,
  excerptHash
)
```

## 6.3 Evidence 质量要求

正式 Claim 必须满足：

- Evidence 属于同一项目；
- Evidence 属于目标版本或明确的有效版本范围；
- Evidence 能通过 location 回查；
- Evidence 与 Claim 的 factKey 和 value 一致；
- Evidence 未被标记为过期或不可用。

---

## 7. 多源解析实现

## 7.1 需求文档解析器

复用现有需求文档导入和语义图构建链路：

```text
需求文件
  -> 清洗
  -> 结构化分块
  -> LLM 实体/关系抽取
  -> Evidence 定位
  -> Claim 归一化
  -> 语义图和向量索引
```

需要补充：

- 每个 Claim 标记 `sourceType=REQUIREMENT`；
- 关系 Claim 记录 `condition`、`scenario` 和版本；
- 将实体和关系同时投影到统一 Claim 索引；
- 保留需求语义图中的实体关系结构。

## 7.2 测试用例解析器

新增：

```text
TestCaseKnowledgeLoader
```

首期支持三种格式：

1. Markdown / 文本测试用例；
2. JSON / JSONL 测试数据；
3. Java / Python 测试源码中的测试方法和断言。

统一抽取字段：

```text
测试用例 ID
标题
模块
前置条件
操作步骤
预期结果
关联需求 ID
测试文件
测试方法
```

推荐解析顺序：

```text
结构化字段解析
  -> 关联 requirementId
  -> 提取 expected result
  -> 生成 TEST_CASE Claim
```

不要第一阶段就让 LLM 负责完整理解测试源码。先使用确定性解析和规则，再将复杂关联交给 LLM 辅助。

## 7.3 测试结果导入

新增：

```text
TestResultKnowledgeLoader
```

输入可以来自：

```text
JUnit XML
pytest XML
CI 构建结果
人工测试结果 JSON
```

生成：

```text
TEST_RESULT Claim
```

测试结果与测试用例必须通过稳定的：

```text
testCaseId
```

建立关联。

## 7.4 数值表解析器

在现有：

`/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/knowledge/ExcelKnowledgeLoader.java`

基础上新增：

```text
ParameterTableLoader
```

职责：

1. 识别表头；
2. 识别模块、参数、值、单位、范围、版本列；
3. 保留原始行列位置；
4. 对数值做类型化转换；
5. 生成参数 Claim；
6. 对无法识别的行保留为普通表格 Evidence；
7. 不覆盖现有需求存疑 Sheet 解析。

表头支持别名：

```text
模块 / 子系统 / 功能
参数 / 指标 / 配置项
最小值 / 下限 / Min
最大值 / 上限 / Max
单位 / Unit
版本 / 生效版本
说明 / 备注
```

数值类型必须区分：

```text
INTEGER
DECIMAL
PERCENTAGE
DURATION
COUNT
BOOLEAN
ENUM
TEXT
```

## 7.5 需求存疑解析器

复用现有：

`/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/knowledge/HistoricalDoubtService.java`

但将当前的提示词文本输出扩展为结构化 `DoubtClaim`：

```text
模块
问题
当前解答
版本
来源 Sheet
行号
状态
负责人
严重级别
```

历史存疑仍可以格式化为 LLM 上下文，但在正式检索中默认：

```text
DOUBT 不作为 VERIFIED 事实返回
```

只有当用户明确询问风险、待确认事项或历史存疑时，才提升其召回权重。

---

## 8. 存储设计

## 8.1 SQLite 结构化存储

新增或扩展以下表：

```text
knowledge_source
knowledge_document
knowledge_claim
knowledge_evidence
knowledge_claim_evidence
knowledge_test_case
knowledge_test_result
knowledge_parameter
knowledge_doubt
knowledge_conflict_set
knowledge_claim_review
```

### `knowledge_source`

```text
id
project_id
source_type
source_name
source_revision
content_hash
created_at
```

### `knowledge_claim`

```text
claim_id
project_id
document_id
version
fact_key
subject
predicate
object_value
value_type
unit
source_type
authority
status
effective_from
effective_to
review_reason
created_at
updated_at
```

### `knowledge_evidence`

```text
evidence_id
project_id
version
source_type
source_name
location
excerpt
content_hash
resolution_status
created_at
```

### `knowledge_claim_evidence`

```text
project_id
claim_id
evidence_id
relation_type
created_at
```

必须使用复合外键或服务端校验，禁止跨项目、跨版本错误关联。

## 8.2 Qdrant 索引

可以继续复用现有 collection，但 payload 必须增加：

```text
knowledgeType
sourceType
authority
status
factKey
unit
module
version
claimId
evidenceId
```

建议初期使用同一个 collection，依靠 payload filter 和检索融合隔离来源；当不同来源的召回质量和生命周期差异明显后，再拆分 collection。

## 8.3 索引写入顺序

```text
解析
  -> 写入 SQLite Draft
  -> 验证 Evidence
  -> 批量写入 Qdrant 临时索引
  -> 运行一致性检查
  -> 切换 live alias
  -> 标记知识版本 READY
```

任何 Qdrant 写入失败都不能将 SQLite Claim 标记为已发布。

---

## 9. 查询路由与检索融合

## 9.1 查询意图识别

新增：

```text
KnowledgeQueryIntentClassifier
```

首期使用规则 + 可选 LLM 回退：

| 意图 | 关键词示例 |
|---|---|
| `NORMATIVE` | 应该、必须、需求规定、规则 |
| `VALIDATION` | 测试、覆盖、验证、是否通过 |
| `PARAMETER` | 多少、上限、下限、单位、阈值 |
| `DOUBT` | 存疑、未确认、风险、待讨论 |
| `CONSISTENCY` | 是否一致、实现是否符合、需求和测试差异 |
| `IMPACT` | 影响哪些模块、修改后会影响什么 |
| `GENERAL` | 无法归类的普通查询 |

显式 `projectId`、`version` 和来源过滤优先于 LLM 路由结果。

## 9.2 来源过滤策略

### NORMATIVE

```text
REQUIREMENT
PARAMETER_TABLE
已验证 TEST_CASE（辅助）
```

### VALIDATION

```text
TEST_CASE
TEST_RESULT
REQUIREMENT（关联规范）
```

### PARAMETER

```text
PARAMETER_TABLE
REQUIREMENT
TEST_CASE（边界验证）
```

### DOUBT

```text
DOUBT
CONFLICT
REQUIREMENT
```

### CONSISTENCY

```text
REQUIREMENT
PARAMETER_TABLE
TEST_CASE
TEST_RESULT
CODE
DOUBT
```

### IMPACT

```text
REQUIREMENT_GRAPH
REQUIREMENT
PARAMETER_TABLE
TEST_CASE
```

## 9.3 统一打分

候选结果统一为：

```text
KnowledgeCandidate {
    candidateId
    claimId
    sourceType
    authority
    status
    textScore
    vectorScore
    graphScore
    intentScore
    evidenceScore
    freshnessScore
    conflictPenalty
    finalScore
}
```

建议初始公式：

```text
finalScore =
    0.25 * vectorScore
  + 0.20 * textScore
  + 0.20 * intentScore
  + 0.15 * graphScore
  + 0.15 * evidenceScore
  + 0.05 * freshnessScore
  - conflictPenalty
```

注意：

- 来源权威级别不直接替换所有分数；
- `REJECTED`、`STALE` 和 `OBSOLETE` 默认不返回；
- `OPEN DOUBT` 不能作为确认事实参与普通规范回答；
- `CONFLICTED` 候选可以返回，但必须标记冲突并降低事实置信度；
- 相同 `factKey` 的多来源结果应先聚合，再生成对比结果。

## 9.4 同一事实聚合

对于：

```text
REQUIREMENT：传播时间 <= 5 分钟
PARAMETER_TABLE：传播时间 <= 5 分钟
TEST_CASE：覆盖 5 分钟边界
TEST_RESULT：通过
```

不应返回四条重复文本，而应聚合为：

```text
结论：传播时间要求不超过 5 分钟。
规范证据：需求文档、数值表。
验证证据：测试用例已覆盖，最近一次执行通过。
状态：已确认。
```

如果出现：

```text
REQUIREMENT：5 分钟
TEST_CASE：10 分钟
```

则聚合为：

```text
存在需求与测试预期不一致，状态为 REVIEW_REQUIRED。
```

---

## 10. 冲突检测与回答策略

继续复用并扩展：

`/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/conflict/KnowledgeConflictService.java`

新增冲突类型：

```text
REQUIREMENT_PARAMETER
REQUIREMENT_DOUBT
PARAMETER_TEST
TEST_RESULT_EXPECTATION
VERSION_INTERNAL
SOURCE_STALE
MISSING_VALIDATION
```

冲突判断必须基于：

```text
projectId
version
factKey
normalized value
sourceType
authority
```

不要仅使用文本相似度判断冲突。

## 10.1 结论状态

```text
CONFIRMED
SUPPORTED
PARTIALLY_SUPPORTED
REVIEW_REQUIRED
CONFLICTED
NO_EVIDENCE
NO_RESULT
```

## 10.2 回答规则

### 无冲突

```text
直接给出结论 + 来源 + Evidence
```

### 有辅助来源不一致

```text
优先返回规范结论，同时展示测试或代码差异
```

### 规范本身冲突

```text
不自动裁决，返回 REVIEW_REQUIRED
```

### 只有存疑，没有规范

```text
返回当前未决问题，不生成确定事实
```

### 只有测试，没有需求

```text
说明“当前测试表现为……”，不能表述为“需求规定……”
```

---

## 11. API 改造

## 11.1 多源导入接口

新增或扩展：

```text
POST /api/knowledge/sources
POST /api/knowledge/ingest/requirements
POST /api/knowledge/ingest/test-cases
POST /api/knowledge/ingest/test-results
POST /api/knowledge/ingest/parameters
POST /api/knowledge/ingest/doubts
```

统一返回：

```json
{
  "sourceId": "source-001",
  "projectId": "fengshen",
  "version": "5.1",
  "sourceType": "PARAMETER_TABLE",
  "status": "READY",
  "claimCount": 42,
  "evidenceCount": 42,
  "conflictCount": 2,
  "warnings": []
}
```

## 11.2 统一检索接口

新增或扩展：

```text
POST /api/knowledge/search
```

请求：

```json
{
  "projectId": "fengshen",
  "version": "5.1",
  "query": "权限撤销传播时间是多少？",
  "intent": "PARAMETER",
  "sourceTypes": ["PARAMETER_TABLE", "REQUIREMENT"],
  "statuses": ["VERIFIED", "SUPPORTED"],
  "includeConflicts": true,
  "includeDoubts": false,
  "limit": 10
}
```

响应：

```json
{
  "intent": "PARAMETER",
  "answerStatus": "CONFIRMED",
  "claims": [],
  "evidence": [],
  "conflicts": [],
  "doubts": [],
  "explanations": [],
  "warnings": []
}
```

## 11.3 一致性分析接口

```text
POST /api/knowledge/consistency/analyze
```

用于比较：

```text
需求 vs 测试
需求 vs 数值表
需求 vs 代码
需求 vs 存疑
```

---

## 12. 分阶段实施计划

## Phase 0：元数据和兼容层

**优先级：P0**

任务：

1. 扩展 `SourceType`；
2. 增加 `authority`、`status`、`version` 和 `knowledgeType`；
3. 给现有 Qdrant payload 增加来源字段；
4. 给 SQLite 增加迁移版本；
5. 建立旧 `TEST` 到 `TEST_CASE` 的兼容映射；
6. 建立统一 Evidence ID 工具类。

验收：

- 旧需求文档检索结果不发生变化；
- 旧 Excel 存疑读取仍然可用；
- 旧冲突接口请求仍能解析；
- 新知识具备完整来源元数据。

## Phase 1：数值表和存疑结构化

**优先级：P0**

任务：

1. 保留 `ExcelKnowledgeLoader` 的存疑 Sheet 兼容逻辑；
2. 新增 `ParameterTableLoader`；
3. 新增结构化 `DoubtClaim`；
4. 保存 Workbook、Sheet、行列位置；
5. 保存单位、范围、精度和版本；
6. 将 OPEN 存疑从普通事实索引中隔离。

验收：

- 结构化数值可通过原始表格位置回查；
- 单位不会丢失；
- 数值范围和边界正确；
- OPEN 存疑不会被普通规范查询当成确定答案。

## Phase 2：测试用例和测试结果

**优先级：P1**

任务：

1. 新增测试用例解析器；
2. 支持测试用例与 `requirementId` 关联；
3. 新增 JUnit XML / pytest XML 结果导入；
4. 建立 `TEST_CASE -> REQUIREMENT` 关联；
5. 建立 `TEST_RESULT -> TEST_CASE` 关联；
6. 记录最近执行时间和环境。

验收：

- 可以查询某条需求是否有测试覆盖；
- 可以查询测试用例预期结果；
- 可以查询最近一次测试是否通过；
- 测试结果不能覆盖需求规范文本。

## Phase 3：统一 Claim 和冲突分析

**优先级：P1**

任务：

1. 扩展 `KnowledgeClaim`；
2. 建立 `factKey` 规范；
3. 实现同一事实的多源聚合；
4. 扩展 `KnowledgeConflictService`；
5. 增加版本、过期和存疑冲突；
6. 实现 `CONFIRMED / REVIEW_REQUIRED / CONFLICTED` 结论状态。

验收：

- 需求、参数、测试和存疑可以针对同一事实关联；
- 需求和测试不一致时生成冲突；
- 不同版本不会互相污染；
- 无 Evidence 的 Claim 不会被标记为 VERIFIED。

## Phase 4：意图路由和多源检索

**优先级：P1**

任务：

1. 新增查询意图分类器；
2. 增加来源过滤策略；
3. 扩展现有 Retrieval Pipeline；
4. 统一候选评分和来源加权；
5. 增加同一 factKey 的聚合返回；
6. 增加答案解释、Evidence 和冲突返回。

验收：

- 参数问题优先返回数值表；
- 规范问题优先返回需求文档；
- 测试覆盖问题优先返回测试用例和测试结果；
- 存疑问题能返回未决状态；
- 一致性问题能返回多源对比而不是单条文本。

## Phase 5：评估和灰度发布

**优先级：P1/P2**

任务：

1. 建立多源 Golden Dataset；
2. 增加规范、参数、验证、存疑和一致性评测；
3. 对比单源检索与多源检索；
4. 按项目开启灰度；
5. 记录误召回和错误来源；
6. 将人工修正结果回流为回归集。

验收：

- 多源检索不降低原有需求文档 Recall；
- 规范问题不存在无证据确定回答；
- 参数问题保留单位和版本；
- 冲突问题不会被静默合并；
- 多源回答具备完整来源解释。

---

## 13. 测试方案

## 13.1 单元测试

### SourceType 和兼容性

- 旧 `TEST` 能映射到 `TEST_CASE`；
- 新来源类型能正确序列化和反序列化；
- 缺失来源类型时使用安全默认值或明确失败。

### 数值表

- 表头别名识别；
- 数值、单位和范围解析；
- 空值和非数值内容处理；
- 行列位置稳定；
- 版本字段正确。

### 需求存疑

- Sheet 版本识别；
- 状态识别；
- 历史存疑不会混入当前确认事实；
- 存疑 Evidence 可回查。

### Claim 和冲突

- 相同 `factKey` 聚合；
- 需求与测试冲突；
- 需求与参数冲突；
- 不同版本隔离；
- 缺少原始 Evidence 时阻止发布。

## 13.2 集成测试

- 需求文档导入后产生 REQUIREMENT Claim；
- Excel 导入后产生 PARAMETER_TABLE 和 DOUBT Claim；
- 测试 XML 导入后产生 TEST_RESULT Claim；
- 所有 Claim 均能回查 Evidence；
- Qdrant 过滤 sourceType 和 version 正确；
- SQLite 和 Qdrant 的版本发布状态一致。

## 13.3 检索评估

至少建立以下 Query 类型：

```text
NORMATIVE
PARAMETER
VALIDATION
DOUBT
CONSISTENCY
IMPACT
```

指标：

```text
Recall@1 / @5 / @10
MRR
Evidence Hit Rate
Grounded Rate
Conflict Detection Accuracy
No-result Accuracy
P50 / P95
```

建议初始门槛：

```text
原有需求文档 Child Recall@10 不下降
参数问题 Unit Accuracy = 100%
Evidence Hit Rate >= 95%
Grounded Rate >= 95%
已发布无证据 Claim = 0
跨版本污染 = 0
```

---

## 14. 配置设计

建议增加配置前缀：

```yaml
app:
  rag:
    knowledge:
      multi-source-enabled: false
      parameter-table-enabled: true
      test-case-enabled: false
      test-result-enabled: false
      doubt-enabled: true
      conflict-analysis-enabled: true
      default-include-doubts: false
      default-include-conflicts: true
      source-routing-enabled: true
```

来源权重配置：

```yaml
app:
  rag:
    knowledge:
      source-weights:
        REQUIREMENT: 1.00
        PARAMETER_TABLE: 1.00
        TEST_CASE: 0.80
        TEST_RESULT: 0.75
        CODE: 0.70
        WIKI: 0.50
        DOUBT: 0.20
```

注意：权重只作为排序因素，不能绕过状态和 Evidence 质量门禁。

---

## 15. 迁移和回滚策略

## 15.1 数据迁移

按以下顺序执行：

```text
V1：现有知识和需求索引
V2：增加 sourceType / authority / status
V3：增加 knowledge_claim / evidence 表
V4：增加 parameter / doubt 表
V5：增加 test_case / test_result 表
V6：增加 conflict_set 和 factKey 索引
```

迁移要求：

- 使用 `PRAGMA user_version`；
- 每个迁移在事务中执行；
- 迁移前后生成结构校验；
- 旧索引保留到新索引验证成功；
- 失败时不切换 live alias。

## 15.2 灰度和回滚

```text
阶段 1：只导入，不进入线上检索
阶段 2：只返回 debug 多源结果
阶段 3：参数问题按白名单启用 PARAMETER_TABLE
阶段 4：验证问题启用 TEST_CASE / TEST_RESULT
阶段 5：一致性问题启用多源对比
阶段 6：全量启用
```

回滚方式：

- 关闭 `multi-source-enabled`；
- 继续使用旧需求文档检索；
- 保留已导入数据，禁止直接删除；
- 回滚 Qdrant live alias；
- 标记新来源索引为 `STALE`，等待重新验证。

---

## 16. 重点代码改造范围

| 文件或模块 | 改造内容 |
|---|---|
| `KnowledgeConflictModels.java` | 扩展 SourceType、Authority、Status 和 Claim 字段 |
| `KnowledgeConflictService.java` | 增加参数、存疑、测试结果和版本冲突 |
| `ExcelKnowledgeLoader.java` | 保留存疑兼容，抽取通用表格行和来源位置 |
| `HistoricalDoubtService.java` | 结构化 Doubt Claim、状态和 Evidence |
| `KnowledgeEntry` | 增加 sourceType、version、authority、evidence metadata |
| `QdrantHybridStore.java` | 增加多源 payload、过滤和索引切换 |
| `RetrievalPipeline.java` | 增加知识来源过滤和多源候选融合 |
| `QueryRouter.java` | 增加知识查询意图或接入独立意图分类器 |
| `SQLiteKnowledgeManagementStore.java` | 增加多源知识生命周期和 Claim 管理 |
| `RequirementGraphModels.java` | 统一需求图 Claim、Evidence 和版本元数据 |
| `RequirementGraphHybridSearchService.java` | 支持关系、参数和 Evidence 的多源融合 |
| `src/test/resources/evaluation/` | 增加多源 Golden Dataset |
| `src/test/java/.../evaluation` | 增加多源召回、冲突和 Evidence 评测 |

---

## 17. 交付验收标准

### 数据接入

- [ ] 需求文档、测试用例、测试结果、数值表和存疑均可独立导入；
- [ ] 每条知识都有项目、版本、来源类型和状态；
- [ ] 数值表保留单位、范围、精度和位置；
- [ ] 存疑保留问题、状态和负责人；
- [ ] 测试用例与测试结果能够关联。

### 事实治理

- [ ] 所有正式 Claim 都有 Evidence；
- [ ] 不同版本严格隔离；
- [ ] 同一事实支持多来源关联；
- [ ] 需求与测试、参数、代码冲突可检测；
- [ ] OPEN 存疑不会被当作 VERIFIED 事实；
- [ ] 无法判断时返回 `REVIEW_REQUIRED`。

### 检索体验

- [ ] 规范问题优先需求来源；
- [ ] 参数问题优先结构化数值来源；
- [ ] 验证问题优先测试来源；
- [ ] 风险问题优先存疑和冲突来源；
- [ ] 一致性问题返回多源对比；
- [ ] 每个结论展示来源和 Evidence；
- [ ] 多源检索不降低原有需求文档召回质量。

### 运行质量

- [ ] 多源索引具备独立状态和可回滚能力；
- [ ] 解析失败不会污染已发布知识；
- [ ] 关键阶段有日志和指标；
- [ ] 支持来源级别的重建和失效；
- [ ] 具备 Golden Dataset 和回归评测。

---

## 18. 推荐实施顺序

```text
第 1 步：统一 sourceType、authority、status、version 和 Evidence 元数据
第 2 步：结构化数值表和需求存疑
第 3 步：建立统一 Claim 和 factKey
第 4 步：接入测试用例和测试结果
第 5 步：扩展冲突检测和版本治理
第 6 步：增加查询意图和来源路由
第 7 步：实现多源候选融合和同事实聚合
第 8 步：增加多源评测集和灰度开关
第 9 步：按项目逐步启用多源检索
```

不建议一开始就把四类资料直接丢进同一个向量集合进行 Top-K 混排。正确的演进路径是：

```text
先分源接入
  -> 再统一 Claim
  -> 再统一 Evidence
  -> 再按意图路由
  -> 最后做多源融合
```

---

## 19. 最终架构原则

```text
需求文档   = 业务规范
测试用例   = 验证定义
测试结果   = 当前行为证据
数值表     = 结构化参数事实
需求存疑   = 未决问题和风险
```

最终系统应做到：

```text
一个知识平台
多种知识类型
不同权威级别
统一 Claim 模型
统一 Evidence 链路
严格版本隔离
显式冲突和不确定性
按问题意图检索
```

最终回答不应只返回“最相似的几段文本”，而应返回：

```text
结论
来源类型
权威级别
需求版本
Evidence
验证状态
冲突状态
未决问题
```

核心验收标准是：

> **可以把需求、测试、数值和存疑放进同一个知识平台，但任何结论都必须说明它来自什么来源、适用于哪个版本、是否有直接证据、是否存在冲突或未决事项。**
