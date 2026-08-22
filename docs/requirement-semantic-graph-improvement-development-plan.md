# 需求文档语义图系统改进开发方案

**状态：** Proposed
**版本：** v1.0
**编制日期：** 2026-08-21
**适用范围：** 需求文档导入、LLM 实体/关系抽取、证据治理、语义图快照、混合检索、构建任务和质量评估
**主要目标：** 将当前“可运行的语义图链路”提升为“可验证、可恢复、可审计、可稳定检索的生产能力”

---

## 1. 背景与现状

当前系统已经形成以下主链路：

```text
需求文档
  -> 结构化分块
  -> LLM 实体/关系抽取
  -> Evidence 生成
  -> SQLite 语义图快照
  -> Qdrant / 图谱混合检索
  -> 需求问答与开发方案生成
```

当前实现已经具备以下基础能力：

- 需求图快照和构建任务模型；
- 按窗口进行增量构建和失败恢复；
- 实体、关系、证据、不确定性和冲突记录；
- 关系类型白名单和 JSON 结构校验；
- 原文连续证据 quote 校验；
- LOCAL、GLOBAL、NAIVE、MIX 等检索路径；
- Evidence-first 的审查和发布基础；
- 构建、检索和质量相关测试。

但是，当前系统仍存在从原型走向生产时必须解决的问题：

1. 快照身份、构建任务身份和发布状态边界不够清晰；
2. 已发布快照存在被恢复构建覆盖的风险；
3. Evidence 在 span、父块和兼容兜底之间存在粒度不一致；
4. MIX 检索可能出现二次分页、跨页排序不稳定和证据缺失；
5. LLM 置信度与原文证据支持度没有完全分离；
6. 关系的条件、否定、时序和版本范围仍需要进一步结构化；
7. 重试、恢复和预算统计未完全按照真实模型调用计量；
8. SQLite 在线迁移和关系约束还需要增强；
9. 缺少稳定的人工标注集和离线评估闭环。

本方案不建议立即重做整体架构，而是围绕数据正确性、抽取质量、检索稳定性和运行可靠性进行分阶段改进。

---

## 2. 总体目标

### 2.1 产品目标

1. 每条正式入图的实体和关系都能回溯到原文证据。
2. 不同需求版本之间严格隔离，不发生跨版本数据污染。
3. 已发布快照不可被构建任务修改。
4. 关系能够表达条件、否定、时序和有效版本。
5. 检索排序不受分页参数影响，Evidence 能稳定回查。
6. 构建任务支持幂等、重试、暂停、取消和恢复。
7. 不确定关系和冲突关系不会被误当成已确认事实。
8. 通过离线评估持续衡量抽取质量和检索质量。

### 2.2 非目标

本阶段暂不做以下事情：

- 不立即将 SQLite 替换为图数据库；
- 不一次性增加大量关系类型；
- 不让 LLM 自由设计实体和关系 Schema；
- 不用图谱完全替代现有文本/向量检索；
- 不把未经证据校验的模型推理直接作为正式事实；
- 不在没有评估集的情况下直接调整大量融合权重。

---

## 3. 核心设计原则

### 3.1 证据优先

关系必须由原文直接支持。LLM 只返回原文 quote，Evidence ID 由服务端生成。

```text
LLM 输出 quote
  -> 服务端定位原文 span
  -> 服务端生成稳定 Evidence ID
  -> 关系引用 Evidence ID
```

### 3.2 封闭关系集合

关系类型必须来自预定义枚举，禁止模型自由生成关系名称。关系类型按照以下类别管理：

| 类别 | 示例 |
|---|---|
| 结构 | `CONTAINS`、`PART_OF`、`SUBMODULE_OF` |
| 行为 | `TRIGGERS`、`PRECEDES`、`PRODUCES`、`CONSUMES` |
| 约束 | `REQUIRES`、`PROHIBITS`、`LIMITS`、`ALLOWS` |
| 影响 | `AFFECTS`、`UPDATES`、`INVALIDATES` |
| 追溯 | `REFINES`、`DERIVED_FROM`、`CONFLICTS_WITH` |

`RELATED_TO`、`SIMILAR_TO`、`POSSIBLY_DEPENDS_ON` 等过于宽泛的关系默认不允许入正式图谱。

### 3.3 显式事实与推理事实分层

所有 Claim 必须标记来源类型：

```text
EXPLICIT    原文明确表达
DERIVED     根据多条显式关系推导
INFERRED    模型推断或跨段推理
```

