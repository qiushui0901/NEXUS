# 代码事实基线驱动的跨源知识图谱改进方案

> **目标版本**：0.9.3 后续迭代
> **状态**：开发方案
> **适用范围**：代码图谱、需求语义图、数值表、测试用例、测试结果、需求存疑及其跨源关联
> **核心原则**：以指定代码仓库与 `commitSha` 为“当前实现事实”基线；需求文档保留为意图、背景和漂移检测对象，不能在冲突时静默覆盖代码。

---

## 1. 背景与问题定义

当前系统已经具有三类可复用能力：

1. **代码知识图谱**：代码符号、调用关系、入口点、测试符号以及 `projectId + commitSha` 维度的代码快照已经由 `CodeKnowledgeService`、`SQLiteSymbolGraphStore` 和 `CodeQdrantStore` 支持。
2. **需求语义图**：需求文档已可构建为 `GraphSnapshot`，包含实体、关系、Evidence、冲突与不确定性。
3. **多源结构化知识**：数值表、测试用例、测试结果、需求存疑已保存为来源分表 Claim，并可通过 `MultiSourceSearchService` 进行意图路由、冲突分析与跨源检索。

但这些能力目前仍以“并列的知识子系统”存在：

```text
代码图谱        → 当前实现结构与调用关系
需求语义图      → 文档表达的业务规则与关系
多源 Claim      → 参数、测试、存疑等结构化事实
```

系统尚缺少一个稳定、可审计的对齐层，用于回答：

```text
某条需求规则由当前代码的什么符号实现？
一个数值表参数被哪些代码读取，是否与当前实现一致？
一个测试用例/测试结果验证的是哪个代码行为？
需求文档、测试预期与当前代码发生不一致时，应更新谁？
某个代码提交影响哪些业务概念、需求说明、参数与测试？
```

本方案不是把全部资料粗暴地放入一张图，而是建立一个**代码事实基线驱动的跨源对齐图**。

---

## 2. 已有能力和定位调整

### 2.1 已有实现

| 子系统 | 当前核心对象 | 当前价值 |
| --- | --- | --- |
| 代码知识 | `CodeSymbol`、`CodeRelation`、`CodeChunk` | 精确定位代码符号、调用链和 commit 证据 |
| 需求语义图 | `GraphSnapshot`、`Entity`、`Relation`、`Evidence` | 结构化表达需求文档中的业务实体与规则 |
| 参数表 | `ParameterClaim` | 保留工作簿、sheet、行列、单位、范围、精度与状态 |
| 测试 | `TestCaseClaim`、`TestResultClaim` | 表达测试定义、预期、实际执行结果和环境 |
| 存疑 | `DoubtClaim` | 表达问题、处理状态、负责人、严重级别与候选方案 |
| 多源关系 | `CrossSourceRelation` | 已支持测试验证、参数支持、存疑指向等基本关系 |
| 多源检索 | `UnifiedKnowledgeClaim` | 已支持来源过滤、冲突、分页、解释与 Evidence 返回 |

### 2.2 必须调整的语义

当前 `RequirementGraphCandidateAdapter` 将已验证需求 Claim 映射为 `REQUIREMENT + PRIMARY`，而 `CodeKnowledgeCandidateAdapter` 将代码映射为 `CODE + SECONDARY`。

在“代码更准确”的业务前提下，这一默认排序不再合适。应明确区分：

```text
代码：当前实现事实（系统现在实际做什么）
数值表：配置事实（最终要绑定部署环境/配置版本）
测试结果：运行观测事实（某环境、某时间实际发生了什么）
测试用例：验证意图（应该如何验证）
需求文档：历史业务意图或说明（应与代码对齐，而非自动裁决代码）
需求存疑：待确认问题（绝不作为确认事实）
```

---

## 3. 总体设计

### 3.1 三层模型

