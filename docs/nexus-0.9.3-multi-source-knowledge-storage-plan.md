# Nexus 0.9.3 多源需求知识数据库落库方案

> **版本**：0.9.3
> **状态**：数据架构说明（已落地，待治理与压测）
> **基线提交**：`d269a35`（多源知识生产级 Review 整改）
> **适用范围**：需求文档、数值表、测试用例、测试结果、需求存疑、需求语义图与代码知识

---

## 0. 实施状态（截至 0.9.4）

> 本文档最初是待实施路径；目前主体已落地，建议作为**数据架构说明**而非 Sprint 待办清单。

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 统一资料目录 `knowledge_document` / `knowledge_document_version` / `knowledge_evidence` | [已实现] | `MultiSourceKnowledgeStore` 建表、写入与查询；Evidence ID 稳定生成。 |
| 统一 Claim 主表 `knowledge_claim` + Claim–Evidence 多对多 | [已实现] | 四类来源分表可映射为统一 Claim，fact_key 稳定生成。 |
| 统一关系 `knowledge_relation` + 抽取运行审计 `knowledge_extraction_run` | [已实现] | 离线关系生产 + 人工审核 + 抽取运行账本。 |
| 发布目录 `knowledge_active_version` + Qdrant live alias | [已实现] | 发布/回滚/按业务版本隔离；大数据量行为待线上压测。 |
| 数值表 / 测试用例 / 测试结果 / 存疑主数据映射 | [已实现] | 各加载器写入分表并 `syncClaims` 生成统一 Claim。 |
| 旧快照数据全量回填、数据核验与历史版本治理 | [待办] | 兼容旧 `projectId+version` 快照路径仍并存。 |
| 查询路径彻底只读统一主数据 | [待办] | 仍存在旧表投影兼容链路。 |
| 所有导入器统一走 DocumentVersion → Evidence → Claim 单一入口 | [待办] | 当前各导入器已趋同，仍有历史兼容分支。 |
| 线上压测、迁移回滚演练、指标门禁 | [待验证] | 需要真实项目数据与灰度数据证明。 |

---

## 1. 结论与目标

当前系统已经具备多源知识的可用闭环：数值表、存疑、测试用例、测试结果已在 SQLite 中按来源分表保存；需求语义图和代码检索结果已在检索时投影为 `UnifiedKnowledgeClaim`；Qdrant 已保存 `sourceType` payload 并支持多源过滤；跨源关系、冲突分析、灰度开关、分页与可观测性也已接入。

0.9.3 不应推倒现有 `MultiSourceKnowledgeStore`，而应在其上补齐**可审计的统一资料、版本、证据与 Claim 主数据层**。目标是让任意回答都能说明：

- 结论来自哪一份资料、哪个业务版本和哪一处原文；
- 该结论是需求规范、参数配置、测试期望、实际执行结果、存疑还是代码实现；
- 它是否已审核、是否过期、是否与其他来源冲突；
- 它由哪次解析/抽取产生，并可安全重跑、发布和回滚。

原则：**可以混合输入给 LLM 做实体与关系候选抽取，但绝不能把不同来源直接混成同等可信的事实。**

---

## 2. 当前实现基线

### 2.1 已有能力

| 能力 | 当前实现 | 结论 |
| --- | --- | --- |
| 数值表结构化存储 | `multi_source_parameter` | 已保留工作簿、sheet、行列、单位、范围、精度、状态与证据位置 |
| 存疑结构化存储 | `multi_source_doubt` | 已有问题、答案、负责人、严重级别、候选方案与生命周期状态 |
| 测试用例与执行结果 | `multi_source_test_case`、`multi_source_test_result` | 已支持 JSON/JSONL/JUnit XML 导入和状态持久化 |
| 跨源关系 | `multi_source_relation` | 已支持 `VERIFIES`、`SUPPORTS`、`RAISES_DOUBT` 等关系 |
| 需求图接入 | `RequirementGraphCandidateAdapter` | 已将已验证实体、关系及其 Evidence 投影为 `REQUIREMENT` Claim |
| 代码接入 | `CodeKnowledgeCandidateAdapter` | 已将代码符号、文件行号、commit SHA 投影为 `CODE` Claim |
| 多源检索 | `MultiSourceSearchService` | 已按意图路由、来源过滤、评分、冲突惩罚、稳定分页与解释输出 |
| 向量检索 | `QdrantHybridStore` | 已支持 `sourceType` 过滤、版本化 physical collection 与 live alias |
| 生产防护 | d269a35 | Alias 原子切换、流式 token 去重、关系按当前页边界生成、分页元数据已整改 |

