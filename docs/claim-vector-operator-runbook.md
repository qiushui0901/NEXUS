# Claim 向量投影运维手册

> **版本**：0.9.6  
> **适用组件**：`knowledge/multisource/vector/`  
> **前置条件**：Qdrant 已部署且 `app.rag.multi-source.claim-vector.enabled=true`

---

## 1. 架构概览

```
SQLite（权威）                  Qdrant（可弃）
┌─────────────────────┐         ┌─────────────────────┐
│ generation manifest  │         │ physical collection  │
│ generation input     │ ──构建→ │   (points + vectors) │
│ (active/retired)    │         │   alias → physical    │
└─────────────────────┘         └─────────────────────┘
         ↑                              ↑
   ClaimVectorQualityGate       ClaimVectorShadowEvaluator
   (健康检查)                    (对比指标)
```

**关键原则**：SQLite 是权威存储，Qdrant 可随时丢弃重建。所有治理字段（status/verifiedAt 等）不进 Qdrant payload，命中后从 SQLite 重新读取。

---

## 2. 配置项

`application.yml` → `app.rag.multi-source.claim-vector.*`

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enabled` | `false` | 总开关。关闭时所有 Claim 向量组件不注册 |
| `build-enabled` | `false` | 构建服务开关。关闭时不接受构建请求 |
| `candidate-retrieval-enabled` | `false` | 候选检索开关。关闭时 `ClaimVectorCandidateAdapter` 不注册 |
| `shadow-query-enabled` | `false` | 影子模式开关。关闭时不记录对比指标 |
| `alias` | `knowledge_claims_live` | 基础 alias 名。实际 live alias 按 project+version 派生（形如 `knowledge_claims_live-<project>-<version>`，见 §3），物理 collection 同样按 scope 隔离——不同项目/版本互不干扰（Review 2） |
| `projection-schema-version` | `knowledge-claim-vector-v2` | 投影 schema 版本 |
| `text-composer-version` | `knowledge-claim-text-v2` | 可独立回答事实文本组合器版本 |
| `semantic-enhancement-enabled` | `true` | 是否调用 LLM 生成召回辅助表达；只影响向量召回文本，不改变 SQLite 权威 Claim/Evidence |
| `semantic-enhancement-model` | `gpt-5.6-luna` | 语义增强 Chat 模型 |
| `candidate-limit` | `200` | 单次检索候选上限 |
| `over-fetch-factor` | `3` | 过度检索因子（candidate-limit × 此值） |
| `batch-size` | `32` | Qdrant 批量写入大小 |
| `representative-evidence-limit` | `3` | 每个候选返回的代表证据数 |
| `retain-physical-collections` | `2` | 保留的物理 collection 数（含活跃） |
| `database-path` | `data/multi-source-knowledge.db` | SQLite 数据库路径（与 MultiSourceKnowledgeStore 共用） |

---

## 3. 构建流程

### 3.1 首次构建

> 高（Review 10）：build/status/quality-gate/rollback/rollback-to 端点由 `ClaimVectorAdminController` 提供，
> 仅当 `app.rag.multi-source.claim-vector.enabled=true` 时装配。
> 高（Review 2）：每个 project+version 使用独立 live alias 与物理 collection 前缀，
> 例如 `knowledge_claims_live-immortal-5-1`——跨 scope 不共享、不误删。

```bash
# 1. 确认配置已启用并存在活跃代际
curl -X GET "http://localhost:8080/api/knowledge/multi-source/claim-vector/status?projectId=immortal&businessVersion=5.1"

# 2. 触发构建（返回 ACTIVE 代际 manifest）
curl -X POST http://localhost:8080/api/knowledge/multi-source/claim-vector/build \
  -H "Content-Type: application/json" \
  -d '{"projectId": "immortal", "businessVersion": "5.1"}'

