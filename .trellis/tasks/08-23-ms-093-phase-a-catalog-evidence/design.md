# 0.9.3 Phase A：统一目录与 Evidence — Design

## 边界与数据流

```text
Loader/Adapter 产生的 Claim
   └── 现有 multi_source_* 表（保持兼容）
         └── (可选关联) document_version_id + evidence_id
               └── knowledge_document/knowledge_document_version/knowledge_evidence
```

- Catalog 与现有业务表存放在同一个 SQLite 库，`PRAGMA foreign_keys=ON`。
- 本次不重写 Loader 写路径；提供等幂关联方法让调用方把已有 Claim 关联到 catalog。
- `evidenceLocation` 字符串保留为兼容展示字段；结构化证据以 `knowledge_evidence` 为唯一事实定位。

## 表结构（与方案文档对齐，修正唯一约束）

### knowledge_document
```sql
create table if not exists knowledge_document(
  document_id text primary key,
  project_id text not null,
  source_type text not null,
  logical_name text not null,
  original_name text,
  storage_uri text not null,
  authority text not null,
  created_at text not null,
  unique(project_id, source_type, logical_name)
);
```

### knowledge_document_version
```sql
create table if not exists knowledge_document_version(
  document_version_id text primary key,
  document_id text not null,
  project_id text not null,
  business_version text not null,
  content_hash text not null,
  parser_version text not null,
  extraction_version text not null,
  source_commit_sha text,
  status text not null,
  imported_at text not null,
  published_at text,
  foreign key(document_id) references knowledge_document(document_id),
  unique(document_id, business_version, content_hash, parser_version, extraction_version)
);
```
> 相比原始方案补上 `business_version`，避免不同业务版本复用同一版本记录。

### knowledge_evidence
```sql
create table if not exists knowledge_evidence(
  evidence_id text primary key,
  document_version_id text not null,
  project_id text not null,
  source_type text not null,
  locator text not null,
  excerpt text not null,
  excerpt_hash text not null,
  start_line integer,
  end_line integer,
  sheet_name text,
  row_number integer,
  column_range text,
  repository_id text,
  commit_sha text,
  symbol_name text,
  created_at text not null,
  foreign key(document_version_id) references knowledge_document_version(document_version_id),
  unique(document_version_id, locator, excerpt_hash)
);
```

## Evidence ID 生成规则

```text
ev:<projectId>:<documentVersionId>:<sha256(locator + "|" + excerptHash) 前 40 位>
```

- 服务端由 `KnowledgeEvidenceIdGenerator` 生成。
- 同一项目/版本/位置/摘要恒等。

## Catalog Store API

- `String registerDocument(KnowledgeDocument doc)`：按唯一键 upsert，返回 documentId。
- `KnowledgeDocumentVersion upsertDocumentVersion(KnowledgeDocumentVersion version)`：命中唯一键时返回已有版本，否则插入。
- `String saveEvidence(KnowledgeEvidence evidence)`：命中唯一键时返回已有 evidenceId，否则插入。
- `Optional<KnowledgeDocumentVersion> findDocumentVersion(documentId, businessVersion, contentHash, parserVersion, extractionVersion)`
- `List<KnowledgeEvidence> findEvidenceByDocumentVersion(documentVersionId)`
- `Optional<KnowledgeEvidence> findEvidenceById(evidenceId)`
- `void linkClaimToCatalog(String sourceType, String claimId, String documentVersionId, String evidenceId)`：更新对应 `multi_source_*` 表的可空列。

## 兼容列迁移

初始化时对四张现有业务表执行 `addColumnIfMissing`：

- `multi_source_parameter`：`document_version_id text`、`evidence_id text`
- `multi_source_doubt`：同上
- `multi_source_test_case`：同上
- `multi_source_test_result`：同上

## 测试策略

- Catalog 幂等：重复注册 Document/Version/Evidence 记录数不增长。
- 版本唯一性：同内容不同 `business_version` 可共存。
- Evidence ID 稳定：同输入两次生成一致。
- 关联：参数/存疑/测试用例/测试结果各建一行后 `linkClaimToCatalog`，再读取新列断言可回查。
- 现有 `MultiSourceKnowledgeStoreTest` 不回归。