默认检索优先级：

```text
EXPLICIT > VERIFIED_DERIVED > INFERRED > UNCERTAIN
```

### 3.4 快照不可变

构建、审核和发布必须采用不可变快照模型：

```text
BUILDING -> REVIEW_REQUIRED -> VERIFIED -> PUBLISHED
```

已发布快照只读。新的需求构建必须创建新的快照，不允许复用已发布快照的 ID 进行覆盖。

### 3.5 先全量排序，再分页

文本召回、向量召回、图谱召回和路径召回必须先完成归一化和融合，最后统一分页，不能对不同通道分别分页后再融合。

---

## 4. 目标架构

```text
                    +----------------------+
                    |  Requirement Source  |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    | Structure-aware      |
                    | Chunk / Window       |
                    +----------+-----------+
                               |
                +--------------+--------------+
                |                             |
                v                             v
      +--------------------+        +--------------------+
      | Entity Extraction  |        | Evidence Locator   |
      | closed schema      |        | quote -> span      |
      +---------+----------+        +---------+----------+
                |                             |
                v                             v
      +--------------------+        +--------------------+
      | Entity Resolution  |        | Evidence Registry  |
      | normalize / merge  |        | stable evidence ID |
      +---------+----------+        +---------+----------+
                |                             |
                +--------------+--------------+
                               v
                    +----------------------+
                    | Relation Extraction  |
                    | condition/negation   |
                    | temporal/version     |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    | Claim Quality Gate   |
                    | schema/evidence/     |
                    | direction/conflict  |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    | Immutable Snapshot   |
                    | entities/relations/  |
                    | evidence/conflicts  |
                    +----------+-----------+
                               |
                +--------------+--------------+
                |                             |
                v                             v
      +--------------------+        +--------------------+
      | Text / Vector Index|        | Graph Index        |
      +---------+----------+        +---------+----------+
                |                             |
                +--------------+--------------+
                               v
                    +----------------------+
                    | Stable Hybrid Search |
                    | fuse -> sort -> page |
                    +----------------------+
```

---

## 5. 分阶段开发计划

## Phase 0：数据正确性和版本安全

**优先级：P0**

**目标：** 先消除可能导致数据错误或线上结果不可信的问题。

### 5.1 快照和构建任务解耦

涉及模块：

- `src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphBuildService.java`
- `src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphBuildJobService.java`
- `src/main/java/com/example/requirementrag/requirement/graph/SQLiteRequirementGraphStore.java`

设计：

```text
snapshotId：输入内容和抽取配置的内容身份
buildId：一次具体构建任务身份
```

建议快照身份包含：

```text
projectId
 documentId
requirementVersion
sourceRevision
schemaVersion
otologyVersion
promptVersion
model
```

构建任务身份使用随机 ID，不参与内容快照的业务唯一约束。

验收标准：

- 同一输入重复构建不会产生脏数据；
- 不同 buildId 可以独立记录；
- 构建失败后可以基于同一 snapshot 恢复；
- 旧快照不会被新构建隐式覆盖；
- 相同输入可以安全幂等复用。

### 5.2 禁止修改已发布快照

新增服务端状态校验：

```text
resumeSnapshotId == PUBLISHED -> 拒绝恢复
resumeSnapshotId == VERIFIED -> 创建新构建快照
resumeSnapshotId == BUILDING/PARTIAL_FAILED -> 允许恢复
```

发布后的实体、关系、Evidence 和审计信息都必须只读。

验收标准：

- 传入已发布 snapshot ID 时返回明确错误码；
- 已发布快照状态不会被改为 `BUILDING`；
- 已发布快照的图数据不会被删除或重写；
- 新构建产生新的 snapshot/build 记录。

### 5.3 Evidence ID 统一为 span 级

涉及模块：

- `RequirementGraphBuildService`
- `RequirementGraphExtractionService`
- `RequirementGraphHybridSearchService`
- `SQLiteRequirementGraphStore`

统一 Evidence 主键生成规则：

```text
hash(snapshotId, parentId, startOffset, endOffset, quoteHash)
```

禁止检索层根据父块自行生成伪 Evidence ID。旧数据可以保留兼容读取，但新构建不得继续写入 `LEGACY_PARENT_ONLY` 证据。

验收标准：

- 关系引用的 Evidence 均可通过 snapshot 查回；
- 检索返回的 Evidence 与关系引用使用同一 ID；
- 无需通过空字段兜底创建 Evidence；
- Evidence 能准确返回原文起止位置和 quote。

