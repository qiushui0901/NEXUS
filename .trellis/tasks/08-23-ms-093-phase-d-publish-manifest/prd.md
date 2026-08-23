# 0.9.3 Phase D：发布目录与索引一致性

## Goal

新增 project + businessVersion 的 active document-version manifest，扩展 Qdrant payload 到 `documentVersionId/evidenceId/factKey/authority/status`，提供 payload-only 更新（不重新 embed）与回滚能力，使主库、索引、查询响应版本一致。

## Requirements

- 新增 `knowledge_active_version` 表：`(project_id, business_version) -> document_version_id + status + published_at`。
- `MultiSourceKnowledgeStore` 提供 `publishDocumentVersion` / `rollbackActiveVersion` / `activeDocumentVersion`。
- `ChunkRecord` 扩展：`documentVersionId / authority / status / evidenceId / factKey`（旧构造器默认空/`REQUIREMENT`）。
- `QdrantHybridStore` 写入 payload 时包含上述字段，读取时兼容缺失字段。
- `QdrantHybridStore` 提供 `setPayload(collection, payloadById)`：只更新 payload，不重算向量（避免全量 re-embed）。
- 发布顺序约定：先更新主库 active manifest，再切换 Qdrant alias；回滚反向。

## Acceptance Criteria

- [ ] 主库可记录/查询各项目业务版本的 active document-version，并支持回滚。
- [ ] Qdrant 写入/读取 payload 包含 `documentVersionId/evidenceId/factKey/authority/status`。
- [ ] payload-only 更新不触发重算/重写向量（单次 HTTP 请求更新指定点 payload）。
- [ ] 全量测试通过。