### 2.2 当前缺口

现有 `UnifiedKnowledgeClaim` 是很好的**统一检索视图**，但并非统一持久化主数据：

1. 原始资料和解析版本没有统一的 `Document / DocumentVersion` 目录；
2. `evidenceLocation` 主要仍是字符串或图 Evidence ID，不能统一查询、审计和复用；
3. Claim 多由 Adapter 在查询时投影，尚不能完整追踪“抽取模型、提示词版本、输入 hash、审核结果”；
4. 关系已能保存，但缺少统一的关系状态、置信度、确认方式和证据引用；
5. `replaceSnapshot(projectId, version, ...)` 适合当前全量重导，但不适合长期保留多次导入、抽取和发布历史。

---

## 3. 目标架构

```text
原始资料 / 代码提交
   │
   ├── 文档注册与版本化（Document / DocumentVersion）
   ├── 解析切片与定位（Evidence）
   ├── 规则 + LLM 候选抽取（Claim / Entity / Relation）
   ├── 标准化、冲突、人工审核（Status / Review）
   ├── 发布版本切换（SQLite 状态 + Qdrant live alias）
   └── 多源检索只读（意图路由 + 当前页关系裁剪）
```

职责边界：

- **SQLite / 后续 PostgreSQL**：事实、版本、证据、关系、状态、审计与发布目录，是事实主库。
- **需求语义图 SQLite**：需求实体、关系、冲突和原始图 Evidence；通过稳定 ID 与统一 Evidence 关联。
- **Qdrant**：文本/向量召回索引，不是事实真相来源；payload 必须带版本和来源元数据。
- **代码知识库**：符号、调用关系、仓库和 commit 证据；代码代表“实现事实”，不自动覆盖“规范事实”。

---

## 4. 统一数据模型

### 4.1 来源、权威性和状态

沿用现有 `SourceType`，新入库禁止再使用旧 `TEST`，读取旧数据时规范化为 `TEST_CASE`：

```text
REQUIREMENT       需求文档/需求语义图
PARAMETER_TABLE   数值表
TEST_CASE         测试用例定义
TEST_RESULT       测试执行结果
DOUBT             需求存疑
CODE              代码实现证据
WIKI              设计说明、Wiki
```

权威级别只由系统配置与来源类型决定，LLM 不可自行决定：

```text
PRIMARY     已发布需求、受控数值表
SECONDARY   测试用例、代码实现、已审核 Wiki
DERIVED     LLM 推导、聚合结论
```

建议知识状态：

```text
DRAFT / SUPPORTED / VERIFIED / PUBLISHED / OPEN / UNDER_DISCUSSION
RESOLVED / REJECTED / CONFLICTED / STALE / OBSOLETE / UNAVAILABLE
```

普通规范检索仅使用满足下列条件的记录：

```text
status in (VERIFIED, PUBLISHED)
且 sourceType != DOUBT
且在有效版本窗口内
且存在至少一条可回查 Evidence
```

### 4.2 原始资料表

新增 `knowledge_document`，登记逻辑资料而不存放重复正文：

```sql
create table knowledge_document (
  document_id text primary key,
  project_id text not null,
  source_type text not null,
  logical_name text not null,
  original_name text,
  storage_uri text not null,
  authority text not null,
  created_at text not null,
  unique(project_id, source_type, logical_name)
);
```

`logical_name` 示例：`combat-requirement`、`battle-parameter`、`test-case-suite`。

### 4.3 资料版本表

每一次内容变化生成不可变版本；不要覆盖历史版本。

```sql
create table knowledge_document_version (
  document_version_id text primary key,
  document_id text not null,
  project_id text not null,
  business_version text not null,
  content_hash text not null,
  parser_version text not null,
  extraction_version text not null,
  source_commit_sha text,
  status text not null,
  imported_at text not null,
  published_at text,
  foreign key(document_id) references knowledge_document(document_id),
  unique(document_id, business_version, content_hash, parser_version, extraction_version)
);
```

`business_version` 必须与当前 `MultiSourceKnowledgeStore` 的 `version` 一致；代码资料额外使用 `source_commit_sha` 固定实现快照。

> 唯一键必须包含 `business_version`，避免同一内容在不同业务版本下被误复用为同一版本记录（0.9.3 Phase A 已按此落地）。

### 4.4 Evidence 证据表

