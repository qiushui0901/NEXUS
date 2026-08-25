# 需求语义 Chunk 增强与混合检索开发方案

**版本：** 0.9.5  
**状态：** Proposed  
**编制日期：** 2026-08-25  
**适用项目：** request-RAG / Nexus 需求知识、代码知识和跨源对齐系统  
**文档目标：** 在不替代现有需求图谱、代码图谱和跨源对齐能力的前提下，增加“LLM 语义增强 Chunk”层，提升局部语义召回、条件/数值/单位检索和最终问答质量。

---

## 1. 背景与问题定义

当前系统已经具备多条知识处理链路：

```text
需求文档
  ├── RequirementIngestionService
  │     └── 原始文本分块 / Qdrant 向量检索
  ├── RequirementGraphBuildService
  │     └── 窗口规划 / LLM 实体关系抽取 / Evidence / SQLite 需求图
  ├── MultiSourceKnowledgeStore
  │     └── 需求、参数、测试、结果、存疑统一 Claim
  └── CodeKnowledgeService / SQLiteSymbolGraphStore
        └── 代码符号、调用关系和代码语义检索
```

当前主要问题不是“没有图谱”，而是缺少一个专门面向召回的中间层：

```text
原始 Chunk -> LLM 语义增强 -> 可检索语义 Chunk
```

当前需求图谱主要抽取：

- Entity；
- Relation；
- Uncertainty；
- Evidence；
- Relation condition / scenario 自由文本。

但用户实际查询经常是：

- 谁在什么条件下可以做什么？
- 达到多少数值、使用什么单位后发生什么？
- 哪些条件是同时满足、任一满足还是前置条件？
- 这个规则是否被代码、参数表或测试结果验证？
- 需求文档和当前代码实现是否一致？

单纯的原始文本向量检索对词面差异敏感；单纯的需求图谱又容易受到窗口边界、实体合并和关系本体限制影响。因此 0.9.5 采用分层方案：

```text
原始 Chunk
  + LLM Semantic Chunk Annotation
  + Requirement Graph
  + Multi-source Claim
  + Code Graph
  + Alignment Graph
  -> Candidate Fusion
  -> Version / Authority / Conflict Governance
  -> Evidence-first Answer
```

---

## 2. 当前代码基线

### 2.1 已有能力

| 能力 | 当前实现 | 作用 |
|---|---|---|
| 原始需求文档导入 | `/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/service/RequirementIngestionService.java` | 文本预处理、分块、写入需求向量库 |
| 原始需求混合检索 | `/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/retrieval/QdrantHybridStore.java` | Dense / sparse / hybrid 检索 |
| 需求窗口规划 | `/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphWindowPlanner.java` | 按父块切分有重叠窗口 |
| 需求实体关系抽取 | `/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphExtractionService.java` | LLM 抽取实体、关系、证据和存疑 |
| 需求图谱构建 | `/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphBuildService.java` | 构建、恢复、预算、快照和持久化 |
| 需求图谱检索适配 | `/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/knowledge/multisource/RequirementGraphCandidateAdapter.java` | 将已审核需求图投影为统一 Claim |
| 多源 Claim 检索 | `/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/knowledge/multisource/MultiSourceSearchService.java` | 按来源、字段、冲突和状态排序 |
| 代码语义 Chunk | `/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/model/CodeChunk.java` | 已有业务描述、关键词、用户问题、同义词字段 |
| 代码符号图 | `/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/code/SQLiteSymbolGraphStore.java` | 代码符号、调用和关系 |
| 跨源对齐 | `/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/knowledge/multisource/alignment/` | 版本上下文、业务概念、代码参数/测试对齐、漂移和存疑影响 |
| 金标评测 | `/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/evaluation/` | 抽取、证据、失败状态和口径评估 |

### 2.2 当前缺口

1. 需求 Chunk 没有和代码 Chunk 对齐的语义增强字段。
2. 条件、事件、数值、单位、范围和操作符没有形成统一的结构化 Claim。
3. LLM 增强结果没有独立存储、版本化和重跑机制。
4. 多源检索目前主要依赖统一 Claim 的确定性字段评分，没有统一融合原始向量、语义向量、图谱候选和代码候选的 `CandidateFusion` 层。
5. 需求图谱适配器使用 `findFirst()` 选择快照，缺少明确的 Active Snapshot 选择机制。
6. 未审核的 LLM 语义结果只能作为内部候选，不能影响规范事实；但如果完全不参与检索，又会损失召回。
7. 当前评测主要验证抽取结构，尚未系统评估“原始 Chunk vs 语义 Chunk vs 完整混合检索”的召回差异。

---

## 3. 版本目标

### 3.1 主要目标

#### 目标 A：增加语义增强 Chunk 层

对每一个需求父块或抽取窗口，生成可检索的结构化语义标注：

- 主体是谁；
- 客体是谁；
- 在什么条件下发生；
- 发生什么事件或动作；
- 数值、单位、范围和比较关系；
- 可检索的业务别名；
- 用户可能提出的问题；
- 明确缺失的上下文；
- 每条结果对应的原文证据。

#### 目标 B：语义增强结果不直接覆盖权威事实

语义增强结果属于：

```text
检索候选 / 语义索引 / Claim 候选
```

不是自动确认的产品规则。

只有经过：

- 原文证据校验；
- Schema 校验；
- 多源对齐；
- 代码或参数确认；
- 人工审核；

后，才能升级为 `VERIFIED` 或正式发布 Claim。

#### 目标 C：建立统一候选融合检索

融合以下候选来源：

```text
原始文本向量
语义增强 Chunk 向量
统一 Claim
需求图谱实体/关系
代码符号和调用图
跨源对齐结果
```

#### 目标 D：建立可度量的效果对照

至少支持以下三种检索基线：

```text
A. RAW：原始 Chunk + 原始向量
B. SEMANTIC：原始 Chunk + LLM 语义增强 Chunk
C. FUSED：RAW + SEMANTIC + Claim + Graph + Code + Alignment
```

### 3.2 非目标

0.9.5 不做以下事情：