### 5.4 修复 MIX 检索分页

涉及模块：

- `RequirementGraphHybridSearchService`
- `RequirementGraphSearchService`

统一流程：

```text
召回所有候选
  -> Evidence / Entity / Relation 归一化
  -> 统一打分
  -> 全量稳定排序
  -> 最终分页
```

验收标准：

- 第 2 页不因二次 `offset` 导致 Evidence 为空；
- 不同页之间不重复；
- 同一个查询不同 page 不改变已有结果的相对顺序；
- `total`、`truncated` 和分页结果一致。

---

## Phase 1：LLM 抽取质量改进

**优先级：P0/P1**

**目标：** 提高关系的准确率、证据完整性和业务语义表达能力。

### 5.5 采用实体优先的两阶段抽取

将当前一次性抽取调整为：

```text
窗口 -> 实体抽取 -> 实体归一化 -> 关系抽取 -> 服务端校验
```

关系只能连接当前窗口中已存在的实体。跨窗口关系由后续实体归一化和关系合并阶段处理。

### 5.6 扩展关系模型

关系模型建议增加：

```text
sourceEntityId
targetEntityId
type
statement
condition
scenario
negated
temporalScope
versionScope
sourceEvidenceIds
sourceType
modelConfidence
evidenceSupport
claimStatus
reviewReason
```

其中：

- `condition`：成立条件；
- `scenario`：适用场景；
- `negated`：否定或禁止表达；
- `temporalScope`：先后或时间范围；
- `versionScope`：适用需求版本；
- `sourceType`：显式、推导或推理；
- `evidenceSupport`：服务端证据校验结果；
- `claimStatus`：审核和发布状态。

### 5.7 增加关系证据质量门禁

服务端至少检查：

1. `sourceLocalId` 和 `targetLocalId` 均存在；
2. 关系类型属于白名单；
3. quote 是原文连续子串；
4. quote 同时覆盖关系两端或其明确指代；
5. quote 包含支持该关系的谓词语义；
6. 关系方向符合类型定义；
7. 关系不是无意义自环；
8. 关系不是重复关系；
9. 条件、否定词和时间词没有被截断；
10. 证据不足时自动降级为 `REVIEW_REQUIRED`。

### 5.8 增加不确定性和冲突处理

关系状态建议采用：

```text
SUPPORTED
UNCERTAIN
INFERRED
CONFLICTED
REVIEW_REQUIRED
REJECTED
```

以下表达不能直接进入已确认关系：

```text
可能
建议
考虑
预计
原则上
视情况而定
```

需要保存原文中的不确定性说明，并允许审核人员确认、拒绝或修改关系。

---

## Phase 2：图谱存储和迁移治理

**优先级：P1**

### 5.9 数据表约束

建议确保以下表均具备 snapshot 作用域：

```text
requirement_graph_snapshot
requirement_graph_window
requirement_graph_window_result
requirement_graph_entity
requirement_graph_relation
requirement_graph_evidence
requirement_graph_claim_evidence
requirement_graph_uncertainty
requirement_graph_conflict
```

建议业务唯一键：

```text
window: snapshot_id + window_id
window_result: snapshot_id + window_id
evidence: snapshot_id + evidence_id
entity: snapshot_id + canonical_key
relation: snapshot_id + source_entity_id + type + target_entity_id + condition_hash
```

Claim–Evidence 关联表必须同时约束：

```text
snapshot_id
claim_id
evidence_id
```

并避免出现跨 snapshot 的 Evidence 关联。

### 5.10 数据库迁移版本化

使用 SQLite `PRAGMA user_version` 管理迁移：

```text
V1：初始语义图表
V2：增加窗口和 Evidence 复合主键
V3：增加 Claim 状态和审核字段
V4：增加条件、场景、版本范围
V5：统一 span Evidence
```

每次迁移必须：

- 在事务中执行；
- 支持重复执行；
- 迁移完成后验证表结构和索引；
- 失败时保留可恢复状态；
- 对旧数据提供兼容策略。

---

## Phase 3：混合检索改进

**优先级：P1**

### 5.11 统一召回和融合模型

候选来源：

```text
文本关键词
向量相似度
实体命中
关系命中
图路径命中
Evidence 质量
```

统一候选结构：

```text
candidateId
candidateType
snapshotId
textScore
vectorScore
graphScore
pathScore
evidenceScore
finalScore
matchedChannels
explanation
```

第一阶段采用可解释加权：