```text
┌──────────────────────────────────────────────────────────────┐
│  来源事实层：来源不可混淆、版本不可丢失                       │
│                                                              │
│ CodeSymbol / RequirementClaim / ParameterClaim / TestCase   │
│ TestResult / DoubtClaim / Evidence / CodeSnapshot            │
└─────────────────────────┬────────────────────────────────────┘
                          │ 显式映射、稳定 ID、LLM 辅助
┌─────────────────────────▼────────────────────────────────────┐
│  业务概念层：跨来源对齐锚点                                   │
│                                                              │
│ BusinessConcept / FactKey / Alias / Unit / Domain Vocabulary  │
└─────────────────────────┬────────────────────────────────────┘
                          │ 可审核关系与状态
┌─────────────────────────▼────────────────────────────────────┐
│  跨源证据层：验证、漂移、冲突与影响                           │
│                                                              │
│ IMPLEMENTS / READS_CONFIG / ASSERTS / OBSERVES / ALIGNED      │
│ DOCUMENT_DRIFT / TEST_DRIFT / CONFIG_DRIFT / RAISES_DOUBT     │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 关键约束

1. **来源事实永不合并覆盖**：代码、文档、测试、表格各保留独立 Claim 和 Evidence。
2. **业务概念可共享**：不同来源通过 `BusinessConcept` 或稳定 `factKey` 对齐。
3. **代码快照必须固定**：所有“当前实现”结论必须绑定 `repositoryId + commitSha`。
4. **关系必须可回查**：每条已发布关系至少有双方节点、版本和 Evidence。
5. **LLM 只能提候选关系**：不能创建未知节点、伪造 Evidence 或改变权威等级。
6. **查询只读**：关系抽取、LLM 确认和冲突计算应在导入/发布任务发生，而不是由查询请求触发全量写入。

---

## 4. 统一节点模型

### 4.1 来源节点

| 节点类型 | 主来源 | 说明 |
| --- | --- | --- |
| `CodeSymbol` | 代码图谱 | 类、方法、字段、配置读取点、接口、事件、测试符号 |
| `RequirementClaim` | 需求语义图 | 文档中明确表达的规则、约束、流程或验收条件 |
| `ParameterClaim` | 数值表 | 参数值、范围、单位、精度、生效窗口 |
| `TestCaseClaim` | 测试用例 | 前置条件、步骤、预期结果、覆盖目标 |
| `TestResultClaim` | 测试结果 | 执行状态、真实输出、环境、运行时间 |
| `DoubtClaim` | 需求存疑 | 待决问题、责任人、处理状态、候选方案 |
| `Evidence` | 所有来源 | 文件段落、表格单元格、测试节点、代码行和 commit |
| `VersionContext` | 发布目录 | 业务版本、文档版本、配置版本、代码 commit、环境 |

### 4.2 新增业务概念节点：`BusinessConcept`

`BusinessConcept` 是跨源对齐的锚点，不是原始资料的替代品。

示例：

```text
combat.skill.fireball.cooldown
combat.skill.fireball.damage_formula
payment.order.refund.status
role.level.unlock_condition
```

建议字段：

```sql
create table business_concept (
  concept_id text primary key,
  project_id text not null,
  canonical_key text not null,
  display_name text not null,
  concept_type text not null,
  module text,
  description text,
  status text not null,
  created_at text not null,
  updated_at text not null,
  unique(project_id, canonical_key)
);
```

`canonical_key` 使用可稳定生成的规范化路径，而不是任意自然语言。例如：

```text
<module>.<entity>.<attribute>
combat.fireball.cooldown
combat.damage.critical_multiplier
```

### 4.3 概念别名与词汇表

需求文档、代码和数值表常用不同命名，必须建立显式别名表：

```sql
create table business_concept_alias (
  alias_id text primary key,
  concept_id text not null,
  alias text not null,
  source_type text,
  normalization_method text not null,
  confidence real,
  created_at text not null,
  unique(concept_id, alias, source_type)
);
```

示例：

```text
BusinessConcept：combat.fireball.cooldown
需求别名：火球术冷却时间
数值表别名：Fireball_CD
代码别名：resolveCooldown / fireballCooldownMillis
```

别名必须标记来源；不要让一次 LLM 推断直接变成全局 canonical 名称。

---

## 5. 统一关系模型

### 5.1 代码内部关系：继续使用现有代码图谱

```text
CALLS
READS
WRITES
EMITS
CONSUMES
DEPENDS_ON
IMPLEMENTS_INTERFACE
```

这些关系应保留原有解析精度：

```text
EXACT / SAME_FILE / HEURISTIC / UNRESOLVED
```

不能把启发式解析关系当作确定实现证据。

### 5.2 来源节点到业务概念的映射

```text
CodeSymbol        --IMPLEMENTS-->   BusinessConcept
CodeSymbol        --READS_CONFIG--> ParameterClaim
RequirementClaim  --DOCUMENTS-->    BusinessConcept
ParameterClaim    --CONFIGURES-->   BusinessConcept
TestCaseClaim     --ASSERTS-->      BusinessConcept
TestResultClaim   --OBSERVES-->     BusinessConcept
DoubtClaim        --QUESTIONS-->    BusinessConcept
```

### 5.3 跨来源关系

```text
TestCaseClaim     --VERIFIES-->       CodeSymbol / BusinessConcept
TestResultClaim   --CONFIRMS-->       TestCaseClaim / CodeSymbol
ParameterClaim    --SUPPORTS-->       CodeSymbol / BusinessConcept
RequirementClaim  --ALIGNED_WITH-->   CodeSymbol
RequirementClaim  --DOCUMENT_DRIFT--> CodeSymbol
TestCaseClaim     --TEST_DRIFT-->     CodeSymbol
ParameterClaim    --CONFIG_DRIFT-->   CodeSymbol
DoubtClaim        --RAISES_DOUBT-->   BusinessConcept / Claim
```

### 5.4 关系状态

关系不能只保存类型，必须保存产生与确认状态：

```text
RULE_CONFIRMED      由显式 ID、配置键、注解、稳定名称等规则建立
LLM_CANDIDATE       LLM 产生的候选，未发布
LLM_CONFIRMED       LLM 二次确认，仍需可审计
HUMAN_CONFIRMED     人工确认，可作为高可信发布关系
REJECTED            已明确拒绝
UNRESOLVED          未能定位真实目标
STALE               任一侧版本或 commit 已过期
```

建议扩展现有跨源关系记录：

```sql
alter table multi_source_relation add column status text default 'RULE_CONFIRMED';
alter table multi_source_relation add column confidence real;
alter table multi_source_relation add column evidence_id text;
alter table multi_source_relation add column extraction_method text;
alter table multi_source_relation add column confirmation_method text;
alter table multi_source_relation add column confirmation_reason text;
alter table multi_source_relation add column source_version_context text;
alter table multi_source_relation add column target_version_context text;
```

迁移完成后，可逐步由统一 `knowledge_relation` 承担主数据角色，并保留现有表作为兼容视图或过渡表。

---

## 6. 代码事实基线和裁决规则

### 6.1 增加 `truth_role`

`SourceType` 表达来自哪里，`truthRole` 表达该记录回答什么问题。二者必须分开。

```text
IMPLEMENTATION   当前代码实现的行为
CONFIGURATION    已部署或受控配置的值
OBSERVATION      某环境下测试/运行观测到的行为
INTENT           需求文档表达的预期或历史意图
QUESTION         待确认问题
DERIVED          规则或 LLM 推导的结论
```

建议优先级：

```text
CODE（指定 repository + commit）
> 已部署配置 / 已受控数值表
> TEST_RESULT（指定环境和时间）
> TEST_CASE
> REQUIREMENT
> DOUBT
> LLM 推导
```

### 6.2 冲突不等于谁一定错误

系统应生成结构化漂移结论，而不是自动修改任何来源：

| 场景 | 默认结论 | 建议动作 |
| --- | --- | --- |
| 代码 = 12，文档 = 10，实际测试 = 12 | `DOCUMENT_DRIFT` | 创建文档更新候选，保留人工复核入口 |
| 代码 = 12，参数表 = 10 | `CONFIG_DRIFT` | 检查运行配置、默认值和部署环境 |
| 代码 = 12，测试预期 = 10 | `TEST_DRIFT` | 更新测试或确认代码变更是否缺少验收 |
| 代码未找到映射，文档有规则 | `UNMAPPED` | 建立待映射任务，不宣称已实现 |
| 文档/参数/测试一致，代码不同 | `IMPLEMENTATION_REVIEW_REQUIRED` | 高优先级人工判断是否代码缺陷 |

### 6.3 输出样例

```text
业务概念：combat.fireball.cooldown