- 不替换现有 Qdrant 原始需求检索；
- 不将所有 LLM 推断直接写入正式需求图；
- 不因为语义增强而放宽代码事实优先原则；
- 不将需求图谱改造成通用图数据库；
- 不一次性扩展大量 RelationType；
- 不通过语义摘要覆盖原始 Evidence；
- 不在没有金标和离线指标的情况下直接调整线上权重；
- 不把 `codeFact echo` 误称为代码事实自主抽取。

---

## 4. 核心设计原则

### 4.1 原文是证据，语义增强是索引

```text
rawText = 原始证据
semanticAnnotation = LLM 结构化索引
claim = 可治理事实候选
alignment = 跨源裁决
```

最终回答引用原始证据，不直接引用没有回链的语义摘要。

### 4.2 明确事实和推断事实分层

每一个语义字段和 Claim 都必须标记：

```text
EXPLICIT   原文明确表达
DERIVED    根据同一文档中多条明确事实确定推导
INFERRED   模型推断，不能直接作为规范事实
UNKNOWN    输入缺少必要上下文
```

默认检索优先级：

```text
代码 VERIFIED
> 参数 VERIFIED
> 测试观测
> 需求 VERIFIED
> 需求 EXTRACTED
> LLM INFERRED
> OPEN DOUBT
```

### 4.3 不为了“Chunk 独立回答”而编造上下文

Prompt 必须明确：

```text
如果 Chunk 没有主体、条件、数值或单位，不得补造。
必须放入 missingContext 或 uncertainties。
```

“独立可回答”表示提高检索表达完整性，不表示允许模型虚构缺失事实。

### 4.4 版本和来源必须贯穿所有层

所有语义增强、向量、Claim、图谱和对齐结果必须至少绑定：

```text
projectId
documentId
requirementVersion
sourceRevision/contentHash
sourceType
```

代码事实还必须绑定：

```text
repositoryId
commitSha
environment
```

### 4.5 候选检索和规范检索分离

```text
Candidate Retrieval：允许 EXTRACTED / INFERRED，必须显示状态和置信度
Normative Retrieval：只允许 VERIFIED / PUBLISHED
```

任何未审核候选不能在最终答案中伪装成确认事实。

### 4.6 失败必须可见

语义增强或图谱构建失败不能静默变成“没有知识”。必须区分：

```text
SUCCESS
EMPTY_RESULT
MODEL_TIMEOUT
MODEL_RATE_LIMITED
JSON_PARSE_FAILED
SCHEMA_INVALID
PARTIAL_FAILURE
FAILURE
```

---

## 5. 目标架构

```text
                           ┌──────────────────────┐
                           │  原始需求/参数/测试/代码 │
                           └──────────┬───────────┘
                                      │
                 ┌────────────────────┼────────────────────┐
                 │                    │                    │
                 ▼                    ▼                    ▼
        ┌────────────────┐   ┌────────────────┐   ┌────────────────┐
        │ Raw Chunk      │   │ Code Symbol    │   │ Other Sources  │
        │ 原始文本证据    │   │ 代码事实/调用图  │   │ 参数/测试/存疑   │
        └───────┬────────┘   └───────┬────────┘   └───────┬────────┘
                │                    │                    │
                ▼                    │                    │
        ┌────────────────┐            │                    │
        │ Semantic Chunk │            │                    │
        │ LLM 语义增强    │            │                    │
        └───────┬────────┘            │                    │
                │                    │                    │
       ┌────────┴────────┐           │                    │
       ▼                 ▼           ▼                    ▼
┌──────────────┐  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ Raw Vector   │  │ Semantic     │ │ Requirement  │ │ Multi-source │
│ Index        │  │ Vector Index │ │ Graph        │ │ Claim Store  │
└──────┬───────┘  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
       └──────────────────┼──────────────┼─────────────────┘
                          ▼              ▼
                   ┌──────────────────────────┐
                   │ Candidate Fusion Service │
                   │ 统一候选归一化/去重/排序    │
                   └─────────────┬────────────┘
                                 ▼
                   ┌──────────────────────────┐
                   │ Authority / Version /    │
                   │ Conflict / Status Gate   │
                   └─────────────┬────────────┘
                                 ▼
                   ┌──────────────────────────┐
                   │ Evidence-first Answer    │
                   │ 原文证据 + 当前代码事实     │
                   └──────────────────────────┘
```

---

## 6. 数据模型设计

## 6.1 `requirement_semantic_annotation`

建议在当前需求图 SQLite 数据库或统一多源 SQLite 数据库中新增表：

```sql
create table if not exists requirement_semantic_annotation (
  annotation_id text primary key,
  project_id text not null,
  document_id text not null,
  requirement_version text not null,
  source_revision text not null,
  source_chunk_id text not null,
  parent_id text,
  window_id text,
  source_file text,
  content_hash text not null,
  raw_text text not null,
  normalized_text text,
  semantic_summary text,
  model text not null,
  prompt_version text not null,
  schema_version text not null,
  extraction_status text not null,
  claim_status text not null,
  confidence real,
  created_at text not null,
  updated_at text not null,
  error_code text,
  unique(project_id, document_id, requirement_version,
         source_chunk_id, content_hash, model, prompt_version)
);
```

### 字段说明

| 字段 | 说明 |
|---|---|
| `annotation_id` | 语义标注唯一 ID |
| `source_chunk_id` | 原始 Chunk 身份，不允许只依赖向量 point ID |
| `window_id` | 若从需求图窗口抽取，则绑定窗口 |
| `content_hash` | 语义标注输入内容哈希 |
| `semantic_summary` | 面向检索的结构化文本摘要 |
| `model` | 实际使用的模型 |
| `prompt_version` | Prompt 版本 |
| `extraction_status` | 抽取状态 |
| `claim_status` | 事实治理状态 |
| `error_code` | 失败原因 |

## 6.2 `requirement_semantic_entity`

```sql
create table if not exists requirement_semantic_entity (
  annotation_id text not null,
  entity_index integer not null,
  entity_name text not null,
  entity_type text,
  aliases_json text not null,
  certainty text not null,
  evidence_quote text,
  primary key(annotation_id, entity_index),
  foreign key(annotation_id) references requirement_semantic_annotation(annotation_id)
);
```

## 6.3 `requirement_semantic_condition`