`evidenceLocation` 继续保留作兼容展示字段，但新增结构化 Evidence 作为唯一事实定位。

```sql
create table knowledge_evidence (
  evidence_id text primary key,
  document_version_id text not null,
  project_id text not null,
  source_type text not null,
  locator text not null,
  excerpt text not null,
  excerpt_hash text not null,
  start_line integer,
  end_line integer,
  sheet_name text,
  row_number integer,
  column_range text,
  repository_id text,
  commit_sha text,
  symbol_name text,
  created_at text not null,
  foreign key(document_version_id) references knowledge_document_version(document_version_id),
  unique(document_version_id, locator, excerpt_hash)
);
```

定位示例：

| 来源 | `locator` |
| --- | --- |
| Word/Markdown 需求 | `combat.md#3.2/paragraph-4` |
| 数值表 | `封神数值.xlsx#技能参数!B12:G12` |
| 测试用例 | `testcases.json#TC-1001` |
| JUnit 结果 | `surefire-report.xml#DamageServiceTest#cooldownShouldBe12` |
| 存疑 | `doubt.xlsx#需求存疑!A9:H9` |
| 代码 | `src/main/java/.../DamageService.java:120-145@<commitSha>` |

Evidence ID 由服务端根据 `documentVersion + locator + excerptHash` 稳定生成；LLM 只能引用候选 Evidence ID，不能创造 ID 或伪造位置。

### 4.5 统一 Claim 主表

新增 `knowledge_claim` 作为来源无关的事实主记录。现有 `UnifiedKnowledgeClaim` 可由该表和来源扩展表投影生成，避免破坏检索 API。

```sql
create table knowledge_claim (
  claim_id text primary key,
  project_id text not null,
  document_version_id text not null,
  source_type text not null,
  authority text not null,
  fact_key text not null,
  subject text not null,
  predicate text not null,
  object_value text,
  value_type text,
  unit text,
  status text not null,
  confidence real,
  effective_from text,
  effective_to text,
  extraction_method text not null,
  extraction_run_id text,
  created_at text not null,
  updated_at text not null,
  foreign key(document_version_id) references knowledge_document_version(document_version_id),
  -- 同一 fact_key 允许不同 object_value 并存（冲突/历史），仅对完全重复的事实去重
  unique(project_id, document_version_id, source_type, fact_key, object_value)
);

create index idx_knowledge_claim_project_version_fact
  on knowledge_claim(project_id, document_version_id, fact_key);
```

`fact_key` 必须由确定性规则生成，例如：

```text
<projectId>|<businessVersion>|<module>|<normalizedSubject>|<normalizedPredicate>
```

示例：

```text
fengshen|1.8.0|combat|火球术|冷却时间
```

同一 `fact_key` 可以有多条 Claim，分别记录需求规定、数值配置、测试预期、实际结果和代码实现；禁止“后写入覆盖先写入”。

### 4.6 Claim 与 Evidence 的多对多关联

一条 Claim 可能由多个段落或多个单元格共同支持；一条 Evidence 也可支持多个 Claim。

```sql
create table knowledge_claim_evidence (
  claim_id text not null,
  evidence_id text not null,
  role text not null default 'SUPPORTS',
  created_at text not null,
  primary key(claim_id, evidence_id),
  foreign key(claim_id) references knowledge_claim(claim_id),
  foreign key(evidence_id) references knowledge_evidence(evidence_id)
);
```

`role` 可为：`SUPPORTS`、`CONTRADICTS`、`CONTEXT`、`RESOLUTION`。

### 4.7 来源扩展表的演进

保留现有表，不复制或丢失其特有字段；将其主键与统一 Claim 关联：

| 当前表 | 保留的专属信息 | 迁移方向 |
| --- | --- | --- |
| `multi_source_parameter` | workbook、sheet、行列、范围、精度、边界 | `claim_id` 关联 `knowledge_claim`；`evidence_location` 映射为 `evidence_id` |
| `multi_source_doubt` | question、answer、owner、severity、dueDate、options | `doubt_id` 作为 Claim 或关联到一个 DOUBT Claim |
| `multi_source_test_case` | 前置条件、步骤、预期、框架、测试方法 | `claim_id` 保持不变并追加 `document_version_id` |
| `multi_source_test_result` | runId、状态、环境、实际结果、失败信息 | 每次执行是独立历史记录，不要按 claim 覆盖历史 |
| `multi_source_relation` | 现有跨源关系 | 迁移为统一关系表或增加状态/审计列 |

