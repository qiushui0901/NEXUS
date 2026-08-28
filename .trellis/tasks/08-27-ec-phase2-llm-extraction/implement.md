# Phase 2 LLM 实体提取与归一化 — 实施清单

## 新增包 `knowledge/multisource/entity`

1. `EntityExtractionModels`：`QueryIntent`（GENERAL/CURRENT_STATE/NUMERIC_VALUE/IMPLEMENTATION/HISTORY/
   VALIDATION/CONSISTENCY）、`EntityMention(text,entityId?,matchMethod,confidence,status)`、
   `EntityQueryPlan(projectId,originalQuery,mentions,intent,requestedVersions,includeHistory,
   asksCurrentState,asksImplementation,asksNumericValue)`、`ResolutionCandidate`、
   `EntityResolution(resolved,candidates,llmUsed,warnings)`；LLM 结构化输出 record：
   `QuestionExtractionRaw(entities,intent,versions)`、`SourceExtractionRaw(entities,facts,relations)`、
   `SourceEntityRaw/SourceFactRaw/SourceRelationRaw`；`AliasOrigin`
   （SOURCE_EXPLICIT/RULE_NORMALIZED/LLM_PROPOSED/HUMAN_CONFIRMED）、`AliasProposal`、`RelationProposal`。
2. `EntityExtractionProperties`（`@ConfigurationProperties("app.rag.entity-extraction")`，默认全开但 LLM 可选）：
   enabled/model/maxMentionsPerQuery/maxEntitiesPerSourceBatch/maxFactsPerSourceBatch/
   maxRelationsPerSourceBatch/sourceBatchSize/reviewThreshold/maxAliasScan/allowLlmAssist。
3. `EntityExtractionPromptService`：系统+用户 Prompt（问题分析、来源提取、受限选择三套，含 JSON 形状说明）。
4. `EntityExtractionValidator`：问题输出（实体名非空、上限）、来源输出（sourceClaimId ∈ 输入批次、
   relationType 白名单、数量上限）、受限选择（entityId 必须 ∈ 候选集）。
5. `QuestionEntityAnalyzer`：规则优先——`findEntitiesMentionedIn` 命中别名（CONFIRMED）→ mentions；
   意图/版本条件/asks* 规则推导；LLM assist（allowLlmAssist 且模型可用）只补召回 + 校验；LLM 失败返回规则结果。
6. `EntityResolverService`：解析链 规范化精确 → CONFIRMED 别名 → 成员名（=alias，Phase1 已别名化）→
   factKey/subject/列名 → 代码符号 → LLM 受限选择 → NEEDS_REVIEW（多候选）。输出 `EntityResolution`。
7. `SourceEntityExtractor`：输入 bounded claims（sourceBatchSize）→ 规则先存别名（SOURCE_EXPLICIT，
   Phase1 已做，本阶段复用）→ LLM 提议别名（LLM_PROPOSED/PROPOSED）与关系（LLM_PROPOSED/PROPOSED）
   → validator → alignmentStore 落库；**不写知识事实**（LLM 值不覆盖来源原始值）。

## 存储改动

8. `CodeCentricModels.ConceptAlias`：加 `origin/status/evidenceId`（compact constructor 默认
   RULE_NORMALIZED/CONFIRMED/null）。
9. `CodeCentricAlignmentStore`：`addColumnIfMissing` 加 `business_concept_alias.origin/status/evidence_id`；
   `upsertAlias`/`findAliases` 读写新字段；新增 `findEntitiesMentionedIn(projectId,text,limit)`（instr 扫描，
   别名命中→conceptId 映射）、`findConceptIdsByAlias(projectId,alias)`。
10. `BusinessConceptService`：写别名时带 origin=SOURCE_EXPLICIT、status=CONFIRMED（SOURCE_NAME→SOURCE_EXPLICIT 映射）。
11. `WebMvcConfig` 注册 `EntityExtractionProperties`（若 semantic properties 走同一机制）。

## 测试

- 规则问题提取：命中/未命中/多实体/意图与版本条件。
- 受限选择：LLM 返回未知 entityId 被拒。
- 来源提取：非法 claimId/非法 relationType/超上限被拒；合法提议别名 PROPOSED 不参与精确匹配。
- NEEDS_REVIEW：低置信多候选 + 不合并。
- LLM 失败降级：模型不可用/解析失败 → 规则结果完整返回 + 稳定错误码。
- 校验：`./mvnw -Dtest='*Entity*Test,QuestionEntityAnalyzerTest,SourceEntityExtractorTest' test` + 全量。

## 评审点

- 规则解析链不引入全量扫描（alias instr 有 limit 封顶）。
- LLM 提议不直写 knowledge_claim / 不改变 CONFIRMED 匹配结果。
- 属性注册与 enable/disable 语义（无模型环境可用）。