```sql
create table if not exists requirement_semantic_condition (
  annotation_id text not null,
  condition_index integer not null,
  subject text,
  field_name text,
  operator text,
  value text,
  unit text,
  value_type text,
  logical_group text,
  certainty text not null,
  evidence_quote text,
  primary key(annotation_id, condition_index),
  foreign key(annotation_id) references requirement_semantic_annotation(annotation_id)
);
```

`operator` 建议只允许：

```text
EQ
NE
GT
GTE
LT
LTE
IN
NOT_IN
BETWEEN
BEFORE
AFTER
REQUIRES
FORBIDS
UNKNOWN
```

`value_type` 建议只允许：

```text
NUMBER
STRING
BOOLEAN
ENUM
DATE
DURATION
RANGE
UNKNOWN
```

## 6.4 `requirement_semantic_event`

```sql
create table if not exists requirement_semantic_event (
  annotation_id text not null,
  event_index integer not null,
  subject text,
  event_name text not null,
  object_name text,
  result text,
  condition_text text,
  certainty text not null,
  evidence_quote text,
  primary key(annotation_id, event_index),
  foreign key(annotation_id) references requirement_semantic_annotation(annotation_id)
);
```

## 6.5 `requirement_semantic_numeric_fact`

```sql
create table if not exists requirement_semantic_numeric_fact (
  annotation_id text not null,
  numeric_index integer not null,
  subject text,
  field_name text,
  value text not null,
  normalized_value real,
  unit text,
  normalized_unit text,
  operator text,
  range_min real,
  range_max real,
  certainty text not null,
  evidence_quote text,
  primary key(annotation_id, numeric_index),
  foreign key(annotation_id) references requirement_semantic_annotation(annotation_id)
);
```

## 6.6 `requirement_semantic_question`

```sql
create table if not exists requirement_semantic_question (
  annotation_id text not null,
  question_index integer not null,
  question_text text not null,
  question_type text,
  primary key(annotation_id, question_index),
  foreign key(annotation_id) references requirement_semantic_annotation(annotation_id)
);
```

`question_type` 可包含：

```text
WHO
CONDITION
EVENT
VALUE
UNIT
IMPACT
IMPLEMENTATION
CONFLICT
DOUBT
```

## 6.7 Claim 与 Evidence 绑定

语义增强产生的 Claim 不应复制出另一套 Evidence 体系。优先复用当前：

```text
knowledge_claim
knowledge_claim_evidence
knowledge_evidence
```

或者由语义标注生成候选 Claim 时，使用：

```text
sourceAnnotationId
sourceEvidenceId
certainty
claimStatus
```

建议增加可追踪字段：

```sql
alter table knowledge_claim add column source_annotation_id text;
alter table knowledge_claim add column certainty text;
alter table knowledge_claim add column extraction_model text;
alter table knowledge_claim add column extraction_prompt_version text;
```

如果 SQLite 迁移策略不允许直接 `alter`，应通过版本化迁移脚本或幂等 `addColumnIfMissing` 实现。

---

## 7. 语义增强输出契约

## 7.1 推荐 JSON Schema

```json
{
  "entities": [
    {
      "name": "成长基金",
      "type": "FEATURE",
      "aliases": ["成长基金玩法"],
      "certainty": "EXPLICIT",
      "evidenceQuote": "成长基金"
    }
  ],
  "conditions": [
    {
      "subject": "玩家",
      "field": "level",
      "operator": "GTE",
      "value": "30",
      "unit": "级",
      "valueType": "NUMBER",
      "logicalGroup": "unlock",
      "certainty": "EXPLICIT",
      "evidenceQuote": "达到30级后"
    }
  ],
  "events": [
    {
      "subject": "成长基金",
      "event": "开放",
      "object": "",
      "result": "可进入成长基金",
      "condition": "玩家等级达到30级",
      "certainty": "EXPLICIT",
      "evidenceQuote": "开放成长基金"
    }
  ],
  "numericFacts": [
    {
      "subject": "玩家",
      "field": "level",
      "value": "30",
      "unit": "级",
      "operator": "GTE",
      "certainty": "EXPLICIT",
      "evidenceQuote": "达到30级"
    }
  ],
  "claims": [
    {
      "factKey": "growth_fund.unlock.min_level",
      "subject": "成长基金",
      "predicate": "UNLOCK_MIN_LEVEL",
      "value": "30",
      "unit": "级",
      "certainty": "EXPLICIT",
      "evidenceQuote": "达到30级后开放成长基金"
    }
  ],
  "questionExpansions": [
    {
      "text": "玩家多少级可以开启成长基金？",
      "type": "CONDITION"
    }
  ],
  "uncertainties": [],
  "missingContext": [],
  "selfContained": true
}
```

## 7.2 Prompt 要求

Prompt 必须包含以下约束：

```text
1. 只能根据输入 Chunk 提取事实，不得补造外部知识。
2. 每条实体、条件、事件、数值和 Claim 必须提供原文连续 evidenceQuote。
3. 如果主体、条件、数值或单位在 Chunk 中没有出现，放入 missingContext，不要猜测。
4. certainty=EXPLICIT 仅用于原文明确表达。
5. certainty=DERIVED 只能用于同一 Chunk 内可直接组合的事实。
6. certainty=INFERRED 不能自动升级为 VERIFIED。
7. 数值必须保留原始 value，同时输出 normalizedValue/normalizedUnit。
8. 不要输出不存在于输入文本中的别名。
9. 不要把问题文本本身当成确认事实。
10. 只返回 JSON，不返回 Markdown 或解释。
```

## 7.3 服务端校验

新增：

```text
RequirementSemanticAnnotationValidator
```

必须执行：

1. JSON 反序列化校验；
2. 数组数量上限校验；
3. 枚举值校验；
4. `evidenceQuote` 必须是 `rawText` 连续子串；
5. 数值和单位格式校验；
6. `GTE` / `BETWEEN` 等操作符与值类型校验；
7. Claim 的 `factKey` 非空和格式校验；
8. Entity 引用存在性校验；
9. 禁止空字符串伪造命中；
10. 对异常输出返回稳定错误码。

错误码建议：