重点：`multi_source_test_result` 当前适合“当前快照”检索。0.9.3 应增加 `test_run_id + executed_at + environment` 唯一性，确保可以回答“最近一次成功/指定环境最后一次结果”，同时保留全部历史。

### 4.8 统一关系表

扩展或逐步替换 `multi_source_relation`：

```sql
create table knowledge_relation (
  relation_id text primary key,
  project_id text not null,
  version text not null,
  source_claim_id text not null,
  target_claim_id text not null,
  relation_type text not null,
  status text not null,
  confidence real,
  evidence_id text,
  extraction_method text not null,
  confirmation_method text,
  confirmation_reason text,
  created_at text not null,
  updated_at text not null,
  foreign key(source_claim_id) references knowledge_claim(claim_id),
  foreign key(target_claim_id) references knowledge_claim(claim_id),
  foreign key(evidence_id) references knowledge_evidence(evidence_id),
  unique(project_id, version, source_claim_id, target_claim_id, relation_type)
);
```

关系类型沿用并扩展：

```text
VERIFIES        TEST_CASE -> REQUIREMENT
SUPPORTS        PARAMETER_TABLE -> REQUIREMENT
RAISES_DOUBT    DOUBT -> REQUIREMENT
COVERS          TEST_RESULT -> TEST_CASE
IMPLEMENTED_BY  REQUIREMENT -> CODE
CONTRADICTS     Claim <-> Claim
```

关系状态：

```text
RULE_PROPOSED / LLM_CONFIRMED / LLM_REJECTED / HUMAN_CONFIRMED / STALE
```

LLM 确认只改变关系的审核状态，不应修改原始 Claim。现有 `LlmCrossSourceRelationConfirmer` 的 fail-open 策略可保留，但生产发布关系须明确记录模型确认或人工确认方式。

### 4.9 抽取运行审计表

```sql
create table knowledge_extraction_run (
  extraction_run_id text primary key,
  project_id text not null,
  document_version_id text not null,
  parser_name text not null,
  parser_version text not null,
  model_name text,
  prompt_version text,
  input_hash text not null,
  output_hash text,
  status text not null,
  prompt_tokens integer,
  completion_tokens integer,
  error_message text,
  started_at text not null,
  finished_at text,
  foreign key(document_version_id) references knowledge_document_version(document_version_id)
);
```

该表与现有 `ChatTokenUsageTracker` 的运行时指标互补：前者做可审计的单次任务账本，后者做 Micrometer 聚合监控。

---

## 5. 数据导入与发布流程

### 5.1 幂等导入流程

```text
1. 注册 Document，计算 content_hash
2. 若 document + content_hash + parser_version 已存在且成功，则复用结果
3. 创建不可变 DocumentVersion（初始 DRAFT）
4. 解析成 Evidence，保存结构化位置
5. 规则抽取优先，LLM 只补充候选 Claim / Relation
6. 规范化单位、枚举、实体别名与 fact_key
7. 写入 Claim、来源扩展表、Claim-Evidence 关联
8. 生成关系候选、冲突与存疑关联
9. 运行完整性检查
10. 将通过审核的版本发布为 PUBLISHED
11. 生成 Qdrant 文本/向量 payload，校验后切换 live alias
```

### 5.2 发布边界

- SQLite 中先写入新版本和新 Claim，校验通过后才切换 active version；
- Qdrant 继续使用当前 `publishLiveAlias`：写入物理 collection、校验点数、原子 alias 切换、保留最近可回滚版本；
- 任何一步失败不得影响已发布版本；
- 查询只读取 `PUBLISHED` 文档版本以及与其匹配的 Qdrant alias；
- 查询路径不得执行全量关系抽取、LLM 审核或写库。现有实现已将关系限制在当前命中页；0.9.3 进一步应把关系生产移至导入/发布任务。

### 5.3 版本与代码联动

每一个混合结论至少带以下边界：

```text
projectId
businessVersion
requirement documentVersionId
parameter documentVersionId
code repositoryId + commitSha
observed testRunId + environment + executedAt
```

示例输出：

```text
规范值：冷却时间 10 秒（需求 v1.8.0，Evidence req-e-12）
配置值：冷却时间 12 秒（数值表 v1.8.0，Evidence param-e-55）
实现值：12 秒（combat-service@abc123，Evidence code-e-8）
测试结果：通过，实际值 12 秒（run-20260823，Evidence result-e-9）
结论：需求与配置/实现冲突，状态 CONFLICTED，需产品确认。
```