当前实现：12 秒
  - CODE / IMPLEMENTATION / commit abc123
  - DamageService.resolveCooldown():120-145

受控参数：12 秒
  - PARAMETER_TABLE / CONFIGURATION
  - skills.xlsx#技能参数!B12:G12

测试观测：12 秒（PASS）
  - TEST_RESULT / OBSERVATION
  - run-20260823 / staging

文档声明：10 秒
  - REQUIREMENT / INTENT
  - requirement.md#3.2

结论：DOCUMENT_DRIFT
建议：更新需求文档和测试预期；若业务意图仍应为 10 秒，则创建代码修复任务。
```

---

## 7. 对齐策略：确定性优先，LLM 兜底

### 7.1 第一层：确定性关系

优先建立下列低风险映射：

```text
代码配置键        ↔ 参数表的参数 key / 列名 / 枚举值
代码常量/枚举      ↔ 参数表的受控值
测试方法          ↔ CodeSymbol（测试符号）
测试用例 ID        ↔ 测试文件中的注解、DisplayName、参数化名称
coveredRequirementId ↔ 需求 Claim / 需求图 Entity ID
需求 ID / 模块名    ↔ 代码模块、接口名、事件名
```

关系必须记录“为什么匹配”：

```text
match_method = CONFIG_KEY_EXACT
match_method = REQUIREMENT_ID_EXACT
match_method = TEST_SYMBOL_EXACT
match_method = ENUM_VALUE_EXACT
```

### 7.2 第二层：规则归一化

针对自然语言差异做受控归一化：

```text
中文/英文术语映射
全角半角、大小写、下划线与驼峰转换
单位统一（ms / s / 秒）
参数名前后缀规范化（_CONFIG、_VALUE、duration）
模块和领域词汇表
```

归一化版本必须可追溯，变更后允许重新构建相关关系。

### 7.3 第三层：LLM 候选关系

仅在确定性与规则归一化未能完成时调用 LLM。输入应是受限的候选集合：

```text
源节点 ID、类型、摘要、Evidence ID
候选目标节点 ID、类型、摘要、Evidence ID
允许的 relationType 列表
版本上下文
```

输出必须是结构化 JSON：

```json
{
  "sourceNodeId": "...",
  "targetNodeId": "...",
  "relationType": "IMPLEMENTS",
  "confidence": 0.84,
  "supportingEvidenceIds": ["..."],
  "reason": "..."
}
```

发布规则：

- LLM 输出的节点/Evidence 不在候选集合中：拒绝；
- `confidence` 不达阈值：保存 `LLM_CANDIDATE`，不进入默认检索；
- 高风险类型（`IMPLEMENTATION_REVIEW_REQUIRED`、`DOCUMENT_DRIFT`）必须人工审核；
- LLM 确认失败或超时不删除规则关系，但必须记录审计信息。

---

## 8. 导入、构图与发布流程

### 8.1 代码基线构建

```text
选择 repositoryId + commitSha
  → 扫描代码、生成 CodeSymbol / CodeRelation
  → 识别配置读取点、常量、枚举、API、事件和测试符号
  → 投影 CODE Claim（truthRole = IMPLEMENTATION）
  → 绑定代码 Evidence（路径、行范围、commit）
  → 发布为当前代码事实基线