```text
SEMANTIC_JSON_PARSE_FAILED
SEMANTIC_SCHEMA_INVALID
SEMANTIC_EVIDENCE_UNAVAILABLE
SEMANTIC_NUMERIC_INVALID
SEMANTIC_FACT_KEY_INVALID
SEMANTIC_MODEL_TIMEOUT
SEMANTIC_MODEL_RATE_LIMITED
SEMANTIC_MODEL_UNAVAILABLE
```

---

## 8. Java 模块和类设计

建议新增包：

```text
src/main/java/com/example/requirementrag/requirement/semantic/
```

### 8.1 核心模型

```text
RequirementSemanticModels.java
```

包含：

```java
SemanticChunkAnnotation
SemanticEntity
SemanticCondition
SemanticEvent
SemanticNumericFact
SemanticClaim
SemanticQuestion
SemanticStatus
SemanticCertainty
SemanticErrorCode
```

### 8.2 抽取服务

```text
RequirementSemanticAnnotationService.java
```

职责：

- 接收原始 Chunk / Window；
- 调用 LLM；
- 记录模型和 Prompt 版本；
- 执行重试和错误分类；
- 调用 Validator；
- 返回结构化语义标注；
- 不直接发布正式 Claim。

### 8.3 Prompt 服务

```text
RequirementSemanticPromptService.java
```

职责：

- 生成 System Prompt；
- 生成 User Prompt；
- 控制 Prompt 版本；
- 统一模型输入文本长度；
- 对窗口元数据进行脱敏或标准化。

### 8.4 持久化

```text
SQLiteRequirementSemanticStore.java
```

职责：

- annotation 幂等写入；
- 按 sourceRevision 查询；
- 按 windowId 查询；
- 查询待重试、待审核和失败标注；
- 删除过期模型版本；
- 保存 Evidence 关联。

### 8.5 构建服务

```text
RequirementSemanticBuildService.java
```

职责：

```text
原始需求版本
  -> 获取父块/窗口
  -> 计算 annotation input hash
  -> 跳过未变化内容
  -> 批量执行 LLM 标注
  -> 写入语义注释
  -> 生成候选 Claim
  -> 可选生成 semantic vector points
```

### 8.6 候选 Claim 投影

```text
RequirementSemanticClaimAdapter.java
```

职责：

- 将语义条件、事件、数值转换为统一 Claim；
- 绑定原始证据；
- 生成稳定 factKey；
- 标记 `EXTRACTED / INFERRED`；
- 不覆盖代码和参数事实；
- 供 Candidate Retrieval 使用。

### 8.7 候选融合

```text
CandidateFusionService.java
CandidateModels.java
CandidateSource.java
CandidateFusionPolicy.java
```

职责：

- 合并原始向量候选；
- 合并语义向量候选；
- 合并统一 Claim；
- 合并需求图和代码图候选；
- 去重；
- 版本过滤；
- 权威性、状态和冲突排序；
- 输出统一候选结构。

---

## 9. 向量索引设计

## 9.1 MVP：双 Point 方案

0.9.5 初期不直接改变现有向量模型，采用两个 Point：

```text
representation = RAW
representation = SEMANTIC
```

### RAW Point

```json
{
  "id": "raw:chunk-001",
  "text": "原始 Chunk 文本",
  "payload": {
    "projectId": "immortal-game-service",
    "documentId": "fengshen",
    "requirementVersion": "5.1",
    "sourceType": "REQUIREMENT",
    "chunkId": "chunk-001",
    "representation": "RAW",
    "contentHash": "..."
  }
}
```

### SEMANTIC Point

```json
{
  "id": "semantic:annotation-001",
  "text": "主体：玩家；条件：等级>=30级；事件：成长基金开放；问题：玩家多少级可以开启成长基金？",
  "payload": {
    "projectId": "immortal-game-service",
    "documentId": "fengshen",
    "requirementVersion": "5.1",
    "sourceType": "REQUIREMENT",
    "annotationId": "annotation-001",
    "sourceChunkId": "chunk-001",
    "representation": "SEMANTIC",
    "semanticStatus": "EXTRACTED",
    "claimStatus": "CANDIDATE",
    "factKeys": ["growth_fund.unlock.min_level"],
    "entities": ["成长基金", "玩家"],
    "units": ["级"]
  }
}
```

## 9.2 语义文本生成规则

语义向量文本必须稳定、可重复、可调试：

```text
[原文]
{rawText}

[主体]
{entities}

[条件]
{subject} {field} {operator} {value}{unit}

[事件]
{subject} {event} {object}

[事实]
{factKey} = {value}{unit}

[可能的问题]
{questionExpansions}
```

不要只把 JSON 原样转成文本；应使用稳定字段顺序，避免同一个事实因 JSON 字段顺序变化导致语义向量大幅漂移。

## 9.3 向量点生命周期

语义标注发生以下变化时，必须生成新 Point 或原子替换：

- `contentHash` 变化；
- 模型变化；
- Prompt 版本变化；
- schema 版本变化；
- 业务版本变化；
- 标注状态从候选升级为验证状态。

旧 Point 不能继续混入新版本检索。

---

## 10. Candidate Fusion 设计

## 10.1 候选来源

```text
RAW_VECTOR
SEMANTIC_VECTOR
MULTI_SOURCE_CLAIM
REQUIREMENT_GRAPH
CODE_GRAPH
ALIGNMENT
TEST_RESULT
```

## 10.2 统一候选模型

```java
public record RetrievalCandidate(
        String candidateId,
        CandidateSource source,
        String projectId,
        String documentId,
        String version,
        String factKey,
        String title,
        String text,
        String subject,
        String predicate,
        String value,
        String unit,
        String status,
        String authority,
        double retrievalScore,
        double authorityScore,
        double evidenceScore,
        double conflictPenalty,
        List<String> evidenceLocations,
        List<String> relatedCandidateIds,
        Map<String, Object> metadata
) {
}
```

## 10.3 初始排序公式

0.9.5 初始不要让权重完全由 LLM 学习，采用可解释加权：

```text
finalScore =
    0.30 * normalizedSemanticScore
  + 0.20 * normalizedLexicalScore
  + 0.15 * factKeyScore
  + 0.10 * entityScore
  + 0.08 * numericUnitScore
  + 0.07 * versionScore
  + 0.05 * authorityScore
  + 0.05 * evidenceScore
  - conflictPenalty
  - stalePenalty
  - inferredPenalty
```