代码和测试结果可以证明“当前实现行为”，不能自动修改“需求规定”。冲突必须显式保留。

---

## 6. 检索、关系与冲突规则

### 6.1 查询意图与来源过滤

延续当前 `NORMATIVE / VALIDATION / PARAMETER / DOUBT / CONSISTENCY / IMPACT / GENERAL` 路由：

| 意图 | 默认来源 | 输出重点 |
| --- | --- | --- |
| NORMATIVE | REQUIREMENT、已发布 PARAMETER_TABLE | 规范、约束、Evidence |
| PARAMETER | PARAMETER_TABLE、REQUIREMENT | 值、单位、边界、生效版本 |
| VALIDATION | TEST_CASE、TEST_RESULT、REQUIREMENT | 覆盖与执行状态 |
| DOUBT | DOUBT、REQUIREMENT | 未决问题、责任人、截止日期 |
| CONSISTENCY | 全部来源 | 同 fact_key 的差异、冲突、缺失验证 |
| IMPACT | REQUIREMENT、CODE、TEST_CASE | 影响代码、调用链、回归范围 |

### 6.2 关系生产规则

1. 先按稳定 ID、显式 `coveredRequirementId` 或规范化 `fact_key` 精确匹配；
2. 匹配不到真实目标 Claim 时，保存 `UNRESOLVED` 诊断，不创建伪造目标 ID；
3. 规则命中可产生 `RULE_PROPOSED`，LLM 仅作二次审查；
4. LLM 无法解析、超时或输出非法时 fail-open，但记录审计原因；
5. 发布后查询只读取预先生成的关系，并裁剪到当前命中 Claim 的一跳邻域；
6. `DOUBT` 只建立风险或待确认关系，不提升目标 Claim 的可信度。

### 6.3 冲突规则

冲突按同 `fact_key` 比较，不按自然语言相似度猜测：

```text
REQUIREMENT_PARAMETER
PARAMETER_TEST
TEST_RESULT_EXPECTATION
REQUIREMENT_DOUBT
VERSION_INTERNAL
SOURCE_STALE
MISSING_VALIDATION
REQUIREMENT_CODE
```

建议结论状态：

```text
CONFIRMED             主来源一致，且存在可回查 Evidence
SUPPORTED             有有效证据但未达到确认门槛
PARTIALLY_SUPPORTED   部分字段或来源支持
REVIEW_REQUIRED       信息不足或待人工审核
CONFLICTED            同 fact_key 的有效来源矛盾
NO_EVIDENCE           找到主题但无可发布证据
NO_RESULT             没有匹配结果
```

---

## 7. 实施路径

### Phase A：统一目录与 Evidence（优先）

- 新增 `knowledge_document`、`knowledge_document_version`、`knowledge_evidence`；
- 为现有四类业务表回填 `document_version_id`、`evidence_id`；
- 保持 `evidence_location` 兼容读取，新的写入必须同时写 Evidence；
- 提供 Evidence ID 的稳定生成器与唯一性测试。

**验收**：任一参数、存疑、测试用例或代码 Claim 都能定位到一份资料版本和原始位置。

### Phase B：Claim 主数据和扩展表映射

- 新增 `knowledge_claim`、`knowledge_claim_evidence`；
- 把 `ParameterClaim`、`DoubtClaim`、`TestCaseClaim`、`TestResultClaim` 写入时同步生成 Claim；
- `RequirementGraphCandidateAdapter` 与 `CodeKnowledgeCandidateAdapter` 改为优先读取已持久化 Claim，保留旧投影作为迁移期回退；
- 统一 `fact_key` 生成器，增加别名与单位规范化版本。

**验收**：任一 `UnifiedKnowledgeClaim.claimId` 均可在主库查询其版本、状态和 Evidence。

### Phase C：关系、冲突与审核审计

- 为现有关系补齐 `status/confidence/evidence_id/extraction_method/confirmation_reason`；
- 将关系生产移到导入后异步任务或发布前校验任务；
- 新增 `knowledge_extraction_run`，关联解析器、模型、提示词版本和 token；
- 建立人工审核 API：确认、拒绝、标记过期、处理存疑。

**验收**：关系和冲突均可追溯到双方 Claim、Evidence、抽取运行与审核动作。

### Phase D：发布目录与索引一致性