```text
finalScore =
    0.35 * vectorScore
  + 0.25 * textScore
  + 0.25 * graphScore
  + 0.15 * evidenceScore
```

权重必须配置化，并通过离线评估调整。

### 5.12 结果稳定性

相同查询必须具备稳定排序：

```text
finalScore DESC
candidateType ASC
candidateId ASC
```

不能让数据库返回顺序或当前 page 影响最终排名。

### 5.13 增加检索解释

每个结果返回：

```text
命中的通道
命中的实体或关系
命中的 Evidence
命中的路径
分数明细
```

示例：

```json
{
  "candidateId": "relation-123",
  "matchedChannels": ["TEXT", "GRAPH", "EVIDENCE"],
  "explanation": "通过“提交订单 -> TRIGGERS -> 库存校验”路径命中",
  "evidenceIds": ["ev-123"],
  "scoreBreakdown": {
    "text": 0.72,
    "vector": 0.81,
    "graph": 0.93,
    "evidence": 1.0,
    "final": 0.86
  }
}
```

---

## Phase 4：构建任务和运行可靠性

**优先级：P1**

### 5.14 按真实模型调用统计预算

构建任务持久化以下数据：

```text
estimatedInputTokens
estimatedOutputTokens
actualInputTokens
actualOutputTokens
modelCallCount
retryCount
budgetLimit
budgetUsed
```

每次模型调用前检查预算，每次调用结束后更新实际用量。重试和恢复都必须纳入总预算。

### 5.15 构建任务幂等和恢复

要求：

- 相同 `buildId` 重复执行不会重复写入；
- 已完成窗口不会重复调用模型；
- 重试不会重复创建 Evidence；
- 取消操作先持久化，再终止执行；
- 进程重启后能恢复到准确窗口状态；
- 已发布快照不参与恢复写入。

### 5.16 可观测性

增加以下指标：

```text
requirement_graph_build_total
requirement_graph_build_failed_total
requirement_graph_build_duration
requirement_graph_llm_call_total
requirement_graph_llm_retry_total
requirement_graph_token_used
requirement_graph_evidence_invalid_total
requirement_graph_claim_review_required_total
requirement_graph_search_latency
requirement_graph_search_empty_total
requirement_graph_search_degraded_total
```

日志中至少携带：

```text
projectId
documentId
requirementVersion
snapshotId
buildId
windowId
model
promptVersion
schemaVersion
```

---

## Phase 5：评估和灰度发布

**优先级：P1/P2**

### 5.17 建立 Golden Dataset

准备 50～100 份人工标注样本，覆盖：

- 明确实体和关系；
- 同义实体；
- 跨段关系；
- 条件关系；
- 否定关系；
- 时序关系；
- 不确定关系；
- 冲突版本；
- 不应抽取的共现关系；
- 证据边界和长文本场景。

### 5.18 评估指标

抽取指标：

```text
Entity Precision / Recall / F1
Relation Precision / Recall / F1
Relation Direction Accuracy
Evidence Recall
Evidence Re-check Success Rate
Condition Accuracy
Negation Accuracy
Conflict Detection Accuracy
```

检索指标：

```text
Recall@K
MRR
nDCG@K
Evidence Hit Rate
Path Hit Rate
Answer Groundedness
P50 / P95 Latency
```

正式入图优先关注：

```text
Relation Precision
Evidence Re-check Success Rate
Published Unsupported Claim Rate = 0
```

### 5.19 灰度策略

```text
阶段 1：只构建，不影响线上检索
阶段 2：只在调试接口返回语义图结果
阶段 3：MIX 检索按项目白名单启用
阶段 4：对比文本基线和语义图结果
阶段 5：逐步扩大启用范围
```

任何阶段都保留旧文本/向量检索作为降级路径。

---

## 6. 重点文件改造范围

| 文件 | 主要改造内容 |
|---|---|
| `RequirementGraphExtractionService.java` | 抽取 Schema、证据校验、关系语义校验、状态归类 |
| `RequirementGraphBuildService.java` | 快照不可变、恢复规则、预算统计、Evidence 生成 |
| `RequirementGraphBuildJobService.java` | 幂等、取消、恢复、失败状态和任务审计 |
| `SQLiteRequirementGraphStore.java` | 复合主键、外键、事务迁移、快照唯一约束 |
| `RequirementGraphHybridSearchService.java` | 全量融合、稳定排序、统一 Evidence、单次分页 |
| `RequirementGraphSearchService.java` | 版本过滤、状态过滤、证据回查和解释信息 |
| `RequirementGraphModels.java` | Claim 状态、条件、否定、版本范围、Evidence span 字段 |
| `RequirementGraphController.java` | 审核、发布、回滚、构建状态和检索解释 API |
| `src/test/.../requirement/graph` | 单元测试、集成测试、属性测试和回归测试 |
| `evaluation/` | Golden Dataset、抽取评估和检索评估 |

