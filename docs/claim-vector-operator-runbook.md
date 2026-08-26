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
| `alias` | `knowledge_claims_live` | Qdrant alias 名称 |
| `projection-schema-version` | `knowledge-claim-vector-v1` | 投影 schema 版本 |
| `text-composer-version` | `knowledge-claim-text-v1` | 文本组合器版本 |
| `candidate-limit` | `200` | 单次检索候选上限 |
| `over-fetch-factor` | `3` | 过度检索因子（candidate-limit × 此值） |
| `batch-size` | `32` | Qdrant 批量写入大小 |
| `representative-evidence-limit` | `3` | 每个候选返回的代表证据数 |
| `retain-physical-collections` | `2` | 保留的物理 collection 数（含活跃） |
| `database-path` | `data/multi-source-knowledge.db` | SQLite 数据库路径（与 MultiSourceKnowledgeStore 共用） |

---

## 3. 构建流程

### 3.1 首次构建

```bash
# 1. 确认配置已启用
curl -X GET http://localhost:8080/api/knowledge/multi-source/claim-vector/status

# 2. 触发构建（通过 REST 或直接调用 BuildService）
curl -X POST http://localhost:8080/api/knowledge/multi-source/claim-vector/build \
  -H "Content-Type: application/json" \
  -d '{"projectId": "immortal", "businessVersion": "5.1"}'

# 3. 查看构建状态
curl -X GET http://localhost:8080/api/knowledge/multi-source/claim-vector/status?projectId=immortal&businessVersion=5.1
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
| Qdrant 写入失败 | `updateStatus(FAILED)` + warnings，alias 不变 | 检查 Qdrant 连接后重新构建 |
| alias 切换失败 | SQLite 不回滚（已 ACTIVE），Qdrant 仍指旧 collection | 手动 `switchAlias` 或 `rollbackAlias` |
| 无 eligible Claim | 抛 `IllegalStateException` | 检查数据源是否有 Claims |

---

## 4. 回滚流程

### 4.1 回滚到上一代际

```java
// 通过 BuildService.rollback
buildService.rollback("immortal", "5.1");
// → listRetiredForRollback → rollbackTo (RETIRED→ACTIVE) → rollbackAlias (Qdrant 切回)
```

### 4.2 回滚到指定代际

```java
buildService.rollbackTo("immortal", "5.1", "gen-20250115-abc123");
```

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

```bash
# 1. SQLite 仍保存所有 manifest，Qdrant 可重建
# 2. 找到当前 ACTIVE 代际
curl -X GET http://localhost:8080/api/knowledge/multi-source/claim-vector/status?projectId=immortal&businessVersion=5.1

# 3. 触发重建（会跳过嵌入因为指纹不变 → findReusableGeneration 命中 → 但物理 collection 已丢失）
# 需要强制重建：删除 ACTIVE 代际的物理记录后重新构建
# 或直接调用 buildService.build() —— findReusableGeneration 会跳过，需要先 rollback 再 build
```

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
