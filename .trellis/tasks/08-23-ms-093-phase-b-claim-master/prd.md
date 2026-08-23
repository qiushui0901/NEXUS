# 0.9.3 Phase B：统一 Claim 主表与扩展表映射

## Goal

新增来源无关的 `knowledge_claim` 主表与 `knowledge_claim_evidence` 关联表，把现有四类业务表（参数/存疑/测试用例/测试结果）写入时同步生成统一 Claim，提供确定性 `fact_key` 生成器，使任一 `UnifiedKnowledgeClaim.claimId` 都能在主库回查版本、状态与 Evidence。

## Requirements

- 新增 `knowledge_claim`：来源无关事实主记录，`claim_id` 与业务表主键一致（便于回查），唯一键按 `object_value` 去重（允许同 fact_key 多值并存）。
- 新增 `knowledge_claim_evidence`：Claim ↔ Evidence 多对多，`role` 支持 `SUPPORTS/CONTRADICTS/CONTEXT/RESOLUTION`。
- 新增统一 `fact_key` 生成器：`<projectId>|<businessVersion>|<module>|<normalizedSubject>|<normalizedPredicate>`（trim + 小写）。
- `MultiSourceKnowledgeStore` 提供：
  - `saveClaim`（幂等 upsert + 更新时间）
  - `linkClaimEvidence(claimId, evidenceId, role)`
  - `findClaimById` / `findClaimsByFactKey`
  - `syncSnapshotClaims(projectId, version, documentVersionId, evidenceIdByClaimId)`：把一次快照内的四类业务表行批量生成 Claim + Evidence 关联。
- 保留旧投影（Adapter/搜索）作为迁移期回退；本阶段先提供持久化与回查能力，不强制重写 Adapter。

## Acceptance Criteria

- [ ] 同内容重复 `saveClaim` 不产生重复主记录，`updated_at` 更新。
- [ ] 同 fact_key 不同 `object_value` 可并存；完全重复被去重。
- [ ] `syncSnapshotClaims` 后，任一参数/存疑/测试用例/测试结果 claimId 可在 `knowledge_claim` 查询到其版本、状态与 Evidence。
- [ ] `findClaimsByFactKey` 能按项目/版本/事实键命中多来源 Claim。
- [ ] `evidenceLocation` 与现有 API 保持兼容，全量测试通过。