- 增加 project + businessVersion 的 active document-version manifest；
- Qdrant payload 至少包含 `projectId/version/documentVersionId/sourceType/authority/status/evidenceId/factKey`；
- 只有主库发布成功后才能切换 Qdrant alias；
- 增加失败恢复和旧版本回滚流程。

**验收**：跨版本污染为 0；索引、主库、查询响应三处版本一致。

### Phase E：质量评测与线上观测

- 扩展 `multi-source-golden.jsonl`：来源正确率、Evidence 可回查率、冲突识别率、跨版本隔离；
- 监控导入耗时、抽取失败、未解析关系、审核积压、Token、向量发布结果；
- 以项目灰度开关逐步启用，保留关闭多源检索的回退路径。

**建议指标**：

```text
Evidence 可回查率 >= 99%
PUBLISHED Claim 无 Evidence 数 = 0
跨版本污染率 = 0
关系悬空率 = 0
数值单位规范化率 >= 99%
多源 Golden Evidence Grounded Rate >= 95%
```

---

## 8. 迁移策略

### 8.1 不破坏现有接口

- `MultiSourceSearchResponse`、`UnifiedKnowledgeClaim` 和当前 HTTP API 保持兼容；
- 已有 `multi_source_*` 表继续可读可写；
- 新字段允许先为空，迁移完成后逐步收紧约束；
- 旧 `TEST` 继续通过现有规范化逻辑读为 `TEST_CASE`。

### 8.2 分批回填

```text
第一批：数值表和存疑（结构最稳定）
第二批：测试用例和历史测试结果
第三批：已发布需求图实体/关系
第四批：代码符号及 commit Evidence
第五批：旧多源关系和冲突审计
```

每批迁移应：

1. 生成 DocumentVersion；
2. 生成 Evidence；
3. 生成或关联 Claim；
4. 对比迁移前后的记录数与 hash；
5. 抽样验证可回查链接；
6. 在项目级灰度开关下发布。

### 8.3 禁止事项

- 禁止让 LLM 直接写入 `PUBLISHED` Claim；
- 禁止没有 Evidence 的混合结论进入规范检索；
- 禁止以全文相似度替代 `fact_key` 做事实合并；
- 禁止用代码值静默覆盖需求值；
- 禁止查询请求触发全量抽取、全量 LLM 确认或全量关系写库；
- 禁止删除旧版本事实来“消除冲突”，应以 `STALE/OBSOLETE/REJECTED` 标记和发布 manifest 管理。

---

## 9. 测试清单

### 数据库与迁移

- 同内容重复导入不创建重复 DocumentVersion/Evidence/Claim；
- 内容变化创建新版本，旧版本仍可审计；
- 事务失败时 DocumentVersion、Claim、Relation 与扩展表一致回滚；
- `claim_id/evidence_id/relation_id` 稳定且无跨项目冲突；
- 历史 TEST_RESULT 可按环境和时间查询最近有效结果。

### 证据与事实

- PUBLISHED Claim 必有 Evidence；
- Evidence 的文件、sheet、行列、代码行号和 commit 可回查；
- LLM 生成的未知 Evidence ID 被拒绝；
- 需求图和代码 Adapter 的投影与持久化 Claim 一致。

### 检索与冲突

- NORMATIVE 不返回 OPEN DOUBT 作为确认事实；
- PARAMETER 返回单位、范围、精度和来源位置；
- VALIDATION 同时区分用例预期与实际执行结果；
- 同 fact_key 的需求/参数/代码不一致返回结构化冲突；
- 分页只返回当前页关系，一页之外的关系不泄漏；
- `total/page/limit/hasMore` 与结果集一致。

### 发布与回滚

- SQLite 发布失败不切换 Qdrant alias；
- Qdrant 点数校验失败时旧 alias 可查询；
- alias swap 不支持时 atomic delete/create 仍为单请求；
- 回滚后查询仅返回目标发布版本；
- 流式模型调用只统计一次请求与最终 usage。

---

## 10. 推荐落地顺序

1. **先做 Phase A**：统一 Document、Version、Evidence，是审计和可信回答的基础；
2. **再做 Phase B**：让已有分表与 `UnifiedKnowledgeClaim` 具备统一主数据身份；
3. **然后做 Phase C**：把关系生产从查询路径迁到导入/发布路径；
4. **最后做 Phase D/E**：发布 manifest、索引一致性、评测和灰度运营。

0.9.3 的重点不是再增加一种资料解析器，而是把已有多源能力从“可检索”升级为“可证明、可版本化、可复核、可回滚”的知识主库闭环。
