# 0.9.3 Phase A：统一目录与 Evidence — Implement

> 每步完成后运行对应验证命令再继续。

- [x] 1. 新增 catalog 领域模型（`KnowledgeCatalogModels`：Document / DocumentVersion / Evidence / CatalogReference）。
- [x] 2. 新增 `KnowledgeEvidenceIdGenerator`（稳定 ID + 测试）。
- [x] 3. 在 `MultiSourceKnowledgeStore.initialize()` 创建三张 catalog 表 + 索引，并为四张业务表 `addColumnIfMissing`。
- [x] 4. 在 store 新增 register/upsert/find/saveEvidence/linkClaimToCatalog/findCatalogReference 方法。
- [x] 5. 修正方案文档 `knowledge_document_version` 唯一约束（补 `business_version`）与 `knowledge_claim` 唯一键（按 object_value 去重）。
- [x] 6. 新增 `MultiSourceKnowledgeCatalogTest`：幂等、版本唯一、Evidence ID 稳定、四类表关联回查。
- [x] 7. 版本统一为 0.9.3，更新 CHANGELOG 0.9.3 与 Trellis implement.md，运行全量测试，提交推送。

## 验证命令

```bash
./mvnw -B test -Dtest='MultiSourceKnowledgeCatalogTest,MultiSourceKnowledgeStoreTest,MultiSourceKnowledgeLoaderTest'
git diff --check
./mvnw -B test
```
