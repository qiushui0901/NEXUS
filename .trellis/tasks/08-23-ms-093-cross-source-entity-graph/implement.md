# 0.9.3 跨源总实体关系图 — Implement

> 每步完成后运行对应验证命令再继续。

- [x] 1. 新增 `KnowledgeGraphModels`（entity / relation）。
- [x] 2. `MultiSourceKnowledgeStore` 新增表 + saveEntity/saveEntityRelation/findEntities/findEntityRelations/deleteGraph/findClaimsByProjectVersion。
- [x] 3. 新增 `KnowledgeGraphBuildService`（规则实体聚合 + 确定性关系 + CodeEntitySource/LlmGraphExtractor SPI）。
- [x] 4. 新增 `KnowledgeGraphController`（GET graph / POST build）。
- [x] 5. 新增 `KnowledgeGraphBuildServiceTest` / `KnowledgeGraphControllerTest` / `KnowledgeGraphBuildIT`。
- [x] 6. 代码接入：`SQLiteSymbolGraphStore.allSymbols` + `SymbolGraphCodeEntitySource`（immortal→immortal-game-service 映射）。
- [x] 7. LLM 语义边：`LlmKnowledgeGraphExtractor` + `KnowledgeGraphBuildConfiguration`（`app.rag.multi-source.graph-llm-enabled=true` 开启）。
- [x] 8. 对已导入 immortal 数据生成真实图（含代码）；更新 CHANGELOG/Trellis，运行全量测试，提交推送。

## 验证命令

```bash
./mvnw -B test -Dtest='KnowledgeGraphBuildServiceTest,KnowledgeGraphControllerTest'
./mvnw -B test -Dtest=KnowledgeGraphBuildIT -Dgraph.build=true
git diff --check
./mvnw -B test
```