建议初始状态系数：

| 状态 | 系数 |
|---|---:|
| `VERIFIED` | 1.00 |
| `PUBLISHED` | 1.00 |
| `EXTRACTED` | 0.75 |
| `DERIVED` | 0.70 |
| `INFERRED` | 0.50 |
| `OPEN` | 0.20 |
| `CONFLICTED` | 0.10 |
| `STALE` | 0.00 |
| `REJECTED` | 0.00 |

注意：状态系数只影响候选排序，不代表低状态结果可以作为最终确认事实。

## 10.4 去重规则

优先级：

```text
source + canonical factKey + version
```

没有 factKey 时使用：

```text
source + normalized(subject) + normalized(predicate) + normalized(value) + version
```

不能仅按文本相似度去重，避免：

- 不同版本事实被合并；
- 同名不同业务概念被合并；
- 需求和代码事实被错误覆盖。

---

## 11. 需求图谱与语义 Chunk 的关系

### 11.1 不做重复存储

两层职责如下：

| 层 | 主要职责 | 是否可作为规范事实 |
|---|---|---:|
| 原始 Chunk | 保留证据和原文结构 | 是，作为证据 |
| Semantic Chunk | 提升召回和局部理解 | 否，默认是候选 |
| Requirement Graph | 表达实体、关系和跨窗口结构 | 经审核后可以 |
| Unified Claim | 多源统一事实 | 经治理后可以 |
| Code Graph | 实现事实 | 是，代码事实基线 |
| Alignment Graph | 版本、冲突、漂移和裁决 | 是，作为裁决结果 |

### 11.2 推荐数据流

```text
SemanticAnnotation
  -> SemanticEntity / Condition / Event / NumericFact
  -> Candidate Claim
  -> BusinessFactKeyService
  -> 与参数/测试/代码对齐
  -> 生成 AlignmentRelation / DriftItem
  -> 审核后升级为 VERIFIED Claim
```

### 11.3 不能做的事情

- 不能用语义 Chunk 覆盖当前代码值；
- 不能因语义 Chunk 与需求文档相似就判断代码已实现；
- 不能把 `INFERRED` 直接放入规范检索；
- 不能因为向量相似就生成正式图关系；
- 不能把多个版本的同名 factKey 混为一个事实。

---

## 12. Active Snapshot 改造

当前需求图适配器存在通过 `listSnapshots(...).findFirst()` 选择快照的风险。0.9.5 建议新增：

```text
ActiveRequirementGraphService
```

### 12.1 Active Snapshot 表

```sql
create table if not exists knowledge_active_requirement_snapshot (
  project_id text not null,
  document_id text not null,
  requirement_version text not null,
  snapshot_id text not null,
  status text not null,
  activated_by text,
  activated_at text not null,
  reason text,
  primary key(project_id, document_id, requirement_version)
);
```

### 12.2 选择规则

1. 优先读取 Active Snapshot；
2. Active Snapshot 必须是 `VERIFIED` 或 `PUBLISHED`；
3. 快照必须匹配当前 `sourceRevision` 或被显式标记为可用；
4. 没有 Active Snapshot 时不自动随机选择；
5. 返回候选检索时可选择最近的 `EXTRACTED` 快照，但必须标记为 `CANDIDATE`；
6. 旧 Active Snapshot 失效后，明确变为 `STALE`，不能静默回退到任意快照。

---

## 13. 版本化事实 Key

新增：

```text
BusinessFactKeyService
```

建议统一格式：

```text
{concept}.{field}
```

例如：

```text
growth_fund.unlock.min_level
growth_fund.reward.currency
growth_fund.purchase.required
growth_fund.claim.endpoint
growth_fund.claim.cooldown
```

版本和项目不直接拼入领域 Key，而作为外层 Scope：

```text
projectId = immortal-game-service
version = 5.1
factKey = growth_fund.unlock.min_level
```

这样同一个业务概念可以跨版本对比，但不会在事实层混淆版本。

### 13.1 Key 生成规则

1. 优先使用受控业务词汇表；
2. 其次使用人工审核的 alias；
3. 最后才允许 LLM 生成候选 Key；
4. LLM 生成的 Key 必须经过格式校验；
5. 不允许把原始句子直接当 factKey；
6. Key 一旦进入 VERIFIED 事实，不因 Prompt 变化自动重命名。

---

## 14. API 设计

## 14.1 语义标注构建

```http
POST /api/knowledge/requirement-semantic/build
```

请求：

```json
{
  "projectId": "immortal-game-service",
  "documentId": "fengshen",
  "requirementVersion": "5.1",
  "sourceRevision": "...",
  "mode": "WINDOW",
  "publishVectors": true,
  "allowCandidateRetrieval": true,
  "promptVersion": "requirement-semantic-v1"
}
```

响应：

```json
{
  "jobId": "semantic-build:...",
  "status": "BUILDING",
  "projectId": "immortal-game-service",
  "documentId": "fengshen",
  "requirementVersion": "5.1",
  "totalChunks": 120,
  "completedChunks": 0,
  "failedChunks": 0
}
```

## 14.2 查询语义标注

```http
GET /api/knowledge/requirement-semantic/annotations
```

参数：

```text
projectId
documentId
requirementVersion
status
factKey
windowId
page
size
```

## 14.3 候选 Claim 审核

```http
POST /api/knowledge/requirement-semantic/claims/{claimId}/review
```

请求：

```json
{
  "decision": "VERIFY|REJECT|KEEP_CANDIDATE|MARK_CONFLICT",
  "reviewer": "user-001",
  "reason": "代码 commit 中存在明确实现证据",
  "evidenceIds": ["evidence-001"]
}
```

## 14.4 混合候选检索

```http
POST /api/knowledge/search/fused
```

请求：

```json
{
  "projectId": "immortal-game-service",
  "documentId": "fengshen",
  "requirementVersion": "5.1",
  "query": "玩家多少级可以开启成长基金？",
  "mode": "FUSED",
  "includeCandidates": true,
  "includeCode": true,
  "includeConflicts": true,
  "limit": 10,
  "page": 0
}
```

响应必须区分：