# 3. 质量门检查
curl -X GET "http://localhost:8080/api/knowledge/multi-source/claim-vector/quality-gate?projectId=immortal&businessVersion=5.1"
```

构建流水线：
1. 从 `MultiSourceKnowledgeStore` 加载所有活跃 Claims
2. `KnowledgeClaimVectorTextComposer` 按来源类型生成确定性嵌入文本
3. 计算输入指纹（排序无关）
4. 检查 `findReusableGeneration`——如果同一指纹的 SUCCESS/ACTIVE 代际已存在，跳过重建
5. `recordBuildStart(BUILDING)` ——写入 manifest + input 集合
6. `EmbeddingBatcher.embedAll` ——批量嵌入（batch=8，缓存+二分降级）
7. `publishPhysicalCollection` ——写入 Qdrant 物理 collection
8. `verifyPhysicalCount` ——校验点数
9. `updateStatus(VERIFYING → SUCCESS)`
10. `markActive` ——SQLite 激活新代际 + 退役旧代际
11. `switchAlias` ——Qdrant alias 切到新物理 collection + 清理旧 collection

### 3.2 增量重建

当 Claims 变化时（新增/修改/删除），输入指纹会变化，触发自动重建。`findReusableGeneration` 确保相同输入不会重复构建。

### 3.3 失败处理

| 失败点 | 行为 | 恢复 |
|--------|------|------|
| 嵌入失败 | `updateStatus(FAILED)` + warnings，alias 不变 | 修复嵌入模型后重新构建 |
| Qdrant 写入失败 | `updateStatus(FAILED)` + warnings，alias 不变（半成品物理 collection 由下次 retire 清理） | 检查 Qdrant 连接后重新构建 |
| alias 切换失败 | 高（Review 6）：代际标记 `FAILED`（ALIAS_SWITCH_FAILED）且保持**非 ACTIVE**，旧 ACTIVE 与旧 alias 不变，构建抛异常——不再返回虚假成功。SQLite 与 Qdrant 均未提交新代际 | 确认 Qdrant 健康后重新构建（同指纹会命中可复用项跳过嵌入） |
| markActive 失败 | 极为罕见（同一 SQLite 连接此前已成功写入）；抛异常，Qdrant alias 可能已指向新物理 collection，需人工核对 | 检查 SQLite 状态后手动清理 |
| 无 eligible Claim | 抛 `IllegalStateException` | 检查数据源是否有 Claims |

---

## 4. 回滚流程

### 4.1 回滚到上一代际

```java
// 通过 BuildService.rollback
buildService.rollback("immortal", "5.1");
// → listRetiredForRollback → 取物理集合仍在 Qdrant 的最近 RETIRED（被 retain 清理的跳过）
//   → rollbackTo (RETIRED→ACTIVE) → rollbackAlias (Qdrant 切回)
```

> 中（第七批 Review 4）：回滚目标必须是**物理集合仍在 Qdrant** 的 RETIRED 代际——
> 被 `retain-physical-collections` 窗口清理过的旧集合已删除，不可作为回滚目标（跳过并继续向前找更旧的候选）。

### 4.2 回滚到指定代际

```bash
# 通过 REST（高：Review 10——端点已实现）
curl -X POST http://localhost:8080/api/knowledge/multi-source/claim-vector/rollback-to \
  -H "Content-Type: application/json" \
  -d '{"projectId": "immortal", "businessVersion": "5.1", "generationId": "cv-xxx"}'

