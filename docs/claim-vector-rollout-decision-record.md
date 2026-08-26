# Claim 向量投影发布决策记录

> **版本**：0.9.6  
> **决策日期**：2025-01-18  
> **决策者**：____  
> **状态**：⬜ 待评估 / ⬜ 已批准 / ⬜ 已驳回 / ⬜ 已发布

---

## 1. 发布范围

- **功能**：Claim 向量投影检索（0.9.6 Phase A-E）
- **影响组件**：`knowledge/multisource/vector/`、`MultiSourceSearchService`、`SourceFilterStrategy`
- **向后兼容**：✅ 是——所有开关默认 `false`，不改变现有行为

## 2. 发布前置条件

| # | 条件 | 状态 | 证据 |
|---|------|------|------|
| 1 | 单元测试全量通过 | ⬜ | `./mvnw test` → _____ tests, 0 failures |
| 2 | 201K Claims 规模构建成功 | ⬜ | `BuildService.build("immortal", "5.1")` 完成，indexedPointCount = _____ |
| 3 | 质量门全部通过 | ⬜ | `QualityGate.check("immortal", "5.1")` readyToPublish = true |
| 4 | 影子模式数据充足 | ⬜ | ≥20 条影子查询，向量新增召回率 ≥ 30% |
| 5 | 回滚演练成功 | ⬜ | `BuildService.rollback("immortal", "5.1")` 切回旧代际，质量门通过 |
| 6 | 全量重建演练成功 | ⬜ | 删除 Qdrant collection 后重建，质量门通过 |
| 7 | 基线检索指标不退化 | ⬜ | Recall@1 ≥ 93.6%, MRR ≥ 0.9596 |

## 3. 灰度发布计划

| 阶段 | 配置 | 持续时间 | 通过条件 |
|------|------|----------|----------|
| **阶段 0：关闭** | 全 `false` | — | 现有行为不变 |
| **阶段 1：构建** | `enabled=true`, `build-enabled=true` | 1 天 | 构建成功，质量门通过 |
| **阶段 2：影子** | + `shadow-query-enabled=true` | 1 周 | ≥20 查询，召回率 ≥ 30% |
| **阶段 3：候选** | + `candidate-retrieval-enabled=true` | 1 周 | 检索指标不退化，无异常警告 |
| **阶段 4：融合** | 融合自动激活（候选开关开启后） | 1 周 | Recall/MRR 提升或持平 |
| **阶段 5：正式** | 全 `true` | — | 质量门持续通过 |

## 4. 回滚预案

- **回滚触发条件**：检索指标退化 > 5%、质量门持续失败、Qdrant 不可用
- **回滚步骤**：
  1. `candidate-retrieval-enabled=false`（立即停止向量候选）
  2. `BuildService.rollback(projectId, version)`（切回上一代际）
  3. 质量门验证
- **回滚时间**：< 5 分钟（配置切换 + alias 切换）

## 5. 融合权重决策

| 来源 | 权重 | 理由 |
|------|------|------|
| 向量（semantic） | 0.55 | 主召回通道，语义相似度 |
| 词法（lexical） | 0.25 | 精确字段匹配，稳定基线 |
| 来源策略（policy） | 0.10 | PRIMARY > SECONDARY > DERIVED |
| 精确命中（exact） | 0.10 | factKey / subject 完全匹配 |

## 6. 风险评估

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| Qdrant 不可用 | 低 | 中（回退结构化检索） | fail-safe 返回空列表 |
| 嵌入模型不可用 | 低 | 高（无法构建） | EmbeddingBatcher 缓存+二分降级 |
| 向量召回质量差 | 中 | 中（候选污染） | 影子模式监控，< 30% 召回率不发布 |
| 候选去重遗漏 | 低 | 低（重复候选） | UnifiedKnowledgeClaim.equals 自然去重 |

## 7. 决策记录

- **日期**：____
- **决策**：⬜ 批准发布 / ⬜ 推迟 / ⬜ 驳回
- **决策者**：____
- **备注**：

---

## 附录：代码组件清单

| 组件 | 文件 | 阶段 |
|------|------|------|
| 投影契约模型 | `KnowledgeClaimVectorModels.java` | A |
| 配置 | `KnowledgeClaimVectorProperties.java` | A |
| 确定性文本组合器 | `KnowledgeClaimVectorTextComposer.java` | A |
| SQLite 代际存储 | `SQLiteKnowledgeClaimVectorStore.java` | A |
| Qdrant 发布器 | `KnowledgeClaimVectorQdrantStore.java` | B |
| 构建服务 | `KnowledgeClaimVectorBuildService.java` | B |
| 候选适配器 | `ClaimVectorCandidateAdapter.java` | C |
| SQLite 批量水化 | `MultiSourceKnowledgeStore.findClaimsByIds` | C |
| 确定性融合 | `KnowledgeClaimVectorFusion.java` | D |
| 影子评估器 | `ClaimVectorShadowEvaluator.java` | D |
| 质量门 | `ClaimVectorQualityGate.java` | E |
| 搜索服务集成 | `MultiSourceSearchService.java` | D |
| 来源过滤策略 | `SourceFilterStrategy.java` | D |
