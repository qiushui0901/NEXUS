# 0.9.3 Phase B：统一 Claim 主表与扩展表映射 — Design

## 数据流

```text
Loader/解析 -> multi_source_* 业务表（保持兼容）
                 │
                 └─ syncSnapshotClaims(projectId, version, documentVersionId, evidenceIdByClaimId)
                            │
                            v
                    knowledge_claim + knowledge_claim_evidence
                            │
                            v
                    findClaimById / findClaimsByFactKey（统一回查）
```

## 表结构

### knowledge_claim
```sql
create table if not exists knowledge_claim(
  claim_id text primary key,
  project_id text not null,
  document_version_id text not null,
  source_type text not null,
  authority text not null,
  fact_key text not null,
  subject text not null,
  predicate text not null,
  object_value text,
  value_type text,
  unit text,
  status text not null,
  confidence real,
  effective_from text,
  effective_to text,
  extraction_method text not null,
  extraction_run_id text,
  created_at text not null,
  updated_at text not null,
  foreign key(document_version_id) references knowledge_document_version(document_version_id),
  unique(project_id, document_version_id, source_type, fact_key, object_value)
);
create index if not exists idx_knowledge_claim_fact on knowledge_claim(project_id, document_version_id, fact_key);
```

### knowledge_claim_evidence
```sql
create table if not exists knowledge_claim_evidence(
  claim_id text not null,
  evidence_id text not null,
  role text not null default 'SUPPORTS',
  created_at text not null,
  primary key(claim_id, evidence_id),
  foreign key(claim_id) references knowledge_claim(claim_id),
  foreign key(evidence_id) references knowledge_evidence(evidence_id)
);
```

## fact_key 生成器

`KnowledgeFactKeyGenerator.generate(projectId, businessVersion, module, subject, predicate)`：

```text
<projectId>|<businessVersion>|<module>|<normalizedSubject>|<normalizedPredicate>
```

规范化：trim + 去空白 + 小写；null/空段用空字符串。

## 映射规则（syncSnapshotClaims）

对一次 `replaceSnapshot` 后的四张业务表逐行：

| 业务表 | claim_id | subject | predicate | object_value | authority |
| --- | --- | --- | --- | --- | --- |
| multi_source_parameter | claim_id | parameter | `value` | normalized_value | PRIMARY |
| multi_source_doubt | doubt_id | module | question | answer | PRIMARY |
| multi_source_test_case | claim_id | title | `expectedResult` | expected_result | SECONDARY |
| multi_source_test_result | claim_id | testCaseId | `executionStatus` | execution_status | SECONDARY |

- `claim_id` 与业务表主键一致，保证 `UnifiedKnowledgeClaim.claimId` 可回查主库。
- 每个 Claim 至少关联一条 Evidence；如果某行没有 evidenceId，则跳过关联但主记录仍写入。
- `extraction_method` 用 `RULE`，`status` 映射业务表 status（无则 `SUPPORTED`）。

## Store API

- `void saveClaim(KnowledgeClaimRecord claim)`：存在同唯一键时更新 `updated_at`/非空字段，否则插入。
- `void linkClaimEvidence(String claimId, String evidenceId, String role)`：`INSERT OR IGNORE`。
- `Optional<KnowledgeClaimRecord> findClaimById(String claimId)`
- `List<KnowledgeClaimRecord> findClaimsByFactKey(String projectId, String documentVersionId, String factKey)`
- `void syncSnapshotClaims(String projectId, String version, String documentVersionId, Map<String,String> evidenceIdByClaimId)`：事务内批量生成四类 Claim 与 Evidence 关联，并调用 `linkClaimToCatalog` 回填业务表关联列。

## 测试策略

- Claim 幂等 upsert（同内容不重复，updated_at 变化）。
- 同 fact_key 多 object_value 并存。
- sync 后四类 claimId 可在主库按 ID/事实键查询，且 Evidence 关联存在。
- 现有 `MultiSourceKnowledgeCatalogTest/StoreTest/LoaderTest` 不回归。