# 等价：通过 BuildService
buildService.rollbackTo("immortal", "5.1", "cv-xxx");
```

> 中（第七批 Review 4）：目标代际的物理集合已被 retain 清理（或不存在）时，**明确拒绝回滚并报错**，
> 绝不把 alias 指向已删除的集合（悬空 alias = 向量召回静默归零）。

### 4.3 回滚后验证

```bash
# 质量门检查
curl -X GET http://localhost:8080/api/knowledge/multi-source/claim-vector/quality-gate?projectId=immortal&businessVersion=5.1
```

---

## 5. 质量门

`ClaimVectorQualityGate.check(projectId, businessVersion)` 执行 5 项检查：

| 检查项 | 名称 | 通过条件 |
|--------|------|----------|
| 活跃代际 | `ACTIVE_GENERATION` | SQLite 有 ACTIVE 代际 |
| 点数完整性 | `POINT_COUNT` | `indexedPointCount == expectedPointCount` |
| alias 健康 | `ALIAS_HEALTH` | alias 指向活跃物理 collection |
| 物理一致性 | `PHYSICAL_CONSISTENCY` | 物理 collection 点数 == SQLite manifest |
| 影子数据 | `SHADOW_DATA` | ≥20 条影子查询（仅 shadow-query-enabled 时） |

全部通过时 `readyToPublish=true`。

---

## 6. 影子模式

当 `shadow-query-enabled=true` 时，每次检索自动记录对比指标：

- 向量候选命中数 vs 结构化候选命中数
- 重合率（overlap rate）
- 向量新增召回率（vector recall contribution rate）
- 响应时间

`publishIfReady(projectId, version)` 判断是否可正式发布：
- ≥20 条影子查询
- 向量新增召回的查询比例 ≥ 30%

---

## 7. 灾难恢复

### Qdrant 数据丢失

**已知局限**：当前版本**没有全自动重建路径**。SQLite manifest 未动、输入指纹不变时，
`findReusableGeneration`（只检查 SQLite `physical_collection is not null`，不检查 Qdrant 物理集合是否存在）
会直接复用旧 ACTIVE 代际，`build()` 返回旧 manifest **不会重建**；`rollback()` 只回滚物理集合仍在的
RETIRED 代际，Qdrant 全丢时同样无目标。**质量门能检出**（`ALIAS_HEALTH` / `PHYSICAL_CONSISTENCY` 失败），
但修复需人工介入。

```bash
# 1. 确认故障：质量门 ALIAS_HEALTH / PHYSICAL_CONSISTENCY 失败
curl -X GET "http://localhost:8080/api/knowledge/multi-source/claim-vector/quality-gate?projectId=immortal&businessVersion=5.1"

# 2. 恢复手段（二选一）：
#    a. 让输入指纹变化后重建——编辑任一 claim / 重导数据（updatedAt 变化 → 新指纹 → 全新构建 → markActive → alias 切换）
#    b. 人工清理 SQLite 代际行（备份后删除该 scope 的 generation / generation_input 记录），再全量构建
#       （注意：无 API 可删除 ACTIVE 代际——deleteSupersededGenerations 只清理 status != 'ACTIVE' 的行）

# 3. 重建完成后核对
curl -X GET "http://localhost:8080/api/knowledge/multi-source/claim-vector/status?projectId=immortal&businessVersion=5.1"
curl -X GET "http://localhost:8080/api/knowledge/multi-source/claim-vector/quality-gate?projectId=immortal&businessVersion=5.1"
```

> 建议把「Qdrant 全量丢失后的重建」纳入发布前演练清单（见发布决策记录 §3 第 6 项），并按上述手段确认恢复可行。

### SQLite 数据丢失

SQLite 是权威存储。如果丢失：
1. 从备份恢复 `data/multi-source-knowledge.db`
2. 如果无备份，需要重建所有代际（Qdrant 数据无法反向生成 manifest）

---

## 8. 监控指标

| 指标 | 来源 | 告警阈值 |
|------|------|----------|
| ACTIVE 代际存在 | `findActiveGeneration` | 不存在 = 严重 |
| 点数一致 | `QualityGate` | indexed != expected = 警告 |
| alias 指向正确 | `QualityGate` | 不匹配 = 严重 |
| 影子新增召回率 | `ShadowEvaluator` | < 10% = 提示（向量召回效果不佳） |
| 构建失败率 | `BuildService` | > 5% = 警告 |
| 检索响应时间 | `ShadowEvaluator` | P99 > 500ms = 警告 |
