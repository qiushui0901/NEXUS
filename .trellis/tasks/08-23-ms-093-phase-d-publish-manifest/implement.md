# 0.9.3 Phase D：发布目录与索引一致性 — Implement

> 每步完成后运行对应验证命令再继续。

- [x] 1. `ChunkRecord` 扩展 `documentVersionId / authority / status / evidenceId / factKey`，保留旧构造器。
- [x] 2. `QdrantHybridStore` payload 写入/读取新字段，新增 `setPayload`（payload-only 批量更新）。
- [x] 3. `knowledge_active_version` 表 + `MultiSourceKnowledgeStore` publish/rollback/active 方法。
- [x] 4. 新增测试：payload round-trip、setPayload 请求、active manifest 发布/回滚。
- [x] 5. 更新 CHANGELOG 0.9.3 与 Trellis implement.md，运行全量测试，提交推送。

## 验证命令

```bash
./mvnw -B test -Dtest='QdrantHybridStoreMultiSourceTest,MultiSourceKnowledgePublishTest'
git diff --check
./mvnw -B test
```