```json
{
  "status": "SUPPORTED|CANDIDATE_ONLY|CONFLICTED|NO_RESULT",
  "answerCandidates": [],
  "evidence": [],
  "conflicts": [],
  "sourceBreakdown": {
    "RAW_VECTOR": 2,
    "SEMANTIC_VECTOR": 3,
    "MULTI_SOURCE_CLAIM": 2,
    "REQUIREMENT_GRAPH": 1,
    "CODE_GRAPH": 1
  },
  "warnings": []
}
```

---

## 15. 开发阶段和任务拆分

## Phase 1：语义模型和 Prompt 契约

**目标：** 建立可测试、可版本化的语义增强 JSON 契约。

### 任务

1. 新增 `RequirementSemanticModels`；
2. 新增 `SemanticCertainty`、`SemanticStatus`、`SemanticErrorCode`；
3. 新增 Prompt 服务；
4. 新增离线 Validator；
5. 增加 30 条固定 JSON fixture；
6. 覆盖主体、条件、事件、数值、单位、否定、范围和缺失上下文。

### 验收标准

- 非法 JSON 可以被识别；
- quote 不在原文中时失败；
- 数值单位可以规范化；
- `INFERRED` 不会被标记成 `EXPLICIT`；
- 缺少条件时输出 `missingContext`；
- 每条 Claim 至少有一个 Evidence quote。

## Phase 2：语义标注存储和幂等构建

**目标：** 将 LLM 语义结果独立持久化，支持失败重试和增量重跑。

### 任务

1. 新增 SQLite 表；
2. 新增 `SQLiteRequirementSemanticStore`；
3. 新增 `RequirementSemanticAnnotationService`；
4. 实现 annotation input hash；
5. 实现 `(contentHash, model, promptVersion, schemaVersion)` 幂等；
6. 支持只重跑失败项；
7. 记录模型调用次数、耗时、错误码和估计 token。

### 验收标准

- 相同输入重复构建不重复调用模型；
- 修改 Prompt 后生成新 annotation，不覆盖旧结果；
- 只修改一个 Chunk 时，只重跑该 Chunk；
- 模型超时、限流和 Schema 错误可区分；
- 失败结果可恢复。

## Phase 3：语义向量索引

**目标：** 将语义增强结果用于召回，但不污染原始向量索引。

### 任务

1. 增加 `SEMANTIC` representation；
2. 生成稳定 semanticText；
3. 写入语义 Point；
4. 支持按版本删除和替换；
5. 支持语义 Point 与 annotation/evidence 关联；
6. 增加 RAW / SEMANTIC 单独检索接口或内部通道。

### 验收标准

- 通过 annotationId 可以回查原文；
- 语义 Point 不会召回其他项目或版本；
- 新版本替换后旧版本不会被默认召回；
- 删除失败不会导致新旧 Point 混杂；
- 向量写入失败可被报告并重试。

## Phase 4：结构化 Claim 投影

**目标：** 将条件、事件、数值和单位接入统一 Claim。

### 任务

1. 新增 `RequirementSemanticClaimAdapter`；
2. 接入 `BusinessFactKeyService`；
3. 将条件映射为 Claim；
4. 将数值和单位映射为 Claim；
5. 将事件映射为候选关系或 Claim；
6. 与参数、测试、代码对齐；
7. 建立 `sourceAnnotationId` 和 Evidence 追溯。

### 验收标准

- 可以查询 `growth_fund.unlock.min_level`；
- 可以区分 `30级` 与 `30秒`；
- 可以保留 `GTE`、`BETWEEN` 等操作符；
- 语义候选不会覆盖代码值；
- 代码和参数不一致时生成 Drift/Conflict，而不是静默合并。

## Phase 5：Candidate Fusion

**目标：** 统一原始向量、语义向量、Claim、需求图和代码图候选。

### 任务

1. 新增统一 Candidate 模型；
2. 新增各来源 Candidate Adapter；
3. 实现版本过滤；
4. 实现状态和权威排序；
5. 实现 factKey/实体/文本去重；
6. 实现 RRF 或加权融合；
7. 输出 sourceBreakdown 和 warnings。

### 验收标准

- 不同通道的结果可统一分页；
- 同一事实不会重复展示；
- 代码事实优先于需求候选；
- CONFLICTED 事实会被标记；
- OPEN/INFERRED 结果不会伪装成确认事实；
- 所有候选可回查 Evidence。

## Phase 6：Active Snapshot 与线上接入

**目标：** 将语义 Chunk 和需求图接入稳定的版本化检索入口。

### 任务

1. 新增 Active Snapshot 表和服务；
2. 修改 `RequirementGraphCandidateAdapter`；
3. 修改多源检索入口；
4. 增加 `FUSED` 检索模式；
5. 增加灰度开关；
6. 支持回退到现有 RAW 检索；
7. 增加监控指标。

### 灰度策略

```text
semantic.enabled=false        默认关闭
semantic.shadow=true          只记录候选，不影响答案
semantic.candidate=true       允许候选召回，必须标状态
semantic.normative=false      不允许直接作为规范答案
semantic.fused=false          评测通过后开启
```

## Phase 7：评测和质量门禁

**目标：** 用数据证明语义增强确实带来收益，而不是增加复杂度。

### 任务

1. 建立 RAW / SEMANTIC / FUSED 三套评测模式；
2. 新增 100～300 条查询集；
3. 按场景分层；
4. 统计召回、证据、数值、单位和冲突指标；
5. 建立线上 Shadow 评测；
6. 设置发布门禁。

---

## 16. 测试方案

## 16.1 单元测试

### Validator

- 空实体；
- 空 evidenceQuote；
- quote 不在原文；
- 非法 operator；
- 数值单位不一致；
- invalid factKey；
- INFERRED / EXPLICIT 状态校验。

### Store

- 幂等 upsert；
- contentHash 变化生成新结果；
- Prompt version 变化不覆盖旧结果；
- 失败重试；
- 版本查询隔离；
- 删除过期向量。

### Candidate Fusion

- 相同 factKey 去重；
- 版本不同不去重；
- 代码事实优先；
- CONFLICTED 保留冲突标记；
- evidence 合并；
- 分页边界稳定。

## 16.2 集成测试

至少覆盖：