---

## 7. 测试方案

### 7.1 单元测试

覆盖：

- 快照 ID 和 build ID 生成；
- 已发布快照恢复拦截；
- Evidence span 定位；
- quote 不存在时拒绝；
- 关系方向和类型校验；
- 条件和否定词处理；
- 关系去重；
- 版本冲突检测；
- 混合检索排序和分页；
- token 预算和重试计数。

### 7.2 集成测试

覆盖：

- 同一文档多版本隔离；
- 同一内容重复构建幂等；
- 构建中断后恢复；
- 构建取消后状态一致；
- 发布后不可覆盖；
- Evidence 从抽取到检索完整回查；
- SQLite 重启后数据完整；
- Qdrant 不可用时降级到图谱/文本路径。

### 7.3 属性测试

建议加入以下不变量：

```text
PUBLISHED snapshot 的所有图数据不可变
任意 ClaimEvidence 不能引用其他 snapshot 的 Evidence
同一 snapshot 中相同业务键不能存在两条实体/关系
任意返回 Evidence 都必须能回查原文
分页不会改变全局排序
失败重试不会增加已完成窗口数量
```

---

## 8. 交付验收标准

### 数据正确性

- [ ] 已发布快照不可被构建任务修改；
- [ ] 多项目、多文档、多版本数据严格隔离；
- [ ] 同一输入重复构建结果幂等；
- [ ] Evidence ID 统一为 span 级；
- [ ] Claim–Evidence 不存在跨快照关联；
- [ ] 数据库迁移支持失败恢复。

### LLM 抽取质量

- [ ] 关系类型全部来自白名单；
- [ ] 每条关系都有可回查原文证据；
- [ ] 关系方向和证据语义通过服务端校验；
- [ ] 条件、否定、时序和版本信息不会被静默丢失；
- [ ] 不确定关系不会直接进入已确认事实集合；
- [ ] 关系去重和冲突标记有效。

### 检索质量

- [ ] MIX 查询先融合、后分页；
- [ ] 跨页不重复、不漏 Evidence；
- [ ] 相同查询排序稳定；
- [ ] 检索结果能够解释命中原因；
- [ ] Qdrant 或图谱任一路径失败时能够降级；
- [ ] 语义图结果不降低原有文本检索基线。

### 运行可靠性

- [ ] 模型重试计入真实调用次数和预算；
- [ ] 构建任务可恢复、可取消、可重入；
- [ ] 失败窗口和失败原因可追踪；
- [ ] 指标和日志包含完整构建上下文；
- [ ] 具备离线评估和灰度开关。

---

## 9. 推荐实施顺序

建议实际开发顺序如下：

```text
第 1 步：快照不可变和 build/snapshot 解耦
第 2 步：统一 span Evidence ID
第 3 步：修复 MIX 全量排序和分页
第 4 步：增加关系证据语义校验
第 5 步：补充条件、否定、时序和版本范围
第 6 步：完善 Claim 状态、冲突和审核流
第 7 步：改造预算、重试和恢复统计
第 8 步：SQLite 迁移版本化和约束补齐
第 9 步：建立 Golden Dataset 和离线评估
第 10 步：按项目灰度启用语义图检索
```

不建议在完成前四步之前继续增加更多关系类型或复杂推理能力。当前最重要的不是让图谱“更大”，而是让图谱中的每一条关系都能够被可靠地解释和验证。

---

## 10. 最终架构判断

系统最终应该形成以下职责边界：

```text
LLM：识别候选实体、关系和原文 quote
服务端：校验 Schema、证据、方向、条件和状态
数据库：保证 snapshot 隔离、事务一致性和不可变发布
检索层：统一召回、融合、排序和分页
审核层：处理不确定关系、冲突关系和人工修订
评估层：持续衡量抽取质量、检索质量和线上稳定性
```

最终目标不是构建一个“看起来很丰富”的关系图，而是构建一个：

> **每条关系都有来源、每个版本都可复现、每次构建都可恢复、每个检索结果都能解释的需求知识系统。**
