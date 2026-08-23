# 0.9.3 跨源总实体关系图 — Design

## 数据模型

```text
knowledge_entity(entity_id, project_id, version, name, normalized_name,
                 entity_type, source_type, summary, evidence_id, source_claim_ids, created_at, updated_at)
knowledge_entity_relation(relation_id, project_id, version, source_entity_id, target_entity_id,
                          relation_type, status, confidence, extraction_method, evidence_ids, created_at, updated_at)
```

## 构建流程（KnowledgeGraphBuildService.build）

1. `store.deleteGraph(projectId, version)` 幂等清空。
2. 读取 `knowledge_claim`（`findClaimsByProjectVersion`）。
3. 聚合实体：
   - REQUIREMENT → 实体名 = subject（功能）
   - PARAMETER_TABLE → 实体名 = fact_key 第二段 module（sheet）
   - TEST_CASE → 实体名 = fact_key 第二段 module
   - DOUBT → 实体名 = fact_key 第二段 module，缺失 → “QA存疑”
   - CODE → `CodeEntitySource.load(projectId, version)`
4. 规则关系：规范化名称包含/相等匹配，生成 SUPPORTS / VERIFIES / RAISES_DOUBT / IMPLEMENTED_BY（RULE_PROPOSED）。
5. 可选 LLM：`LlmGraphExtractor.extract(...)` 返回语义边，合并为 LLM_CONFIRMED。

## SPI

- `CodeEntitySource.load(projectId, version) -> List<CodeEntityInput>`
- `LlmGraphExtractor.extract(projectId, version, entities) -> List<SemanticEdge>`

## API

- `GET /api/knowledge/graph?projectId&version` → {entities, relations}
- `POST /api/knowledge/graph/build` {projectId, version} → {entities, relations}

## 测试

- 单元：模块级实体聚合 + 规则关系。
- 控制器：查询/构建/权限。
- IT（`-Dgraph.build=true`）：对已导入 immortal 数据构建真实图。