```text
Raw Chunk -> Semantic Annotation -> Store
Semantic Annotation -> Claim Adapter
Claim Adapter -> MultiSourceSearchService
Raw + Semantic Vector -> CandidateFusion
RequirementGraph + CodeGraph -> Alignment Result
```

## 16.3 跨窗口测试

必须覆盖：

1. 主体在窗口 A、条件在窗口 B；
2. 条件被窗口边界切开；
3. 同一实体在多个窗口使用别名；
4. 同一 factKey 在不同窗口重复出现；
5. 两个同名但不同类型实体不能错误合并；
6. 跨窗口证据分别回查；
7. 某窗口失败时不影响其他窗口结果，但整体状态为 `PARTIAL_FAILURE`。

## 16.4 多源冲突测试

构造：

```text
需求：成长基金 >= 30级开放
参数：min_level = 35
代码：playerLevel >= 40
测试：39级未开放
```

期望：

```text
代码事实保留为实现基线；
参数事实保留为配置事实；
需求事实保留为历史意图；
生成 DOCUMENT_DRIFT / CONFIG_DRIFT / TEST_DRIFT；
最终答案显示冲突和来源，不直接覆盖。
```

---

## 17. 评测指标

## 17.1 语义抽取指标

```text
entityNamePrecision / Recall / F1
entityTypedF1
conditionPrecision / Recall / F1
eventPrecision / Recall / F1
numericFactPrecision / Recall / F1
unitAccuracy
operatorAccuracy
factKeyAccuracy
uncertaintyPrecision / Recall
```

## 17.2 证据指标

```text
quoteSourceMatchRate
windowOffsetValidityRate
sourceFileOffsetValidityRate
claimEvidenceSupportRate
semanticAnnotationTraceabilityRate
```

## 17.3 检索指标

```text
Recall@5
Recall@10
MRR
NDCG@10
FactKeyHitRate
EntityHitRate
ConditionHitRate
NumericUnitHitRate
CrossWindowRecall
CrossSourceRecall
CodeEvidenceRecall
```

## 17.4 可信度指标

```text
versionContaminationRate
wrongAuthorityRate
conflictMissRate
unsupportedPublishedClaimRate
staleClaimRecallRate
candidateAsNormativeErrorRate
```

## 17.5 运行指标

```text
semanticAnnotationSuccessRate
semanticAnnotationFailureRate
modelTimeoutRate
averageAnnotationLatencyMs
p95AnnotationLatencyMs
semanticVectorWriteSuccessRate
fusionLatencyMs
p95FusionLatencyMs
```

## 17.6 正式门槛建议

首次上线前建议满足：

| 指标 | 建议门槛 |
|---|---:|
| semantic annotation schema valid rate | >= 99% |
| evidence quote source match | >= 98% |
| condition F1 | >= 0.80 |
| numeric fact F1 | >= 0.85 |
| unit accuracy | >= 0.95 |
| Recall@10 相对 RAW 提升 | >= 10% |
| version contamination rate | 0 |
| rejected/stale claim 误召回率 | 0 |
| unsupported published claim rate | 0 |
| Candidate Fusion p95 | <= 500ms，不含 LLM |

这些是初始门槛，必须根据真实金标规模和场景分布调整，不能用小样本直接作为最终上线门槛。

---

## 18. 可观测性和审计

### 18.1 日志字段

每次语义增强至少记录：

```text
projectId
documentId
requirementVersion
sourceChunkId
windowId
contentHash
model
promptVersion
attempt
latencyMs
tokenEstimate
status
errorCode
```

### 18.2 指标

```text
nexus.requirement.semantic.started
nexus.requirement.semantic.completed
nexus.requirement.semantic.failed
nexus.requirement.semantic.latency
nexus.requirement.semantic.evidence_invalid
nexus.requirement.semantic.vector_write_failed
nexus.retrieval.fusion.candidates
nexus.retrieval.fusion.conflicts
nexus.retrieval.fusion.version_filtered
nexus.retrieval.fusion.latency
```

### 18.3 审计要求

任何以下操作必须有审计记录：

- 手工确认 Semantic Claim；
- 手工拒绝 Claim；
- 修改 factKey；
- 修改实体别名；
- 激活新 Snapshot；
- 发布新向量版本；
- 标记冲突解决；
- 关闭存疑。

---

## 19. 失败处理与恢复

### 19.1 LLM 调用失败

```text
MODEL_TIMEOUT -> 指数退避，达到上限后记录失败
MODEL_RATE_LIMITED -> 延迟重试，受全局并发限制
JSON_PARSE_FAILED -> 不盲目重试超过配置次数
SCHEMA_INVALID -> 记录原始响应摘要，进入人工/离线修复
MODEL_UNAVAILABLE -> 标记待重试
```

### 19.2 向量写入失败

语义注释和向量写入必须分离：

```text
annotation persisted
vector write failed
status = VECTOR_PENDING
```

下次任务只补写向量，不重新调用 LLM。

### 19.3 部分失败

语义标注构建结果必须区分：

```text
SUCCESS
PARTIAL_FAILURE
FAILED
```

部分失败时：

- 已成功的 annotation 可以用于候选检索；
- 不能生成 `VERIFIED` 需求快照；
- 必须显示失败窗口数量和错误码；
- 支持只重试失败窗口。

---

## 20. 配置设计

建议在 `application.yml` 增加：

```yaml
app:
  rag:
    requirement-semantic:
      enabled: false
      candidate-retrieval-enabled: false
      normative-retrieval-enabled: false
      vector-index-enabled: false
      model: ${REQUIREMENT_SEMANTIC_MODEL:}
      prompt-version: requirement-semantic-v1
      schema-version: v1
      max-input-chars: 12000
      max-entities-per-chunk: 30
      max-conditions-per-chunk: 30
      max-events-per-chunk: 30
      max-numeric-facts-per-chunk: 30
      max-questions-per-chunk: 20
      max-retries: 2
      max-model-calls: 1000
      max-wall-clock-seconds: 1800
      max-estimated-tokens: 1000000
      write-raw-vector: true
      write-semantic-vector: false
      allow-inferred-candidate: true
      active-snapshot-required: true
      fusion:
        enabled: false
        semantic-weight: 0.30
        lexical-weight: 0.20
        fact-key-weight: 0.15
        authority-weight: 0.05
        evidence-weight: 0.05
```

