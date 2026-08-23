# 0.9.3 Phase D：发布目录与索引一致性 — Design

## 表结构：knowledge_active_version

```sql
create table if not exists knowledge_active_version(
  project_id text not null,
  business_version text not null,
  document_version_id text not null,
  status text not null,
  published_at text not null,
  updated_at text not null,
  primary key(project_id, business_version)
);
```

## Store API

- `publishDocumentVersion(projectId, businessVersion, documentVersionId)`：upsert active manifest（status=PUBLISHED）。
- `rollbackActiveVersion(projectId, businessVersion, documentVersionId)`：把 manifest 指回目标版本。
- `Optional<String> activeDocumentVersion(projectId, businessVersion)`。

## Qdrant payload 扩展

`ChunkRecord` 新增：`documentVersionId / authority / status / evidenceId / factKey`（默认空串；sourceType 默认 REQUIREMENT 不变）。

`QdrantHybridStore.buildPoints` payload 追加：

```text
projectId, documentVersionId, authority, status, evidenceId, factKey
```

`toRecord` 读取时缺失字段回退空串。

## payload-only 更新

```text
POST /collections/{collection}/points/payload?wait=true
{ "points": [ { "id": "...", "payload": { ... } } ] }
```

`QdrantHybridStore.setPayload(collection, Map<String, Map<String,Object>> payloadById)`：批量合并 payload，不触碰向量，避免全量 re-embed 回填。

## 发布顺序

1. SQLite 主库写入新 DocumentVersion/Evidence/Claim → 校验 → `publishDocumentVersion` 更新 active manifest。
2. 只有 `activeDocumentVersion` 等于目标版本时，才允许切换 Qdrant alias。
3. 回滚：`rollbackActiveVersion` → 查询与索引跟随 manifest。

## 测试策略

- active manifest 发布/回滚/查询。
- ChunkRecord payload round-trip 含新字段。
- `setPayload` 单请求批量更新 payload（MockRestServiceServer）。