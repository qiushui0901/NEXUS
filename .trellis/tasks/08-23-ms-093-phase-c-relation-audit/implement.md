# 0.9.3 Phase C：关系、冲突与审核审计 — Implement

> 每步完成后运行对应验证命令再继续。

- [x] 1. `KnowledgeCatalogModels` 新增 `KnowledgeRelation`、`ExtractionRun`。
- [x] 2. `MultiSourceKnowledgeStore` 新增 `knowledge_relation` / `knowledge_extraction_run` 表、saveRelation / findRelationsForClaims / startExtractionRun / finishExtractionRun / findExtractionRun / reviewRelation。
- [x] 3. 新增 `KnowledgeRelationBuildService`：离线/发布前关系生产 + 抽取运行审计 + 可选 LLM 确认。
- [x] 4. `MultiSourceSearchService` 查询改为只读预生成关系并按页裁剪一跳邻域（旧表只读回退）。
- [x] 5. 新增人工审核 API `POST /api/knowledge/review/relations/{relationId}`。
- [x] 6. 新增 `KnowledgeRelationBuildServiceTest` / `KnowledgeReviewControllerTest`，更新 `MultiSourceSearchServiceTest`。
- [x] 7. 更新 CHANGELOG 0.9.3 与 Trellis implement.md，运行全量测试，提交推送。

## 验证命令

```bash
./mvnw -B test -Dtest='KnowledgeRelationBuildServiceTest,KnowledgeReviewControllerTest,MultiSourceSearchServiceTest'
git diff --check
./mvnw -B test
```