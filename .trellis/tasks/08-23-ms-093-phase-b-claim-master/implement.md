# 0.9.3 Phase B：统一 Claim 主表与扩展表映射 — Implement

> 每步完成后运行对应验证命令再继续。

- [x] 1. `KnowledgeCatalogModels` 新增 `KnowledgeClaimRecord`、`KnowledgeClaimEvidence`、`ClaimEvidenceRole`。
- [x] 2. 新增 `KnowledgeFactKeyGenerator`（规范化 + 测试）。
- [x] 3. `MultiSourceKnowledgeStore.initialize()` 创建 `knowledge_claim` / `knowledge_claim_evidence` 表 + 索引。
- [x] 4. Store 新增 saveClaim / linkClaimEvidence / findClaimById / findClaimsByFactKey / findEvidenceIdsByClaimId / syncSnapshotClaims。
- [x] 5. 新增 `MultiSourceKnowledgeClaimTest`：幂等、多值并存、sync 后回查、Evidence 关联。
- [x] 6. 更新 CHANGELOG 0.9.3 与 Trellis implement.md，运行全量测试，提交推送。

## 验证命令

```bash
./mvnw -B test -Dtest='MultiSourceKnowledgeClaimTest,MultiSourceKnowledgeCatalogTest,MultiSourceKnowledgeStoreTest'
git diff --check
./mvnw -B test
```