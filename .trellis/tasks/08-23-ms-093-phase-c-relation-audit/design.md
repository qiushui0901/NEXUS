# 0.9.3 Phase C：关系、冲突与审核审计 — Design

## 表结构

### knowledge_relation
```sql
create table if not exists knowledge_relation(
  relation_id text primary key,
  project_id text not null,
  version text not null,
  source_claim_id text not null,
  target_claim_id text not null,
  relation_type text not null,
  status text not null,
  confidence real,
  evidence_id text,
  extraction_method text not null,
  confirmation_method text,
  confirmation_reason text,
  created_at text not null,
  updated_at text not null,
  foreign key(source_claim_id) references knowledge_claim(claim_id),
  foreign key(target_claim_id) references knowledge_claim(claim_id),
  foreign key(evidence_id) references knowledge_evidence(evidence_id),
  unique(project_id, version, source_claim_id, target_claim_id, relation_type)
);
```

### knowledge_extraction_run
```sql
create table if not exists knowledge_extraction_run(
  extraction_run_id text primary key,
  project_id text not null,
  document_version_id text not null,
  parser_name text not null,
  parser_version text not null,
  model_name text,
  prompt_version text,
  input_hash text not null,
  output_hash text,
  status text not null,
  prompt_tokens integer,
  completion_tokens integer,
  error_message text,
  started_at text not null,
  finished_at text,
  foreign key(document_version_id) references knowledge_document_version(document_version_id)
);
```

## 关系生产（离线）

`KnowledgeRelationBuildService.buildRelations(projectId, version, documentVersionId, candidates, doubts, evidenceMap)`：

1. 生成 `input_hash` 与稳定 `extraction_run_id`，写 `RUNNING` 审计。
2. `CrossSourceRelationExtractor.extract` 规则抽取（不伪造悬空 target）。
3. 每条关系写入 `knowledge_relation`：默认 `RULE_PROPOSED`；若开启 LLM 确认且确认通过 → `LLM_CONFIRMED`，拒绝 → `LLM_REJECTED`（仍保留用于审计）。
4. 结束抽取运行 `SUCCESS`，回写 `output_hash`。

## 查询路径

`MultiSourceSearchService` 不再生成/持久化/LLM；改为：

```text
store.findRelationsForClaims(projectId, version, pageClaimIds)  // knowledge_relation，一跳
  为空时回退 store.findRelations(projectId, version) 按页裁剪（旧表只读）
```

## 人工审核 API

`POST /api/knowledge/review/relations/{relationId}`，body：`{projectId, status, reason}`。
`MultiSourceKnowledgeStore.reviewRelation` 更新 `status/confirmation_method=HUMAN/confirmation_reason/updated_at`。

## 测试策略

- 离线构建产生 `RULE_PROPOSED` 关系 + 抽取运行 SUCCESS。
- LLM 拒绝/确认分别落 `LLM_REJECTED / LLM_CONFIRMED`。
- 查询只读且按页裁剪一跳关系。
- 审核 API 权限与调用、缺参 400。