配置原则：

- 默认关闭线上语义向量写入；
- 先 Shadow，再 Candidate，再 Fused；
- 生产模式禁止读取跨版本候选；
- 所有模型、Prompt 和 Schema 版本必须写入结果。

---

## 21. 发布和回滚方案

### 21.1 发布步骤

```text
1. 发布数据模型和幂等迁移
2. 发布 Semantic Annotation 离线任务
3. 只生成 annotation，不写线上语义向量
4. 运行 Validator 和证据质量评测
5. 开启 semantic shadow
6. 比较 RAW / SEMANTIC / FUSED 指标
7. 开启 candidate retrieval
8. 通过人工审核和线上观测后开启 fused retrieval
9. 最后才允许 verified semantic claim 进入规范回答
```

### 21.2 回滚条件

出现以下任一情况，立即关闭语义候选或融合开关：

- 版本污染率 > 0；
- `REJECTED` 或 `STALE` Claim 被规范回答使用；
- Evidence 回查失败率超过门槛；
- 数值或单位错误率明显上升；
- Candidate Fusion 延迟超预算；
- LLM 成本超预算；
- 语义增强结果覆盖代码事实；
- 线上回答出现无法解释的幻觉。

### 21.3 回滚方式

```text
semantic.fused=false
semantic.candidate-retrieval-enabled=false
semantic.vector-index-enabled=false
```

回滚不删除原始 Chunk、需求图谱和语义标注，以便复盘和修复。

---

## 22. 开发顺序建议

建议严格按照以下顺序开发：

### 第一阶段：先做最小闭环

```text
原始 Chunk
  -> LLM Semantic Annotation
  -> Validator
  -> SQLite Store
  -> semanticText
  -> 离线向量 Point
  -> RAW / SEMANTIC 召回对照
```

先不要接入全部代码和对齐关系。

### 第二阶段：接入结构化 Claim

```text
condition / numericFact / event
  -> factKey
  -> candidate Claim
  -> MultiSourceSearchService
```

### 第三阶段：接入候选融合

```text
RAW_VECTOR
+ SEMANTIC_VECTOR
+ MULTI_SOURCE_CLAIM
+ REQUIREMENT_GRAPH
+ CODE_GRAPH
```

### 第四阶段：接入权威裁决

```text
Code Fact
+ Parameter Fact
+ Test Observation
+ Requirement Intent
+ Doubt
  -> AlignmentGraph
  -> Drift / Conflict / Publication
```

### 第五阶段：线上灰度

```text
Shadow -> Candidate -> Fused -> Normative
```

---

## 23. 0.9.5 交付清单

### 必须完成

- [ ] 语义增强 JSON 契约；
- [ ] 服务端 Validator；
- [ ] Semantic Annotation 数据模型；
- [ ] SQLite 幂等存储；
- [ ] LLM 调用错误分类和重试；
- [ ] Evidence quote 回查；
- [ ] `SemanticChunkAnnotationService`；
- [ ] 语义文本生成器；
- [ ] RAW / SEMANTIC 离线检索对照；
- [ ] 语义标注测试 fixture；
- [ ] 语义增强评测报告；
- [ ] 配置开关和 Shadow 模式；
- [ ] 文档和迁移说明。

### 应该完成

- [ ] 条件、数值、单位 Claim 投影；
- [ ] `BusinessFactKeyService`；
- [ ] `CandidateFusionService`；
- [ ] Active Snapshot；
- [ ] 多源候选融合接口；
- [ ] 代码事实优先排序；
- [ ] 冲突和版本污染测试。

### 可以延后

- [ ] Named Vectors；
- [ ] 学习排序模型；
- [ ] 文档级跨窗口 LLM Verifier；
- [ ] 大规模关系本体扩展；
- [ ] 图数据库迁移；
- [ ] 全自动 Claim 发布。

---

## 24. 最终验收标准

0.9.5 不能只以“代码能运行”作为完成标准，必须同时满足：

### 数据正确性

- 每个语义事实都有原文 Evidence；
- 不同项目、版本和 commit 不串数据；
- 失败标注不会被伪装成空知识；
- `INFERRED` 不会自动成为 `VERIFIED`；
- `REJECTED`、`STALE`、`CONFLICTED` 不进入规范回答。

### 检索效果

- SEMANTIC 相对 RAW 在核心问题集上 Recall@10 有明确提升；
- 条件、数值、单位问题能够稳定召回正确 Chunk；
- 跨窗口问题至少不低于 RAW 基线；
- 融合检索不会明显增加版本污染和错误候选。

### 跨源治理

- 语义候选能够关联到统一 factKey；
- 需求、参数、测试和代码可以围绕同一个业务概念对齐；
- 发生不一致时返回 Drift/Conflict，不直接覆盖；
- 代码事实仍然是实现事实基线。

### 运行可靠性

- 语义标注可重试、可恢复、可幂等；
- 向量失败可单独补写；
- Snapshot 和 annotation 都可审计；
- 关闭 Feature Flag 后能够回退到原有 RAW 检索；
- 关键指标和错误码可观测。

---

## 25. 结论

0.9.5 的核心不是“再做一套知识库”，而是为当前系统增加一层职责明确的语义索引：

```text
原始 Chunk 负责保留事实证据
Semantic Chunk 负责提高召回和局部理解
Requirement Graph 负责实体、关系和跨窗口结构
Multi-source Claim 负责统一事实表达
Code Graph 负责实现事实
Alignment Graph 负责版本、冲突和权威裁决
Candidate Fusion 负责把这些结果统一召回
```

最终目标是：

```text
找得到：Semantic Chunk 提升召回
看得懂：条件、事件、数值和单位结构化
连得起来：Requirement Graph 和 Multi-source Claim
验得准：Code Graph 和 Alignment Graph
说得清：Evidence-first、版本隔离和冲突透明
```

因此，0.9.5 最重要的技术决策是：

> **LLM 语义增强结果首先作为可追溯的检索候选，不直接替代原始证据，也不直接覆盖代码事实；只有经过证据校验和跨源治理后，才允许进入正式规范事实层。**
