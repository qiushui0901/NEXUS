# Phase 3 实体中心证据查询 — 实施清单

## 新增（包 `knowledge/multisource/entity`）

1. `EntityEvidenceModels`：响应模型（§8.4）——`EntitySearchResponse(query, plan, entities, factAssessment, citations)`、
   `EntityView(entityId, canonicalName, aliases, currentFacts, timeline, relations, conflicts, warnings)`、
   `CurrentFacts(code, parameterTables, testResults)`、`VersionFactBlock(businessVersion, requirements, parameterTables, tests)`、
   `FactRef(claimId/sourceType/subject/objectValue/unit/evidenceIds/businessVersion)`、`RelationView`、`ConflictView(factKey, values, status)`。
2. `EntityEvidenceAggregator`：
   - 每实体：`findMembers(projectId, entityId, null)` → claimId→businessVersion 映射 + CODE 成员（externalId/displayName/commitSha/evidenceId）。
   - `knowledgeStore.findClaimsByIds(claimIds)` 批量水化 → 按 sourceType 分组；按 member 的 businessVersion 组织 timeline。
   - 当前事实：CODE 成员（含 commitSha）→ code；最新版本 findParameters 过滤参数名/模块命中该实体成员 → parameterTables；
     TEST_RESULT claim → testResults。
   - 关系：`findAlignmentRelationsForClaim`（按 claimId 查 source/target）+ CODE 成员 externalId 关系 → 去重。
   - 冲突：同 factKey 不同 objectValue → ConflictView(CONFLICTED)。
   - 告警：无 CODE 成员/commit → CODE_CONTEXT_UNAVAILABLE；最新版本无参数命中 → PARAMETER_TABLE_UNAVAILABLE。
   - 上限：timeline 每版本 claim 上限（如 20），版本块数受 plan.requestedVersions 或全部版本约束。
3. `EntityQueryService.search(...)`：analyzer.analyze → resolver.resolve（mention 为空时兜底）→ aggregator.aggregate →
   组装 `EntitySearchResponse`；`factAssessment` 返回空骨架；citations = 全部 FactRef 证据去重。
4. `EntitySearchController`：`POST /api/knowledge/entity-search`（@Valid 请求，projectId/query 必填，limit 1..50 默认 20）。
5. `CodeCentricAlignmentStore`：新增 `findAlignmentRelationsForClaim(projectId, claimId)`。

## 测试

- `EntityEvidenceAggregatorTest`：跨版本时间轴（param+req+test 在 5.0/5.1）；当前事实分组（code/parameterTables/testResults）；
  同 factKey 冲突 CONFLICTED；无代码/无数值告警；版本过滤。
- `EntityQueryServiceTest`：端到端（无 LLM）——seed → search → 响应含实体/currentFacts/timeline/relations/conflicts。
- `EntitySearchControllerTest`：HTTP 200 + JSON 结构；缺 query 返回 400。

## 评审点

- 全量 claim 物化风险：findClaimsByIds 批量上限 + 每实体/版本块上限。
- 当前参数表 = 最新版本 findParameters 子集（与实体成员对齐），不把全部参数塞进 currentFacts。
- 不编造 commit（无 CODE 成员 → 告警）。
- 不改 multi-source/search。