```

“当前”不能仅理解为仓库最新分支，必须由业务项目选择固定 commit；若涉及线上行为，还应绑定环境与部署版本。

### 8.2 多源资料导入

```text
需求文档 → 需求图、RequirementClaim、Evidence、truthRole = INTENT
数值表   → ParameterClaim、单位/范围/生效版本、truthRole = CONFIGURATION
测试用例 → TestCaseClaim、步骤/预期、truthRole = INTENT
测试结果 → TestResultClaim、环境/时间/输出、truthRole = OBSERVATION
需求存疑 → DoubtClaim、状态/负责人、truthRole = QUESTION
```

每一个节点都必须至少带：

```text
projectId
businessVersion
sourceType
sourceVersion 或 documentVersionId
evidenceId
status
```

代码额外必须带：

```text
repositoryId
commitSha
filePath
symbolName
startLine / endLine
```

### 8.3 对齐、冲突和发布

```text
导入成功
  → 确定性对齐
  → 规则归一化对齐
  → LLM 候选关系（可选）
  → 计算漂移/冲突
  → 完整性校验
  → 人工审核高风险关系
  → 发布 VersionContext
  → 同步 Qdrant payload 与 live alias
```

查询流程应只做：

```text
根据当前 VersionContext 检索
→ 读取已发布的 Claim、关系与冲突
→ 裁剪到当前命中节点的一跳邻域
→ 返回代码基线、差异与 Evidence
```

---

## 9. 实施阶段

### Phase 1：业务概念与版本上下文（优先）

**目标**：让各来源拥有稳定的共同对齐锚点，且所有结论绑定代码快照。

- 新增 `business_concept`、`business_concept_alias`；
- 新增 `version_context`，保存 `projectId + businessVersion + repositoryId + commitSha + environment`；
- 扩展统一 Claim：增加 `truthRole`、`conceptId`、`versionContextId`；
- 先将 `CodeSymbol`、`ParameterClaim` 映射到概念。

**验收**：给定一个参数或代码符号，可查到其 BusinessConcept 和当前有效 commit。

### Phase 2：代码—参数表关系

**目标**：先完成准确率最高、收益最大的闭环。

- 识别代码中的配置读取、常量、枚举、属性绑定；
- 建立 `READS_CONFIG`、`USES_PARAMETER`、`CONFIG_DRIFT`；
- 对单位、精度、默认值、范围进行结构化比较；
- 关联当前部署环境时，区分“代码默认值”和“实际配置值”。

**验收**：能从一个数值表参数定位读取代码；能从代码改动找到受影响参数。

### Phase 3：代码—测试图谱

**目标**：关联业务测试、自动化测试和代码符号。

- `TestCaseClaim → TestSymbol → CodeSymbol` 三段映射；
- 测试结果按 `runId/environment/executedAt` 记录观测；
- 建立 `VERIFIES`、`CONFIRMS`、`TEST_DRIFT`；
- 代码影响分析自动推荐回归测试与待更新测试用例。

**验收**：给定代码符号可输出关联测试和最近执行结果；给定测试失败可定位相关符号和业务概念。

### Phase 4：需求—代码漂移检测

**目标**：把需求从默认裁决者调整为可审计的意图/漂移来源。

- `RequirementClaim → BusinessConcept`；
- 建立 `ALIGNED_WITH`、`DOCUMENT_DRIFT`、`UNMAPPED`；
- 增加“文档更新候选”和“代码修复候选”人工工作流；
- 不允许自动用任一侧覆盖另一侧。

**验收**：可按项目、版本、模块输出“已对齐/文档过期/未映射/待审核”清单。

### Phase 5：存疑治理与影响分析

**目标**：让存疑成为可管理风险，而不是干扰事实检索的文本。

- `DoubtClaim → BusinessConcept / Claim`；
- 根据受影响代码符号、参数和测试自动补全影响范围；
- OPEN 存疑不进入确认事实，CONSISTENCY/DOUBT 查询可见；
- 解决存疑时必须关联 Resolution Evidence 和人工结论。

**验收**：每一个 OPEN 存疑可定位受影响模块、代码、参数、测试和责任人。

---

## 10. 数据质量与测试要求

### 10.1 强制完整性规则

```text
PUBLISHED 的 CODE Claim 必须有 commitSha 和代码 Evidence
PUBLISHED 的跨源关系必须有双方节点与版本上下文
CodeSymbol 关联必须标记解析精度（EXACT / HEURISTIC 等）
DOUBT 不能作为 NORMATIVE 的确认事实
TEST_RESULT 必须有 runId、环境或执行时间中的可用组合
DOCUMENT_DRIFT 不得覆盖或删除原始需求 Claim
```

### 10.2 测试场景

| 场景 | 预期 |
| --- | --- |
| 代码配置键与数值表精确匹配 | 创建 `READS_CONFIG` / `USES_PARAMETER`，状态 `RULE_CONFIRMED` |
| 代码为 12、参数表为 10 | `CONFIG_DRIFT`，保留双方 Evidence |
| 代码为 12、需求为 10、实际测试为 12 | `DOCUMENT_DRIFT`，代码为当前事实基线 |
| 用例 ID 绑定需求但无代码映射 | `UNMAPPED`，不伪造代码目标 |
| LLM 返回不存在的 Evidence ID | 拒绝该候选关系 |
| 代码 commit 切换 | 旧关系标记 `STALE` 或进入旧 VersionContext，不污染新基线 |
| 多源分页查询 | 只返回当前页 Claim 的一跳关系，`total/page/limit/hasMore` 一致 |
| 关系构建失败 | 不影响已发布查询版本；失败记录可审计 |

### 10.3 评测指标

```text
代码—参数精确映射准确率
代码—测试映射准确率
需求—代码漂移 Precision / Recall
PUBLISHED 关系 Evidence 完整率 = 100%
跨版本 / 跨 commit 污染率 = 0
悬空关系率 = 0
LLM 候选人工采纳率
从代码符号到回归测试的 Recall@K
```

---

## 11. 禁止事项

- 禁止把文档、代码、测试和参数的 Claim 写成没有来源区分的一条“最终事实”；
- 禁止让 LLM 自行提升来源权威等级或生成不存在的 Evidence；
- 禁止用全文相似度直接认定“需求实现于某个代码方法”；
- 禁止用代码值静默覆盖需求值，或用需求值静默覆盖代码值；
- 禁止不绑定 `commitSha` 就称为“当前代码行为”；
- 禁止在在线查询中执行全量关系抽取、全量 LLM 审核或全量持久化；
- 禁止因为发生冲突就删除旧版本文档、测试或参数记录。

---

## 12. 最终目标

完成后，系统的定位将从“多份资料混合检索”演进为：

> **代码事实基线驱动的跨源知识对齐系统。**
>
> 代码图谱回答“当前 commit 实际如何实现”；数值表回答“配置是什么”；测试回答“验证与观测是什么”；需求文档回答“历史意图和文档说明是什么”；存疑回答“哪些问题仍待决”。系统通过业务概念、版本上下文和 Evidence，将它们对齐、发现漂移并生成可审核的行动项。

最终查询结果不只回答一个数值或一句话，而应能回答：

```text
现在实际行为是什么？
代码证据在哪里？
配置、测试和文档是否一致？
若不一致，应更新文档、测试、配置还是代码？
影响哪些模块、接口和回归测试？
```
