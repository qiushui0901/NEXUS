## 0.9.4 — 2026-08-23

### Added

- 产品版本升级为 `0.9.4`，同步 Maven、README、MCP server 与前端外壳版本展示。
- 新增改进方案文档 `docs/code-centric-cross-source-knowledge-graph-improvement-plan.md`，并把其 Phase 1-4（业务概念与版本上下文、代码—参数对齐、代码—测试图谱、需求—代码漂移）落地为独立的**跨源对齐子系统** `knowledge/multisource/alignment`：
  - **Phase 1 业务概念与版本上下文**
    - 新增 `version_context`（project + businessVersion + repositoryId + commitSha + environment，幂等 upsert），`VersionContextService` 从代码符号图解析当前 commit，不存在的代码快照不伪造 commit。
    - 新增 `business_concept` / `business_concept_alias` / `business_concept_member` 三表：业务概念 canonicalKey 稳定生成（`param:<module>.<name>`、`req:<name>`、`test:<module>`、`obs:<module>`、`doubt:<module>`），声明作为概念成员并标注 `truthRole`（IMPLEMENTATION/CONFIGURATION/OBSERVATION/INTENT/QUESTION/DERIVED）；代码符号经规范化名称/别名匹配挂到对应概念（truthRole=IMPLEMENTATION，一个代码符号可同时实现参数与需求概念）。
    - `BusinessConceptService`：从统一 Claim + 代码符号重建概念/别名/成员，幂等；可按项目查询概念。
  - **Phase 2 代码—参数表关系**
    - `CodeParameterAlignmentService`：确定性把参数 Claim 与代码符号按规范化名称匹配，生成 `READS_CONFIG`（`NORMALIZED_NAME_EXACT/CONTAINS`，`RULE_CONFIRMED`）；提供 `CodeValueProvider` SPI 读取代码侧值，与参数值结构化比较，不一致生成 `CONFIG_DRIFT`（默认不提供值 → 不宣称漂移）。
  - **Phase 3 代码—测试图谱**
    - `CodeTestAlignmentService`：业务测试用例经 testMethod/testCaseId/title 映射到代码测试符号生成 `VERIFIES`；测试结果按 testCaseId 生成 `CONFIRMS`；FAILED 观测生成 `TEST_DRIFT`（含未映射代码时的降级结论）。
  - **Phase 4 需求—代码漂移检测**
    - `RequirementCodeDriftService`：每个需求映射到业务概念并绑定 VersionContext——无代码成员 → `UNMAPPED`；需求值与配置值不一致 → `DOCUMENT_DRIFT`（创建文档更新候选，不自动覆盖任一侧）；仅名称映射而无确定性实现关系 → `MAPPED_NO_IMPLEMENTATION_ASSERTION`（不宣称已实现）；只有存在 `READS_CONFIG / IMPLEMENTED_BY / ALIGNED_WITH` 确定性关系且无冲突才 → `ALIGNED`；输出 `DriftReport`（aligned/documentDrift/unmapped/mappedNoAssertion/reviewRequired 统计 + 明细清单）。
  - **Phase 5 存疑影响分析与关闭闭环**
    - 新增 `doubt_impact` 表与 `DoubtImpactService`：OPEN 存疑按 BusinessConcept 自动补全受影响的代码符号、参数、测试（`CODE / PARAMETER_TABLE / TEST_CASE`），影响项绑定 VersionContext。
    - 新增 `POST /api/knowledge/alignment/doubt-impact/build`、`GET /doubt-impact`、`POST /doubt-impact/resolve`；关闭存疑时更新 `multi_source_doubt` 状态、绑定人工结论与 Resolution Evidence，并关闭对应影响项。
  - **对齐存储**
    - 新增 `CodeCentricAlignmentStore`（与多源知识库共用同一 SQLite 文件）：`alignment_relation`（带 matchMethod/status/confidence/evidence、按 `version_context_id` 作用域隔离，NULL 安全的表达式唯一索引 + `insert or replace` 幂等）、`drift_item`（按 `version_context_id` + 概念 + 类型幂等）、`business_concept_member`（按 `business_version` 作用域隔离）、`doubt_impact`（按 `version_context_id` 作用域隔离，含关闭结论与 Resolution Evidence）。
  - **API**
    - 新增 `CodeCentricAlignmentController`（`/api/knowledge/alignment/*`）：版本上下文 resolve/list、概念 build/query、代码—参数 build/query、代码—测试 build/query、漂移 build/report、存疑影响 build/list/resolve；查询类接口按 environment 解析对应 VersionContext，只返回该上下文记录。
  - **文档状态**
    - `docs/nexus-0.9.3-multi-source-knowledge-storage-plan.md` 与 `docs/code-centric-cross-source-knowledge-graph-improvement-plan.md` 增加“实施状态（截至 0.9.4）”清单，按 [已实现]/[部分实现]/[待实施]/[待验证] 标注入当前真实状态。
  - **真实数据评测入口**
    - 新增 `AlignmentEvaluationIT`（`-Dalignment.eval=true` 显式开启）：在进程内用真实 multi-source + code-graph 库构建对齐/漂移/存疑影响，并根据 `src/test/resources/alignment-eval/*.golden.jsonl` 计算 Precision/Recall/F1；金标为空时输出覆盖/诊断报告。
    - 新增空金标模板：`code-param.golden.jsonl`、`code-test.golden.jsonl`、`drift.golden.jsonl`。
    - 首次真实数据基线（immortal/5.1，commit `026394c19dae4f77717cde75363c866815674adc`）：AlignmentRelation 336,119、DriftItem 123、DoubtImpact 621,943；报告见 `docs/reports/alignment-eval-2026-08-23.md`。
  - **测试**
    - 新增 `CodeCentricAlignmentStoreTest`、`BusinessConceptServiceTest`、`CodeParameterAlignmentServiceTest`、`CodeTestAlignmentServiceTest`、`RequirementCodeDriftServiceTest`、`DoubtImpactServiceTest`、`CodeCentricAlignmentControllerTest`（22 个用例）。
    - 全量测试：716 tests 通过。

### Fixed

- **真实数据构建性能**：`BusinessConceptService` / `CodeParameterAlignmentService` / `CodeTestAlignmentService` / `DoubtImpactService` 改为批量写入（成员、关系、漂移、存疑影响各单事务 batch），并给包含匹配加索引规模阈值（超过 2000 只用精确匹配），避免 779k 参数 × 8.5k 代码符号的全量扫描导致真实数据无法构建。
- **旧 schema 自动重建**：`CodeCentricAlignmentStore` 启动时检测对齐层派生表的旧唯一约束（缺少 `business_version` / `version_context_id`），直接重建为当前 schema，避免旧约束阻止新作用域隔离（对齐层数据可从源数据重建）。

- **对齐结论按 VersionContext 隔离，不再跨环境/跨 commit 互相覆盖**：
  - `version_context` 唯一键扩展为 `(project_id, business_version, environment, repository_id, commit_sha)`，`contextId` 含 commit；commit 切换会生成新的上下文记录，旧基线保留可审计。
  - `business_concept_member` 增加 `business_version` 与 `version_context_id`，唯一键含业务版本；`BusinessConceptService.build` 构建前先原子清理该业务版本的旧成员，代码符号删除/改名后旧 `CODE` 成员不再充当当前实现证据。
  - `alignment_relation` / `drift_item` 增加 `version_context_id` 列，纳入唯一约束、索引、删除与查询条件；构建/查询按 environment 解析对应 VersionContext，staging 与 production（或新旧 commit）各自保留独立基线，报告只返回请求环境上下文记录。
- **`ALIGNED` 不再仅凭同名代码符号产生**：需求—代码漂移只有在存在确定性实现关系（`READS_CONFIG` / `IMPLEMENTED_BY` / `ALIGNED_WITH`）且无配置冲突时才判定 `ALIGNED`；仅名称映射降级为 `MAPPED_NO_IMPLEMENTATION_ASSERTION`，避免 getter/DTO/测试辅助方法被误判为“代码已实现”。
- **修正方案文档行尾空白**：`docs/code-centric-cross-source-knowledge-graph-improvement-plan.md` 去除 trailing whitespace，`git diff --check` 通过。

## 0.9.3 — 2026-08-23

### Added

- 产品版本升级为 `0.9.3`，同步 Maven、README 与 MCP server 版本展示。
- 新增 0.9.3 多源知识存储落库方案文档 `docs/nexus-0.9.3-multi-source-knowledge-storage-plan.md`，并修正两处 schema：`knowledge_document_version` 唯一键补 `business_version`；`knowledge_claim` 唯一键改为按 `object_value` 去重，允许同 fact_key 多值并存（冲突/历史）。
- Phase A（统一目录与 Evidence）：
  - 新增 `knowledge_document / knowledge_document_version / knowledge_evidence` 三张 catalog 表，沿用现有 SQLite 库并开启外键。
  - 新增 `KnowledgeCatalogModels` 与 `KnowledgeEvidenceIdGenerator`（`ev:<projectId>:<documentVersionId>:<hash(locator|excerptHash)>` 稳定生成）。
  - `MultiSourceKnowledgeStore` 新增 registerDocument / upsertDocumentVersion / saveEvidence / findDocumentVersion / findEvidenceById / findEvidenceByDocumentVersion / linkClaimToCatalog / findCatalogReference。
  - 现有 `multi_source_parameter / doubt / test_case / test_result` 增加可空 `document_version_id`、`evidence_id` 列；`evidenceLocation` 继续兼容读取。
  - 新增 `MultiSourceKnowledgeCatalogTest`：Document 幂等、DocumentVersion 幂等且按 business_version 隔离、Evidence ID 稳定、四类业务表可关联回查。
- Phase B（统一 Claim 主表与扩展表映射）：
  - 新增 `knowledge_claim` 主表（`claim_id` 与业务表主键一致，唯一键按 `object_value` 去重，允许同 fact_key 多值并存）与 `knowledge_claim_evidence` 关联表（role：SUPPORTS/CONTRADICTS/CONTEXT/RESOLUTION）。
  - 新增 `KnowledgeFactKeyGenerator`：`<projectId>|<businessVersion>|<module>|<normalizedSubject>|<normalizedPredicate>` 确定性生成。
  - `MultiSourceKnowledgeStore` 新增 saveClaim / linkClaimEvidence / findClaimById / findClaimsByFactKey / findEvidenceIdsByClaimId / syncSnapshotClaims（事务内把参数/存疑/测试用例/测试结果批量生成为统一 Claim 并关联 Evidence、回填业务表关联列）。
  - 新增 `MultiSourceKnowledgeClaimTest`：Claim 幂等 upsert、同 fact_key 多值并存、fact_key 规范化、sync 后四类 claimId 可在主库回查版本/状态/Evidence 关联。
- Phase C（关系、冲突与审核审计）：
  - 新增 `knowledge_relation` 统一关系表（状态 `RULE_PROPOSED / LLM_CONFIRMED / LLM_REJECTED / HUMAN_CONFIRMED / STALE`、置信度、evidence、抽取/确认方式与原因）与 `knowledge_extraction_run` 抽取运行审计表（parser/模型/提示词版本/input-output hash/token/状态/耗时）。
  - 新增 `KnowledgeRelationBuildService`：离线/发布前关系生产——规则抽取 + 可选 LLM 确认，结果落 `knowledge_relation` 并记录一次抽取运行；不再在查询侧生成/持久化/调用 LLM。
  - `MultiSourceSearchService` 查询改为**只读预生成关系**并按当前命中页裁剪一跳邻域（新表优先，旧 `multi_source_relation` 只读回退）。
  - 新增人工审核 API `POST /api/knowledge/review/relations/{relationId}`（确认/拒绝/标记过期，`KnowledgeReviewController` + `MultiSourceKnowledgeStore.reviewRelation`）。
  - 新增 `KnowledgeRelationBuildServiceTest`（规则产出/LLM 拒绝保留审计/LLM 确认升级状态）与 `KnowledgeReviewControllerTest`。
- Phase D（发布目录与索引一致性）：
  - 新增 `knowledge_active_version` 表与 `MultiSourceKnowledgeStore.publishDocumentVersion / rollbackActiveVersion / activeDocumentVersion`：project+businessVersion 的 active document-version manifest，支持发布/回滚/按业务版本隔离。
  - `ChunkRecord` 扩展 `documentVersionId / authority / status / evidenceId / factKey`，`QdrantHybridStore` 写入/读取这些 payload 字段（旧构造器兼容）。
  - `QdrantHybridStore.setPayload`：payload-only 批量更新（不重算向量），用于已有点字段回填，避免全量 re-embed。
  - 新增 `MultiSourceKnowledgePublishTest` 与 `QdrantHybridStoreMultiSourceTest.setPayloadUpdatesPointsWithoutReembedding`。
- Immortal 知识导入加载器：
  - 新增 `XlsxTableReader`：轻量 ZIP+DOM 读取 XLSX 多 sheet（表头 + 按列索引行数据），不引入 POI。
  - 新增 `XlsxTestCaseLoader`：把「分组/模块/操作步骤/预期结果」sheet 解析为 `TestCaseClaim`，保留文件/sheet/行号 Evidence，空行跳过。
  - 新增 `ConfigTableLoader`：把第一行为列名的游戏/业务配置表按「行 × 列」生成 `ParameterClaim`（subject=列名、object=单元格值、module=sheet 名），保留行列定位。
  - `DoubtClaimParser` 补 `跟进人 → owner`、`产品答疑 → answer` 别名。
  - 新增 `ImmortalLoadersTest` 覆盖三个加载器。
- Immortal 知识导入编排与缓存：
  - 新增 `ImmortalKnowledgeImporter`：扫描 `document/immortal/{prd,data,qa,case}` 四类目录，按文件注册 Document/Version/Evidence，经各加载器写入业务表并 `syncClaims` 生成统一 Claim；PRD HTML 生成轻量 REQUIREMENT Claim。
  - 内容 hash 缓存：相同 (document, businessVersion, contentHash, parserVersion, extractionVersion) 直接跳过，重跑不再解析/写库（幂等增量）。
  - `MultiSourceKnowledgeStore.syncClaims` 支持按 claim 范围同步，避免多文件导入互相覆盖 catalog 关联；`insertClaim` 改为先按 claim_id 更新、不存在再 `INSERT OR IGNORE`，对完全重复事实静默去重。
  - 新增 `ImmortalImportIT`（`-Dimmortal.import=true` 显式开启）作为本地导入入口；已完成首次导入：参数 779,129、存疑 2,100、测试用例 26,168、需求 123、Evidence 807,520。
- 跨源总实体关系图：
  - 新增 `knowledge_entity` / `knowledge_entity_relation` 表与 `KnowledgeGraphModels`。
  - 新增 `KnowledgeGraphBuildService`：聚合 PRD/DATA/QA/CASE 统一 Claim 为模块级实体，按规范化名称生成确定性关系（SUPPORTS / VERIFIES / RAISES_DOUBT / IMPLEMENTED_BY）；提供 `CodeEntitySource` 代码接入 SPI 与 `LlmGraphExtractor` LLM 语义边扩展点；构建前幂等清空重写。
  - 新增 `GET /api/knowledge/graph` 与 `POST /api/knowledge/graph/build` API。
  - 新增 `KnowledgeGraphBuildServiceTest` / `KnowledgeGraphControllerTest` / `KnowledgeGraphBuildIT`。
  - 代码接入：`SQLiteSymbolGraphStore.allSymbols` 枚举符号，`SymbolGraphCodeEntitySource` 把 `immortal-game-service` 代码符号并入图（知识项目 immortal → 代码项目 immortal-game-service 映射），生成 `IMPLEMENTED_BY` 关系。
  - LLM 语义边：新增 `LlmKnowledgeGraphExtractor`（默认用 `REQUIREMENT_GRAPH_EXTRACTION_MODEL`=deepseek-v4-flash，实体上限 500，失败降级空）；`app.rag.multi-source.graph-llm-enabled=true` 开启后合并语义边；`KnowledgeGraphBuildConfiguration` 完成 Spring 装配。
  - 全量测试：693 tests 通过。

## 0.9.2 — 2026-08-20

### Added

- 第十六轮开发（多源知识生产加固收口：Qdrant 多源过滤 + live alias、真实 Token usage、跨源关系 LLM 语义确认）：
  - Qdrant 多源过滤：`ChunkRecord` 新增 `sourceType`（默认 `REQUIREMENT`，兼容旧构造器），`QdrantHybridStore` 写入/读取 payload `sourceType`，并新增按来源类型过滤的 `hybridSearch`/`hybridSearchWithScores` 重载（单值 `match.value` / 多值 `match.any`）。
  - Qdrant live alias：`QdrantHybridStore` 新增 `publishLiveAlias`（写版本化物理 collection → 校验点数 → Alias 原子创建/swap → 清理旧版本）、`aliasTarget` 与 `rollbackLiveAlias`（回滚到上一物理 collection），在线查询始终读完整版本，失败不切换 alias。
  - 真实 Token usage：新增 `ChatTokenUsageTracker` + `TokenTrackingChatModel`，包装 `ChatClient` 依赖的 `ChatModel`，把模型 API 返回的真实 `TokenUsage`（prompt/completion/total）计入 Micrometer 指标（`rag.tokens.*`，含 stage 标签），监控页 `tokenUsage()` 可聚合真实数据；默认对所有 ChatClient 调用透明生效。
  - 跨源关系 LLM 语义确认：新增 `CrossSourceRelationConfirmer` 接口 + `LlmCrossSourceRelationConfirmer` 实现；`MultiSourceSearchService` 在 `relation-llm-confirmation-enabled` 开启时对规则抽取的关系做 LLM 二次确认，仅丢弃明确判为不成立的关系（拒绝/引用缺失写入 warnings），失败/未解析默认 fail-open 保留规则基线。
  - 新增 Qdrant 过滤/live alias、Token usage、关系 LLM 确认回归测试。
- 第十四轮开发（多源知识生产加固：灰度开关 + LLM 意图回退 + HTTP API）：
  - 新增 `app.rag.multi-source` 配置（`MultiSourceKnowledgeProperties`）：全局总开关 + 按项目灰度开关（`project-enabled`）+ LLM 意图回退开关与模型名；关闭时保留已导入数据，多源检索返回 `NO_RESULT` + `MULTI_SOURCE_DISABLED` 降级响应，默认全关以符合灰度原则。
  - 新增 LLM 意图回退：`KnowledgeQueryIntentLlmFallback` 接口 + `LlmKnowledgeQueryIntentClassifier` 实现，规则分类器无法归类（GENERAL）且开启回退时调用 LLM 细化意图，任何失败/非法输出均降级为规则结果，并在响应 warnings 记录 `intent classified via LLM: X`。
  - 新增多源检索 HTTP API `POST /api/knowledge/multi-source/search`（`MultiSourceKnowledgeController` + `MultiSourceSearchRequest`），支持 projectId/version/query/intent/limit/page，校验项目存在与项目访问权限。
  - 新增灰度开关、按项目关闭、LLM 回退启用/禁用与 HTTP API 回归测试。
- 新增业务项目与代码仓库分层模型：业务项目共享需求、版本和 Wiki，支持多个独立代码仓库、显式公共库引用、项目级权限和多仓库联合检索。
- 新增 Immortal 迁移预览、幂等目录迁移、版本主仓库解析、需求版本落后提示和多仓库 live alias 聚合统计能力。
- 新增需求语义图旁路能力：按业务项目、文档和需求版本生成可审核实体关系快照，保留 evidence 绑定并回查 Qdrant 原文；不替换现有需求主检索链路。
- 新增 GitLab 账号发现、批量仓库导入、受控同步、Webhook 和知识管理运营工作台，并补充真实 RAG 评测与 PageIndex/Vision 扩展能力。

### Changed

- 产品版本统一为 `0.9.2`，同步 Maven、MCP server、前端工作台和 README 展示版本。
- 业务项目检索默认展开全部可用自有仓库及显式引用公共库，代码命中保留仓库、commit 和文件来源。
- 版本清单升级为可保留产品版本、需求基线和多仓库 commit 基线的兼容格式。

### Fixed

- 第十七轮开发（多源知识生产级 Review 整改）：
  - P1 Alias 原子性：`QdrantHybridStore.publishAlias` 的 `swap_aliases` 失败回退与 `rollbackLiveAlias` 改为在单个 `/collections/aliases` 请求的 `actions` 数组中原子提交 delete+create，不再用两个独立请求；任一请求失败时旧 alias 保持可查询，新增“创建失败后旧 alias 仍可查询”集成测试。
  - P1 流式 Token 计量：`TokenTrackingChatModel.stream` 只在订阅开始时计一次 `rag.tokens.requests`，并仅用最后一个携带 usage 的终止分片记录 token，避免按 SSE 分片重复累加请求数与累计式 usage；补充多分片/最终帧/取消/异常四类测试。
  - P1 关系页边界：跨来源关系抽取、LLM 确认与持久化改为严格基于当前命中页 `claims`（两端均在页内）生成，不再用评分前全量候选，避免响应体膨胀、与查询无关的关系写库与无谓 LLM 调用。
  - P2 分页元数据：`MultiSourceSearchResponse` 新增 `total / page / limit / hasMore`，服务端在分页边界、超范围页与 limit 截断上补齐契约测试，HTTP API 与控制器测试同步覆盖。
- 第十五轮开发（旧 `TEST` 数据回填清洗）：`KnowledgeConflictModels.SourceType` 新增 `normalized()` 归一化方法；`KnowledgeConflictService` 在声明规范化阶段把旧 `TEST` 统一回填为 `TEST_CASE`（含自动生成的 claimId 前缀 `test_case:`），并在报告 warnings 记录“已将 N 条旧 TEST 来源声明规范化为 TEST_CASE”，保证新代码路径不再出现遗留 TEST 来源、冲突归类与去重都基于 TEST_CASE。
- 修复需求语义图启用模式下多个构造器未明确 Spring 注入入口的问题；构建和查询开关同时开启时 Spring 上下文可正常加载。
- 修复多仓库代码统计读取基础 collection 而非实际 live alias、导致已发布代码点显示为 0 的问题，并区分不可用与真实零值。
- 将需求语义图从单父块实验升级为受预算约束的结构化窗口构建：记录覆盖率、窗口状态、重试与可恢复结果，支持跨窗口不确定性/关系冲突、证据跨度、声明审核、审计发布、分页和可选混合检索；默认仍不影响 Qdrant 需求主检索。
- 新增 `/requirement-graph.html` 证据优先审阅工作台、异步构建任务状态、声明审核/邻域/路径 API、项目级隐私策略开关、合成评测质量门和构建/检索指标；旧同步构建接口保持兼容。
- 声明审阅新增显式通过/驳回/合并/拆分操作及不可变发布门禁；构建任务支持取消、恢复和窗口级结果复用。
- Code Review 整改：异步任务改为 SQLite 持久化（重启后仍可查询/恢复/取消，启动时标记中断任务）；取消/失败时保留快照 ID 并支持按 buildId 找回已创建快照；发布门禁校验 VERIFIED 声明引用的 `source_evidence_ids` 必须真实存在（新增 `GRAPH_EVIDENCE_MISSING` 阻塞码）；Embedding 失败后回写快照 warning 数；本体校验由“默认放行”改为显式 source/target 矩阵并对未知关系报 `GRAPH_SCHEMA_INVALID`；终态任务按 7 天保留并自动清理。
- 第二轮 Code Review 整改：删除 `SearchRequest`/`SearchResponse` 中与 record canonical constructor 重复/递归的显式构造器（解除 P0 编译阻断）；`MIX` 模式现在与 `HYBRID` 一起路由到 `RequirementGraphHybridSearchService`；`RequirementGraphQueryPlanner` 注册为 Spring Bean 并在 Controller 中生成 `QueryPlan`、回填到响应、缺失 mode 时自动推断；重启恢复会按 `build_id` 回填已创建快照 ID；异步构建按窗口实时回写 `completedWindows/totalWindows` 进度。
- 第三轮 Code Review 整改：`MIX` 升级为真正的多通道混合检索（Qdrant 文本块 + 实体 + 关系 + 一跳/多跳路径 + 证据），按可配置权重 `app.rag.requirement-graph.fusion.*` 加权融合并在响应中保留各通道得分（`channelScores`）；`SearchResponse` 新增 `sourceChunks`/`paths` 由真实检索结果填充；新增 `NAIVE` 纯文本块检索模式并把五种模式统一到 `Controller → QueryPlanner → HybridSearchService` 入口；新增规范化 `requirement_graph_claim_evidence` 关联表，草稿重建时同步重建、删除证据级联清理、发布门禁优先基于该表校验证据完整性（旧快照回退读取 JSON `source_evidence_ids`）。
- 第四轮 Code Review 整改（多版本数据隔离与检索语义）：
  - Window/WindowResult 改为 `(snapshot_id, id)/(snapshot_id, window_id)` 复合主键并补充旧库自动迁移，同一需求多次构建不再因全局窗口 ID 冲突失败。
  - Evidence 改为 `(snapshot_id, evidence_id)` 复合主键，Claim→Evidence 外键同步改为复合外键；旧发布快照与新草稿可独立保存相同证据。
  - 每个 SQLite 业务连接显式开启 `PRAGMA foreign_keys=ON`，级联删除与引用完整性真正生效；新增原子 `saveDraftSnapshot`（快照→证据→实体/关系→Claim→Evidence→不确定性/冲突单事务写入，证据先行满足外键顺序），并新增快照级联删除。
  - 修复 MIX 文本与图通道 ID 空间不一致：通过父块 `filename|parentId|parentOrder|contentHash` 把 Qdrant 命中文本块关联到同父块的 span Evidence，文本命中真正参与实体/关系融合排序，并补排序回归测试。
  - 文本检索通道不再把故障伪装成空结果：区分 `GRAPH_TEXT_NO_HITS`/`GRAPH_TEXT_RETRIEVAL_UNAVAILABLE`/`GRAPH_TEXT_RETRIEVAL_TIMEOUT` 并显式返回 warning。
  - `QueryPlan` 真正驱动 MIX 检索（状态集合、hops、各通道上限、实体/关系关键词、章节关键词），并移除无调用方的 `planMIX()`。
  - MIX 统一召回、融合、稳定排序后一次性分页（跨文本/实体/关系/路径/证据通道），避免跨页重复与漏 Evidence。
  - `maxEstimatedTokens` 生效：达到 Token 预算后停止后续模型调用。
  - 无快照的 QUEUED 任务重启后可按原请求重新排队恢复；取消期间模型调用抛普通异常时不再把已持久化的 CANCELLED 覆盖为 FAILED。
- 第五轮开发（改进方案 Phase 0/1 核心）：
  - 已发布快照只读：`build`/`resume`/全部图数据写入口拒绝修改 `PUBLISHED` 快照，新增 `GRAPH_SNAPSHOT_IMMUTABLE`；`VERIFIED/REVIEW_REQUIRED` 不可作为恢复目标。
  - buildId 与 snapshotId 解耦：schema v2 快照 ID 不再包含 buildId，改为内容/配置身份；相同输入重复构建幂等复用同域快照，兼容旧库按业务唯一域复用旧 ID，修复同输入重建时的唯一约束冲突。
  - 新增确定性关系质量门禁：拒绝自环关系和重复关系，先校验原文证据再进入图谱。
- 第六轮开发（统一 span Evidence + 检索解释）：
  - 统一 span Evidence：检索层不再伪造父块级 Evidence ID，不再为缺失 Evidence 创建空字段占位对象；只返回实体/关系真实引用的 span Evidence，缺失仅通过 `GRAPH_EVIDENCE_UNAVAILABLE` warning 体现，旧父块 Evidence 仍按兼容读取。
  - 检索解释：`SearchResponse` 新增 `explanations`，MIX 每个返回候选附带命中通道（`matchedChannels`）、分数明细（`scoreBreakdown`）、关联 Evidence 与可读解释。
- 第七轮开发（多源需求知识 Phase 0 元数据兼容层）：
  - 扩展 `KnowledgeConflictModels.SourceType`：新增 `TEST_CASE / TEST_RESULT / PARAMETER_TABLE / DOUBT`，旧 `TEST` 保留并兼容映射为 `TEST_CASE`（`SourceType.normalize`）。
  - 扩展 `Authority`：新增 `SECONDARY`（验证/实现证据），与 `PRIMARY / DERIVED` 并列。
  - 新增 Trellis 任务 `multi-source-requirement-knowledge`，将多源知识实施计划拆分为 Phase 0-5 可勾选清单。
- 第八轮开发（多源需求知识 Phase 1 结构化解析层）：
  - 新增 `ParameterTableLoader`：表头别名识别、数值类型化（INTEGER/DECIMAL/PERCENTAGE/DURATION/COUNT/BOOLEAN/ENUM/TEXT）、单位/范围/精度/边界保留、行列位置与 `factKey` 生成。
  - 新增 `DoubtClaimParser`：从行级数据生成结构化 `DoubtClaim`（状态/负责人/严重级别/备选方案/Evidence 位置），默认 `OPEN`。
  - 新增 `MultiSourceKnowledgeModels`：`KnowledgeQueryIntent / KnowledgeStatus / ParameterValueType / DoubtStatus / ParameterClaim / DoubtClaim`。
  - 新增解析器回归测试（参数别名、类型化、版本、Evidence 位置、存疑状态）。
- 第九轮开发（多源知识意图路由基础）：
  - 新增 `KnowledgeQueryIntentClassifier`：规则优先识别 `NORMATIVE / VALIDATION / PARAMETER / DOUBT / CONSISTENCY / IMPACT / GENERAL`。
  - 新增 `MultiSourceKnowledgeGate`：状态门禁（`REJECTED/STALE/OBSOLETE` 默认不返回）+ OPEN/UNDER_DISCUSSION 存疑仅在 DOUBT 意图下进入结果，RESOLVED 存疑可返回。
  - 新增意图分类与门禁回归测试。
- 第十轮开发（多源知识 Phase 1 存储接入）：
  - 新增 `MultiSourceKnowledgeStore`：SQLite 持久化参数 Claim 与存疑 Claim（upsert、按项目/版本查询、幂等重导）。
  - 参数保留单位/范围/精度/边界/Evidence 位置；存疑保留状态/负责人/严重级别/备选方案。
  - 门禁已接入存储读取：OPEN/UNDER_DISCUSSION 存疑不会进入普通规范查询结果。
  - 新增存储与门禁集成回归测试。
- 第十一轮开发（多源知识 Phase 2–5 核心闭环）：
  - Phase 2：新增 `TestCaseClaim / TestResultClaim` 与 `TestKnowledgeLoaders`（JSON/JSONL 用例、JUnit XML 结果导入），存储新增测试用例/结果表与关联。
  - Phase 3：新增 `UnifiedKnowledgeClaim` 统一视图、`MultiSourceConflictAnalyzer`（需求-参数、参数-测试、测试结果-预期冲突）+ 结论状态解析。
  - Phase 4：新增 `SourceFilterStrategy`（按意图过滤来源）与 `MultiSourceSearchService`（意图分类 → 读取结构化知识 → 来源过滤 + 存疑门禁 → 关键词召回 → 冲突分析 → 结论状态与解释）。
  - Phase 5：新增 `src/test/resources/evaluation/multi-source-golden.jsonl` Golden Dataset 与 `MultiSourceGoldenEvalTest` 离线评估（参数/验证/存疑/无结果四类断言）。
  - `MultiSourceKnowledgeStore` 注册为 Spring Bean；全量多源链路（解析→存储→检索→评估）可离线运行。
- 第十二轮开发（多源知识 Code Review 整改）：
  - P1：REQUIREMENT 来源经 `RequirementGraphCandidateAdapter` 接入（已发布/已审核语义图实体/关系投影为统一 Claim）。
  - P1：新增 `CrossSourceRelationExtractor` 与跨源关系模型（TEST_CASE->VERIFIES、PARAMETER_TABLE->SUPPORTS、DOUBT->RAISES_DOUBT）。
  - P1：冲突分析按 `subject|predicate` 兜底分组对齐 factKey；旧 `KnowledgeConflictService` 识别 `TEST_CASE/TEST_RESULT`。
  - P1：多源检索改为字段加权评分 + 冲突惩罚 + 稳定排序 + Top-K 分页；中文无空格查询按 2-gram 分词。
  - P1：JUnit XML 改为 DOM 解析，区分 `PASSED/FAILED/SKIPPED/ERROR`。
  - P2：Claim 状态纳入领域模型并应用到 `MultiSourceKnowledgeGate`；`CONSISTENCY` 意图返回 OPEN 存疑；意图分类器修复中文一致性短语；多源重导改为事务性 `replaceSnapshot`；参数 Evidence 列范围指向真实数据行。
- 第十三轮开发（多源知识 Code Review 第二轮整改）：
  - P1：跨来源关系接入生产链路：`MultiSourceSearchService` 生成关系、`MultiSourceKnowledgeStore` 持久化（`multi_source_relation` 表）、`MultiSourceSearchResponse` 返回 `relations`。
  - P1：关系目标必须真实 Claim：未匹配到需求时不再伪造 `req:xxx`，改输出 `unresolved` 原因。
  - P1：REQUIREMENT 适配器保留实体/关系 `claimStatus` 与 `sourceEvidenceIds`，仅 VERIFIED 且可回查 Evidence 的 Claim 进入规范来源。
  - P1：新增 `CodeKnowledgeCandidateAdapter`（接入 `CodeKnowledgeService`，投影符号为 CODE 统一 Claim）。
  - P2：测试用例/结果 `status` 持久化（表新增 status 列 + 自动迁移，读写状态）。
  - P2：参数生效版本按 `claim.version()` 写入并校验一致，避免版本被导入版本覆盖。
  - P2：冲突惩罚改用冲突分组 `Set`，与 `conflictGroups` 对齐；冲突范围与分页结果一致（按当前页 Claim 计算状态）。

## 0.9.1 — 2026-08-18

### Added

- 新增 `docs/code-knowledge-base-value-and-validation-plan.md`，汇总代码与研发知识库可沉淀内容、证据链方向、RAG/结构化存储分工、MVP 方案、对照实验和提效验收指标。
- 新增 `/settings/gitlab` 可视化管理工作台：支持关联多个 GitLab PAT 账号、分页发现账号
  实际参与的项目、搜索多选、逐项目调整 `projectId`/分支/collection 并批量导入；同时保留
  已导入项目详情、revision drift、旧索引可用性、任务时间线、Webhook 状态及 Secret
  一次性轮换，PAT 与 Webhook Secret 不进入 URL 或浏览器持久化存储。
- 新增 `/knowledge` 知识管理工作台，提供知识库概览、文档列表、文档处理阶段轨道、
  分块检查抽屉、失败重试和复用正式链路的检索测试；页面使用内置 Vue WebJar、
  服务端分页和可见性自适应轮询，不引入外部前端构建链。
- 新增知识库、导入任务、文档、分块和阶段事件的分页查询 API，并提供项目级重建、
  文档/分块重试及复用正式混合检索与重排链路的检索测试 API；响应保留降级诊断，
  同时截断正文并移除向量、异常原文和服务器绝对路径。
- 知识导入 Bootstrap、逐文档清洗/分块/去重及 Qdrant 嵌入/索引/验证/发布阶段已接入持久化状态目录，可查询真实文件与分块进度；状态写入失败仍不影响原索引主流程。
- 新增 RAGFlow 风格知识管理状态基础设施及回归测试：SQLite 持久化知识库、导入任务、文档、分块与阶段事件，提供稳定 ID、分页契约、公开错误脱敏和应用重启中断恢复；状态目录作为旁路能力，不改变 Qdrant 正文与向量存储。
- 新增 GitLab 项目自动接入 MVP：SUPER_ADMIN 管理 API、AES-256-GCM 凭据加密、独立 SQLite 元数据与 Webhook 去重、受控 HTTPS clone/fetch/checkout、动态项目注册、项目级无丢失串行队列、首次全量与快进增量索引、失败目标原位重试、`lastIndexedSha/targetSha` 状态、原生 `X-Gitlab-Token` Push Hook；默认由 `GITLAB_INTEGRATION_ENABLED=false` 关闭，并提供简体中文接入指南。
- 新增 GitLab 账号连接、REST API 项目发现和批量导入契约：新项目只引用账号连接并共享一份
  加密 PAT，旧项目继续兼容项目级密文；GitLab API 与 Git Clone 共用 Host 白名单和私网限制，
  批量导入逐项目返回成功/失败并立即进入首次同步队列。
- 新增真实 RAG 企业评测基线：版本化 JSONL v2 数据契约、24 条拾光冻结用例、稳定 evidence ID、人工审核与 Git commit provenance、nDCG@10/唯一用例降级率指标、版本化质量阈值和可执行发布门禁；提供 `tools/run-real-rag-evaluation.sh` 与简体中文执行指南，默认 CI 仅运行无外部依赖的契约和指标测试。
- 新增 `docs/gitlab-project-integration-implementation-plan.md`，定义 GitLab 项目发现、受控仓库同步、异步索引、Webhook 幂等、权限与迁移方案。
- 新增 NEXUS 企业化收口 Trellis 父子任务，拆分发布验证、真实 RAG 评测、GitLab 自动接入和多实例共享状态四条可独立验收的工作流。

### Changed

- 需求 Multipart 上传现在复用知识管理运行追踪，记录 `UPLOAD` 来源、文件/分块阶段和成功/失败结果；业务项目需求重建与重试分离仓库配置 ID 和业务项目状态 ID，避免把主仓库身份当作需求归属。
- 知识管理页新增 `UNAVAILABLE` 需求状态，Qdrant 不可用不再伪装成真实零分块；前端筛选和状态契约同步支持该状态。
- LightRAG 暂不替换现有需求检索链路。现有 dense+sparse、版本过滤、证据白名单和 Qdrant 原子发布继续作为生产主链路；LightRAG 仅保留为后续跨文档实体/关系图谱的离线对照或旁路增强候选。
- 新增默认关闭的需求语义图 MVP：按业务项目/文档/需求版本保存 LLM 实体关系草稿，强制绑定原文 evidence ID，支持 SQLite 原子快照、Local/Global 查询、Qdrant 证据回查和审核后发布；不改变现有需求检索主链路。
- 修复需求语义图启用模式下多构造器未明确 Spring 注入入口的问题；本地打开构建和查询开关后可正常加载上下文。
- 首页运行状态监视从已停用的 Ollama/BGE 本地依赖更新为 Qdrant、当前 OpenAI 兼容 API 配置模型和 GitLab 连通性；模型探针会核对所有已配置模型，外部探针并行且保持短超时。
- 代码语义标注模型的默认配置由残留的 `claude-opus-5` 对齐为已验证的
  `gpt-5.6-sol`；仍可通过 `ANNOTATION_MODEL` 环境变量覆盖。
- 五个核心页面统一为 NEXUS 知识与代码运营工作台外壳，新增共享设计 Token、完整桌面/移动导航、项目上下文、连接设置、通知和错误规范化基础设施；代码工作台改为统一浅色图谱工作区，版本比较从核心入口移除并兼容保留旧路由/API。
- 知识管理页新增“类型”筛选（全部/需求/代码），并在列表中同时展示需求文档索引与代码索引状态。
- 需求文档导入改为结构感知分块：检测 Markdown 标题后按章节切分父块，并在父块/子块中保留“章节路径”前缀（如 `【章节: 访问控制 / 项目授权撤销】`），无标题文本自动回退原固定窗口逻辑；`ParentChildChunker.split` 保持历史行为，既有评测语料契约不受影响。
- 需求分块新增结构化元数据并持久化到 Qdrant payload 与检索测试响应：`sectionPath`（章节路径）、`heading`（当前标题）、`requirementId`（需求编号，保守正则抽取）、`module`（模块=章节首段）、`acceptanceCriteria`（验收标准，紧随“验收标准/验收条件”后最多 5 行保守抽取）；前端检索测试结果可直接看到“需求编号 / 章节路径 / 验收标准”。旧 `ChunkRecord` 构造器保持兼容，历史存储无字段时回退为空。
- 需求文档清洗不再静默丢尾：`TextPreprocessor` 新增 `cleanWithDiagnostics` 返回 `truncated/keptLines/consideredLines`，导入触发 `120_000` 字截断时记录 WARN 日志、发出 `text.clean.truncated` 事件，并把被截断来源写入 `IngestResponse.truncatedSources`（导入 API 返回解析质量诊断）。
- 需求解析健壮性：HTML 加载按 BOM/meta charset 自动识别编码（兜底 UTF-8）；分页噪声过滤收紧为“第 N 页 / 第 N 页 共 M 页”，避免误删正文；XLSX 加载支持表头映射“模块/问题/解答/答案”列，不再写死前三列。
- 新增 `RequirementImageCaptioner` 图片内容理解扩展点：Spring 中可注入 OCR/Vision 实现，解析时把图片字节和 alt/图注传给实现，返回的中文描述追加到图片占位符中；未配置实现时保持原占位符行为。
- 新增版本级差量导入入口 `RequirementIngestionService.ingestIncremental`：按 source sha256 过滤出新增/变更条目，只重新解析并向量化变化部分，未变化条目跳过；无变化时不触发向量库写入。
- 知识导入链路新增可观察的 Qdrant 批次阶段回调和运行级分块进度，保持“写入新点、验证成功、再清理旧点”的发布顺序不变。
- `ProjectRegistry` 支持线程安全动态注册与卸载，静态项目保持优先且继续作为默认项目，现有静态配置和旧 GitLab Webhook 行为不变。
- 重构 `tools/verify-report.sh`：结构化读取 Maven 版本，使用独立临时文件和 Surefire XML 汇总测试结果，并通过 `clean verify` 避免旧构建产物污染报告。
- 封神代码召回对比升级为四向文档 `docs/fengshen-code-retrieval-four-way-comparison.md`：NEXUS 指标更新为 E10 最新（Recall@1 93.6% / Recall@10 99.6% / MRR 0.9596），并纳入 `codebase-memory MCP（BM25）` 结果（Recall@10 92.0% / MRR 0.8348 / P50 24ms）；旧三方文档保留并标记为历史版本。
- 归档已经随 `0.8.6` 交付的历史 Trellis 任务，使任务状态与发布事实一致。
- 重新生成 `0.8.6` 机器验证报告：JDK 21、430 个测试、JaCoCo 门禁和可执行 jar 全部通过；`clean` 清除了旧 `target` 中 6 条失效测试报告，修正了历史统计虚高。
- 本地运行时 SQLite 数据库（`data/*.db`）纳入 `.gitignore`，避免本地状态库进入发布提交。

### Fixed

- 修复业务项目多仓库代码状态统计回退默认 collection、图谱/源码/影响分析回退默认仓库、代码命中缺少仓库来源，以及 `ImageUnderstandingService` 未实现 `RequirementImageCaptioner` 的问题；ZIP HTML、图片索引和解压读取增加单条目、总量与数量上限。
- 修复需求差量导入只提交变化来源导致未变化来源向量被删除的问题：新增来源级 Qdrant 局部替换并处理来源删除。
- 修复多仓库业务项目在图谱、符号图、影响分析和源码读取未传 `repositoryId` 时静默回退默认仓库的问题；多仓库歧义请求现在明确拒绝，单仓库范围可安全解析。
- 修复多仓库代码检索部分失败被标记为成功的问题，返回 `DEGRADED`、`CODE_REPOSITORY_PARTIAL_FAILURE` 和失败仓库 ID；代码统计不可用时显示 `UNAVAILABLE` 而非真实零值。
- 修复 ZIP Vision 导入绕过数量、并发和超时配置的问题，文档级图片 caption 现在受 `max-images-per-doc`、`concurrency` 和 `timeout-ms` 约束。
- 修复 GitLab 账号导入的稳定性与安全边界：临时 429/5xx/超时不再把连接永久标记为
  `INVALID`；远端项目按“连接 + GitLab 数字 ID”判重并支持项目改名和多实例同路径；
  批量导入并发读取项目详情且不再同步执行最长 120 秒的 Git 预检；并发注册使用原子插入，
  不会覆盖或删除另一请求的成功项目；连接 PAT 只允许发送到相同 Host/端口；项目搜索词
  下推 GitLab API，可在超过 10,000 项时真正缩小发现范围。
- 修复知识库 Qdrant 兜底按 collection 总量误报 READY、状态筛选覆盖真实 FAILED/RUNNING、超过 200 条真实状态丢失，以及 CODE 重建错误调用需求导入的问题。
- 修复 GitLab 向导退出后仍保留 PAT/Secret/验证结果，以及共享导航在页面内切换项目或版本后继续携带旧上下文的问题。
- 修复知识管理页在状态库为空但 Qdrant 已存在历史知识时显示“空知识库”的问题：列表与详情增加 Qdrant 兜底投影，已索引需求文档会以 READY 状态直接展示；同时支持 `CODE` 类型，代码索引情况也会显示在知识库列表中。
- 修复知识库“检索测试”对代码类型知识库仍只检索需求文档的问题：后端按知识库类型选择检索画像（`CODE_RETRIEVAL`），响应新增 `codeHits` 返回代码文件路径、符号、行号与摘要；前端在代码知识库中展示代码命中及对应的空状态提示。
- 修复知识库“检索测试”缺少返回入口、运行按钮被窄列挤压换行的问题；检索表单改为稳定的查询/操作分区，代码命中明确展示 Git commit SHA，并说明检索范围是项目当前已发布代码索引。
- 修复共享顶部上下文栏与页面面包屑层级顺序相反的问题；统一按“页面 / 项目 / 版本”展示，并保持当前页面为强调项。
- 为知识管理页静态资源增加版本号参数，避免浏览器缓存旧 JS 导致“类型筛选”等新功能不生效。
- 修复共享 API 客户端对 `Content-Type` 大小写去重不完整，浏览器合并重复媒体类型后导致代码图谱搜索返回 415 `Unsupported Media Type` 的问题。
- 修复 `.env` 行内注释被并入 API 嵌入模型名、导致代码搜索向网关发送错误模型标识的问题，并在启动期拒绝包含空白字符的模型名。
- 修复 GitLab 后端集成未启用时 `/settings/gitlab` 路由未注册、统一导航进入管理页直接返回 404 的问题；页面可见性现在仅由 `GITLAB_UI_ENABLED` 控制。
- 修复开发方案工作区仍使用深色卡片、深色链路容器和蓝青渐变按钮，与统一浅色代码工作台视觉割裂的问题。
- 修复 Qdrant 未启动时监控页统计触发 collection 初始化重试、导致页面长时间加载且轮询请求堆积的问题；监控统计改为快速只读探测，前端依赖不可用时降频至 15 秒并禁止重入。
- 修复 GitLab 接入向导步骤编号中的比较表达式被浏览器误解析为 HTML 标签，导致步骤条显示 `span="">` 文本的问题。
- 修复知识全量重建发布后仍保留旧文档/分块状态、GitLab 项目停用后同步任务永久停留在 `QUEUED`，以及 Qdrant 中间批次把未处理分块误计为已排除的问题。

- 修复 GitLab 自动接入安全与恢复边界：PAT 仅可发送到精确 Host 白名单中的 GitLab，默认拒绝
  IP 与内网解析地址；最新 HEAD 同步会清除旧 `targetSha`，失败重试不再误用旧成功提交；
  应用重启后自动恢复 `PENDING/CLONING/SYNCING/INDEXING` 中断任务。
- 修复发布验证报告的可追溯性：脏工作区不再签发归属于 `HEAD` 的报告，Surefire XML
  缺失或损坏时仍会生成包含 Maven 原始退出码和解析状态的失败报告。
- 恢复历史 Trellis 任务的真实完成日期，并使用独立 `archivedAt` 字段记录归档日期。

## 0.8.6 — 2026-08-16

### Added

- 新增自进化 RAG M1-M4 受控闭环（`docs/self-evolving-rag-implementation-plan.md`）：新增 `evolution` 模块，打通在线经验采集、失败挖掘、人工审核评测集、离线实验、策略注册表与 Promotion Gate；默认关闭，不改变现有检索行为。
  - 经验采集：`RetrievalExperienceRecorder` + JSONL `FileRetrievalExperienceStore`，`AgenticOrchestrator` 逐 hop 记录策略/候选/反思/状态/版本，异步写入、采样、脱敏、失败隔离。
  - 失败挖掘：`RetrievalFailureMiner` 按稳定 `FailureType` 从经验事件生成待审核候选，按 queryHash+failureType+indexVersion 聚类去重。
  - 评测集演进：`EvaluationCaseReviewService` 候选状态机（DRAFT→IN_REVIEW→APPROVED/REJECTED→PUBLISHED/ROLLED_BACK），`EvaluationDatasetRegistry` 发布不可变数据集版本并支持回滚。
  - 离线实验：`EvolutionExperimentRunner` + `RetrievalMetrics` 在同一数据集上运行基线和候选策略，输出 Recall/MRR/nDCG/延迟报告。
  - 策略治理：`RetrievalPolicyRegistry`、`PolicyLifecycleService`、`PolicyPromotionGate`、`PolicyDrivenRetrievalStrategySelector`，参数 allowlist 校验、不可变版本、原子激活引用。
  - REST：`EvolutionController` 提供候选审核、数据集、策略与实验 API。
- 新增 `docs/nexus-0.8.5-development-roadmap.md`，规划全系统 RAG 加固：统一检索与重排、错误降级与超时、跨 REST/SSE/MCP 的证据闭环、代码索引可回滚升级、大文档 Map-Reduce 与全链路质量门。
- 新增 `docs/nexus-open-source-rag-engine-comparison.md`，基于 GitHub 最新数据对比 RAGFlow、LlamaIndex、LightRAG、GraphRAG、PageIndex、Graphiti、Haystack、RAG-Anything、R2R 等核心 RAG 引擎，并给出 NEXUS 的集成与选型建议。
- 新增 `AnnotationCacheStore`：代码语义标注结果按项目磁盘持久化（JSONL 追加），全量索引与失败重试时磁盘缓存优先、live 与旧物理 collection 缓存补漏，避免重复调用 LLM 标注。
- 完成封神需求文档与代码评测集的 LightRAG/NEXUS 对比复测，补充 NEXUS 的 Recall@1/5/10、MRR@10、平均首命中排名、空召回率、P50/P95 延迟及可复现评测报告。
- 新增 `docs/fengshen-code-retrieval-three-way-comparison.md`，汇总 LightRAG、NEXUS、RAGFlow 的封神代码召回、排序、延迟、数据质量与选型分析。
- 按 `docs/code-recall-at1-improvement-plan.md` 实施代码检索 Recall@1 改进并完成评测（含两轮评审整改）：基线 A3（新索引+开关全关）Recall@1 64.0% / MRR 0.764 → 实验 E6（开关全开·最终）**Recall@1 93.6%（468/500）/ Recall@5 99.6% / Recall@10 99.6% / MRR 0.9596 / nDCG@10 0.9688**（+29.6pp / +0.196 / +0.161；SYMBOL 与 BUSINESS_TERM Recall@1 均为 100%；185 条排名变化中 157 条升至 Top1、9 条从 Top1 掉至 2-5 位——类内语义排序的代价，Recall@5 仍 +7.4pp；P50 334ms / P95 436ms 达标；报告 `target/fengshen-retrieval/nexus-code-report-{A3,E6}.json` + 对比脚本 `tools/compare-code-reports.py`）：
  - 新增 `CodeQueryAnalyzer`：确定性解析查询中的 `ClassName.methodName`、引号内方法名、「在 X 中/应召回 X 的」类名句式与显式 Java 文件路径，输出 `ParsedCodeQuery`（EXACT_SYMBOL / CLASS_SCOPED / GENERIC），不调用 LLM。
  - 新增精确符号快速通道：`SQLiteSymbolGraphStore.findExactSymbols`（类符号与方法符号同文件连接查询）命中时按行范围从仓库源文件确定性构建 chunk 并置顶，混合检索结果按 `filePath+symbolName+startLine` 去重追加，保证 Recall@10 不退化；SQLite 缺失或异常自动回退混合检索。
  - 新增类名限定召回：`SQLiteSymbolGraphStore.classFilePaths` 解析类文件范围后执行 payload `filePath` 范围过滤的 Qdrant 混合检索（`CodeQdrantStore.classScopedSearch`），类内命中置顶，服务「在 XxxService 中由哪个方法实现」类查询。
  - 结构化重排增强（`CodeQdrantStore`）：类名命中 +0.80、方法名精确命中 +0.50、完整 `ClassName.methodName` 命中合计 +1.50、文件名与类名一致 +0.50；稳定排序键增加 `exactMatchLevel`（2=类+方法/1=仅类/0=无）、filePath、startLine；`toChunk` 补反序列化 payload `className`。
  - 新增消融开关：`app.rag.retrieval.code-exact-symbol-enabled` / `code-class-scoped-enabled` / `code-structural-rerank-enabled`（默认开启，纳入检索指纹），支持基线 A 与实验 B-F 的独立关闭对比；类名限定召回带守卫式并集：全局精排已含目标类方法时快速路径直接返回（单次查询），否则类文件范围查询补召回（全局顺序优先）并统一重排 + 方法/构造器优先于容器类 chunk。
  - 收益归因（评审整改后实测）：E4 相对 A2 的 +25.4pp Recall@1 主要来自精确符号通道（97 条连续 `Class.method` + 26 条引号方法名置顶）与结构化重排增强；类名限定召回通道在评测集上因守卫规则基本不触发，定位为真实场景的召回保险，深层语义排序（2 条类内语义难题）留待 BGE 重排阶段。
  - 评测数据核查：500 条评测 = 125 个唯一目标 × 4 模板；全部查询含 className、410 条含 symbolName；151 条排序失败中 97 条含完整 `ClassName.methodName`；确诊 8 条查询（2 个目标）因默认排除规则 `/build/` 的子串匹配误伤包目录名为 `build` 的 127 个源码文件而缺失，已从默认排除列表移除 `/build/` 并全量重索引修复。
  - 新增单元测试：`CodeQueryAnalyzerTest`、`CodeExactChannelTest`（钉位/重载稳定排序/回退/开关/类名限定/图异常降级/快照版本源码/dirty-worktree）、`SQLiteSymbolGraphStoreTest`（精确查找/同文件双类/类文件路径）、`CodeQdrantStoreTest`（结构重排增强与旧行为兼容/方法优先稳定重排/并集/守卫快速路径/`match.any` 请求体断言）、`CodePathFilterTest`，全量 409 测试通过。


### Changed

- 重写拾光检索评测集：42 个代码用例全部改为拾光业务实现问题，覆盖登录注册、短信验证码、笔记生命周期、评论、关注、搜索、上传、推荐与 AI 对话；移除问题和需求语料中的 NEXUS/BGE/Qdrant/Wiki 平台契约，更新冻结 SHA-256，并用合法的零值关闭 runner 检索缓存。
- OpenAI 网关连通性加固：禁用 okhttp 空闲连接池复用（nginx 侧 keep-alive 超时后连接半开，embedding 请求无限挂起），embedding 读超时 20s 且失败重试 5 次（2s 起指数退避）；Embedding 批大小 32→8（`text-embedding-v4` 上游批上限为 8）。
- Qdrant 兼容性：验证点 ID 改用 `has_id`（≥1.13 拒绝 `$point_id`+`match.any`）；`swap_aliases` 400（本机 Qdrant 1.15.4 untagged enum 解析失败）时回退 delete+create；alias 与遗留物理 collection 冲突自动清理后重试。
- 重写 `docs/nexus-technology-selection-comparison.md`，将 NEXUS 定位为 AI 研发提效与决策辅助平台，按企业知识助手、研发协作助手、代码上下文助手和编码执行 Agent 分层比较 Glean、Rovo、Copilot、Sourcegraph、Qoder、DeepWiki 及国内同类产品，明确自建与复用边界。

- Phase 2 核对收口：错误/降级/超时治理基础设施（7 阶段稳定 warning code、核心失败 503 + `outcome=FAILED`、非关键失败 DEGRADED 保留候选、零命中 NO_RESULTS、SSE warning/error 事件、MCP DEGRADED）经代码核对与既有测试确认齐备；新增 `docs/retrieval-status-contract.md` 作为状态语义与 warning code 的权威注册表（REST/SSE/MCP 三入口映射 + 15 个稳定 code + 安全约束）。
- Phase 3 证据闭环补齐跨域验收样例：Module Wiki 需求证据接入（`ModuleKnowledgeBuildService` / `ModuleStaleRebuildService` 经 `RetrievalPipeline` WIKI_BUILD profile 检索需求，绑定 `requirement:N` 证据与「关联需求」Claim；需求检索不可用时不阻断模块构建）；质量门适配需求证据（commit/跨项目校验仅适用于代码类证据，`requirement`↔REQUIREMENT 前缀映射）。
- Phase 4 核对收口：Java AST shadow 差异报告（`JavaAstStructureShadowTest`）——同一 fixture 对 Tree-sitter AST 主扫描器与旧正则扫描器做符号差异对比，覆盖 record / 方法重载 / 嵌套类型 / 注解 / 继承 / 实现 / 构造器，断言 AST 全结构识别且差异报告精确暴露旧解析器结构盲区（record 完全漏检，嵌套类与方法不夸大）；索引发布回滚（版本化 collection + alias 原子切换 + 校验失败不发布）、影响分析三类关系置信度（静态/启发式/未解析）与索引并发互斥经代码核对与既有测试确认。
- Phase 5 大文档覆盖收口：需求正文上下文按模块轮转切片（`EvidenceRegistry.requirementContextSlice`）——预算内每模块至少保留一条代表块，预算用尽的省略块计入覆盖报告（纳入/省略块数、覆盖模块数），不再静默丢弃后部模块；`DevelopmentPlanService` 在省略发生时输出 `CONTEXT_TRUNCATED` warning + DEGRADED 诊断并复用到提示文本。新增切片覆盖与无预算两个测试。
- Phase 6 文档收口：`docs/user-guide.md` 新增「检索状态与降级语义」章节（四态 × 三入口、warnings 结构、`CONTEXT_TRUNCATED` 与注册表指引），`docs/retrieval-status-contract.md` 作为权威契约；`docs/verification/` 固化机器可读验证报告。
- 检索质量门禁（`RetrievalQualityGateTest`）扩展为全 profile 确定性回归：48 条 HIT 用例（REQUIREMENT_REVIEW / DEVELOPMENT_PLAN / WIKI_BUILD）断言文档与代码黄金命中不被确定性逻辑误杀；6 条 NO_RESULTS 用例断言空语料必须返回 `NO_RESULTS`（不虚构命中）。随 CI 默认执行，不依赖 Qdrant/Embedding/BGE。

- Wiki 过期检测（`WikiStalenessService`）升级为符号级传播：Git diff 变更文件 → 变更符号（`symbolsByFiles`）→ 入向调用关系（`relations`）→ 命中页面 `codeSymbols` 引用的符号 → 标记页面 STALE 并在原因中展示「变更符号 -> 页面符号」传播链；文件未命中但调用关系被变更触及的页面同样失效。

- 模块发布质量门补齐四条硬约束（`ModuleClaimQualityGate`）：必须含至少一条真实 CODE 证据；全部证据 commit 与目标代码提交一致；证据文件存在于仓库且行号不越界（fail-closed）；任何 CONFLICT 声明阻止发布。新增对应回归测试（真实代码证据/跨 commit/文件缺失/行号越界/CONFLICT 拦截）。
- `ModuleStaleRebuildService` 加固：重建目标必须是 MODULE 页面且必须出现在当前 StaleReport 中（失效检测基础设施不可用时拒绝重建）。
- 模块发布质量门加固（`ModuleClaimQualityGate`）：目标代码提交与每条代码证据 commit 缺失/不一致均拦截（fail-closed，不允许空值绕过）；CODE 证据的符号必须仍存在于当前符号图快照；Claim 证据 ID 的 namespace 前缀必须与实际证据类型一致（`code`↔CODE、`code-graph`↔CODE_GRAPH、`route`↔ROUTE、`dependency`↔DEPENDENCY、`data`↔DATA、`config`↔CONFIG、`test`↔TEST_SYMBOL、`diagnostic`↔DIAGNOSTIC）。
- 最终质量验证：`./mvnw -Denforcer.skip=true verify` 335 测试通过 + JaCoCo 覆盖率检查通过 + 可执行 jar 打包成功；新增可复现验收脚本 `tools/module-loop-verify.sh`（真实仓库完整复现 build → review → publish → stale → rebuild → claim diff，含符号图同步与清理）。
- 完整验证固化为机器可读产物：`tools/verify-report.sh` 以 JDK 17 运行不跳过 Enforcer 的 `mvnw verify`，聚合 surefire 报告并输出 `docs/verification/<version>-<commit>.json` 与 `latest.json`（测试数、JaCoCo 结果、jar、commit）；`tools/module-loop-verify.sh` 增加失败清理（trap 恢复分支与符号图）与 dirty worktree / 分支防护。
### Fixed

- 自进化 RAG M1-M4 评审整改（策略隔离/门禁/评测集可信度/关闭开关/去重/不可变版本/指标真实性）：
  - 离线实验真正隔离基线与候选策略：`AgenticOrchestrator` 新增 `execute(request, policy)` 显式策略入口，`AgenticRetrievalPolicyExecutor` 把 policy 传入编排器，实验不再共用同一个 active 策略。
  - 策略审批接入 Promotion Gate：`PolicyLifecycleService.approve` 必须提供匹配候选策略的离线实验报告且通过 `PolicyPromotionGate`；状态机限制 `DRAFT→EVALUATING→APPROVED/REJECTED→ACTIVE`，`EvolutionController.approve` 增加 `experimentId` 参数。
  - 失败挖掘不再把失败检索结果当 gold：Miner 生成候选时 `predictedRelevantIds` 为空；审核 API 支持人工修正 `relevantIds`/`queryPreview`；发布前强制 APPROVED 候选必须包含真实 query 和人工确认的 relevant IDs；无 query preview 的经验不再生成候选（避免 SHA-256 哈希当查询）。
  - `evolution.enabled=false` 完全忽略 active policy：`PolicyDrivenRetrievalStrategySelector` 与 `AgenticOrchestrator` 均在 evolution 关闭时不读取 `active.json`，磁盘残留 active 不再影响线上检索。
  - 实验重复次数/失败率真实化：`EvolutionExperimentRunner` 按 `repetitions` 实际重复执行，执行器返回 `SUCCESS/DEGRADED/FAILED` 状态，报告统计 failed/degraded rate；`ExperimentManifest` 增加基线/候选 policyId。
  - Miner 与已有候选去重：候选增加 `indexVersion`，按 `queryHash+failureType+indexVersion` 与候选库全局去重，每日调度不再重复膨胀候选库。
  - 版本不可变与原子引用：`EvaluationDatasetRegistry.publish` 拒绝覆盖已存在版本；`RetrievalPolicyRegistry.save` 拒绝覆盖同版本不同内容；active 引用改为临时文件 + 原子替换。
  - 经验采集指标真实化：Recorder 使用自定义拒绝处理器统计 dropped；`FileRetrievalExperienceStore.append` 返回写入结果，Recorder 仅在成功时增加 written、失败时增加 write_failures，不再静默吞掉磁盘异常。
- 自进化 RAG 第二轮评审整改（策略参数有效性/门禁绑定/版本状态/质量指标/随机种子）：
  - 未接入真实检索链路的策略参数（`weights.*`、`retrieval.topK.*`、`rerank.bge-enabled`）从 allowlist 移除，注册即拒绝，避免“候选看起来不同、实际执行相同”的无效实验。
  - Promotion Gate 绑定基线：运行实验要求 baseline 必须是当前 ACTIVE 策略，且必须提供明确的 indexVersion/modelVersion；审批校验报告 baseline 为当前 ACTIVE、禁止候选与基线相同、数据集/索引/模型版本必须绑定。
  - 策略版本生命周期防回退：`createDraft` 禁止重复创建已存在版本，ACTIVE/APPROVED 等版本不能被相同参数重新写回 DRAFT。
  - 质量指标去重：`EvolutionExperimentRunner` 的 Recall/MRR/nDCG 只取每个 case 第一次执行（#1），repetition 仅用于延迟与 failed/degraded 稳定性统计。
  - randomSeed/repetition 进入执行上下文：`RetrievalRequest` 增加 `randomSeed`，executor 将 seed+repetition 派生后传入检索请求并纳入结果缓存键，为可复现的随机检索/重排提供基础。
- 修复默认排除规则误伤：`application.yml` 默认 `exclude-path-substrings` 移除 `/build/`——排除匹配是路径子串匹配，`/build/` 会把包目录名为 `build` 的源码文件一并排除（封神仓库实测误伤 127 个 Java 文件，含评测目标 `BuildKillRankHandler`、`BuildPluginCommon`，对应 8 条评测查询全部召回失败）；移除后全量重索引覆盖 2139/2139 文件。
- 修复结构重排消融开关未接入生产路径：`code-structural-rerank-enabled=false` 此前不影响实际重排（调用点硬编码开启），已接入全部重排调用点，基线 A 与实验 E 的消融对比成立。
- 修复 OpenAI 网关上游强制 `encoding_format`：嵌入请求补显式 `encoding-format: float`（`OPENAI_EMBEDDING_ENCODING_FORMAT` 可覆盖）；缺失时网关 400、应用 5 次退避重试导致单查询 55s+，实测修复后恢复正常延迟。
- 评审整改（第二轮）：
  - 类限定快速路径补方法优先：全局结果已含目标类方法时的快速路径返回前统一执行 `methodFirst`（容器类 chunk 不再压过方法答案），Recall@1 88.6% → 93.6%（该轮其余为嵌入后端变更的排序漂移）。
  - `CodePathFilter` 区分文件规则与目录规则：文件规则（如 `/简历.md`、`/Generated.java`）在源码树内同样生效；新增 `/src/.../简历.md` 类回归测试。
  - `tools/compare-code-reports.py` 仓库根目录由 `__file__` 推导，不再硬编码本机路径。
  - `searchTrace()` 与生产 `search()` 同一次检索：`ScopedSearchResult` 携带同次检索的候选归因，candidates/ranked 不再来自两次独立检索；新增单次检索断言（trace 路径不得重复调用混合检索）。
- 评审整改（第三轮）：
  - `methodFirst` 只提升目标类文件范围内的方法/构造器：无关类方法不再被误提权，保持原有相对顺序；新增「无关类方法领先不被提权」回归测试。
  - 查询中的显式文件路径参与召回与重排：`classScopedHits` 在解析到 `filePath` 时直接以该文件为类限定范围（不受同名类 `classFilePaths(...,8)` 截断影响）；`candidateScore`/`exactMatchLevel` 增加文件路径精确命中信号（+0.60）；新增同名类多文件路径区分回归测试与显式路径限定范围测试。
  - Trace 候选池与实际重排输入对齐：并集路径的 `ScopedSearchResult.candidates` 返回并集（实际重排输入），CODE_CANDIDATE_RECALL_MISS / CODE_RERANK_LOSS 归因不再失真。
  - git 子进程加固：`gitShow`/`gitHead` 合并错误流单流消费（防 stderr 写满死锁）+ `waitFor(5s)` 超时后 `destroyForcibly`。
  - 第三轮修复对评测集行为中性：E7 与 E6 零排名变化（Recall@1 93.6% / Recall@10 99.6% / MRR 0.9596），全量测试通过。
- 评审整改（第四轮）：
  - 类限定快速路径按目标符号判断：给出目标符号名时，仅当该符号已出现在全局目标类方法中才走快速路径，否则类内补召回（并集符号命中加权置顶）；类内候选无法提供目标符号（解析器误把业务文本标识符当方法名）时不做并集扰动，回退全局精排。
  - 精确符号通道使用查询中的显式文件路径：`findExactSymbols` 增加文件路径过滤（精确/后缀匹配），多模块同名类同名方法场景按路径区分；新增同名符号路径过滤测试。
  - git 输出异步消费：`gitOutput` 在后台线程读取输出 + 合并错误流，`waitFor(5s)` 超时强杀真正生效（子进程不关流也不会阻塞）。
  - Trace 候选池并入精确命中：`candidates = 精确命中 + 混合候选去重`，与最终 ranked 输入一致，raw rank / rank movement / CODE_RERANK_LOSS 归因不失真。
  - 文档行尾空格清理（`git diff --check` 通过）。
  - 第四轮修复对评测集行为中性：E9 与 E7 零排名变化（Recall@1 93.6% / Recall@10 99.6% / MRR 0.9596），全量测试通过。
- 评审整改（第五轮）：
  - SQLite 路径过滤不再用 LIKE：改为确定性后缀比较 `substr(file_path, -length(?)) = ?`，`_`/`%` 不再被当作通配符误命中；新增 `module_a` vs `module-a` 字面量回归测试。
  - 精确通道与类限定通道路径语义统一：新增 `resolveFilePaths` 把查询中的（可能不完整的）路径先解析为符号库中的真实完整路径再进 Qdrant 完整值匹配，解析失败回退原始路径；新增后缀解析测试。
  - git 超时统一 deadline：等待进程与收集输出共用同一 5s deadline（总耗时上限不再接近 10s），finally 先销毁进程（关管道解除读取阻塞）再取消读取任务，避免遗留公共线程池阻塞任务。
  - 第五轮修复对评测集行为中性：E10 与 E9 零排名变化（Recall@1 93.6% / Recall@10 99.6% / MRR 0.9596 / P95 455.6ms），全量 414 测试通过。
- 代码语义标注补标：标注模型由不可路由的 `claude-opus-5`（网关 Vertex AI 404）切为 `ANNOTATION_MODEL=gpt-5.6-sol`；清理重索引新增 140 个文件的降级静态标注缓存条目（磁盘 2920 条按 chunk 哈希剔除 + Qdrant live 按文件范围删除），重索引后 11 个核心业务文件（33 个 chunk）用 gpt-5.6-sol 完成中文业务语义补标（含 `BuildKillRankHandler.handle` 等），非核心文件保持静态标注（设计如此）。补标后评测 E5：Recall@1 88.6% / Recall@10 99.6% / MRR 0.9202，与 E4 持平无回归。
- 评审整改（代码检索改进落地后）：
  - 类限定检索的 Qdrant 过滤格式：多值 filePath 匹配由 `match.value` 数组改为 `match.any`（Qdrant 多值匹配的文档语义；实测本机 Qdrant 1.15.4 对 `match.value` 数组返回 400，此前通道静默回退从未生效），新增请求体断言测试（含 `match.value` 不存在断言）与守卫快速路径测试（全局已含类方法时不发起类内查询）。
  - 精确符号查找增加所属类约束：`findExactSymbols` 类-方法连接增加 `s.qualified_name = c.qualified_name || '.' || s.simple_name`，同文件多类（内部类/多类文件）不再把 OuterB.foo 当 OuterA.foo 置顶；实测 125/125 评测目标覆盖不变，新增同文件双类回归测试。
  - 精确通道源码读取改为快照版本：`git show <commitSha>:<filePath>` 优先（与索引严格一致），git 不可用或失败时仅当工作区 HEAD 与快照一致才回退读工作区，否则放弃该命中；新增 dirty-worktree 回归测试。
  - `searchTrace()` 的 ranked 与生产 `search()` 对齐（含精确符号与类名限定通道），candidates/dense/sparse 仍为混合检索归因，内置评测不再只反映混合检索链路。
  - 排除规则误伤修复改为源码树感知的 `CodePathFilter`（根锚定 + `/src/` 前缀模式 + 目录型模式仅命中源码树外路径段），恢复 `/build/` 默认排除项（Gradle 产物仍排除、源码包目录 `build` 不再误伤），两个扫描器共用同一规则并新增语义测试。
- 增量索引部分失败重试时，从待删除旧 ID 中排除本次新 chunk ID，避免重试删除已成功写入的新数据；删除数量改为实际清理数，并增加真实 Git commit 回归测试。
- 审查整改（0.8.5 系统边界与数据正确性）：
  - **认证 fail-closed**：新增 `AuthProperties`（`app.rag.auth.identity-header` / `default-admin-allowed`）与 `UserContextResolver`；REST 拦截器与 MCP 身份过滤器统一解析可信身份，缺少网关头或默认管理员被禁时返回 401；`application-production.yml` 默认禁止默认管理员（直连应用端口不能以管理员执行写操作）；本地开发保持默认管理员模式。
  - **增量代码索引**：写入 `<base>-live` alias（检索立即可见），顺序改为「扫描 → upsert → 删除旧」，失败不再丢数据；符号图快照在 dirty worktree 时跳过（不伪造 commit）。
  - **dirty worktree 拒绝**：全量扫描发现未提交修改时拒绝（索引内容不得冒充 commit 快照）。
  - **需求导入缓存失效**：`RetrievalResultCache.invalidate(documentId, version)`，同版本替换后旧检索结果不再残留（空结果缓存同样失效）。
  - **并发索引顺序**：`CodeKnowledgeService.index` 项目级锁串行化（同步 API / webhook / 后台任务统一入口），杜绝旧索引晚完成覆盖新 live。
  - **源码接口行号校验**：拒绝 `startLine<1`、`endLine<1`、`startLine>endLine` 与超出文件长度（不再返回 0 行号元数据）。
  - 新增回归测试：401 两场景、网关身份头、dirty worktree 拒绝、行号三类非法范围、缓存失效两场景、增量索引 live alias 写入。

### Added

- 文档摄入格式确认与固化（开源 RAG 引擎对比后的 P0 落地）：上传入口经 Apache Tika 原生支持 `pdf` / `docx` / `xlsx` / `html` / `txt` / HTML-zip（PDF 文本提取由 Tika→PDFBox 完成，无需额外组件）；新增真实 PDF 摄入回归测试（PDFBox 生成 → Tika 提取 → 分块入库），`docs/user-guide.md` 记录支持格式；扫描件 OCR 记为已知缺口。检索评测对照能力（`tools/retrieval-eval-comparison.py` 基线/重排对照 + 54 条冻结评测集 + QualityGate 全 profile 门禁）经核对已具备，无需引入 FlashRAG。

### Changed

- 平台回迁：Spring AI 1.1.2 → **2.0.0**、Spring Boot 3.4.9 → **4.1.0**、**JDK 17 → 21**（pom/Enforcer/Dockerfile/README/verify-report）；移除 spring-ai-alibaba BOM（无 artifact 使用）。适配：`ChatClient.options()` 改传 Options Builder（2.0 签名）、SSE 事件改用项目 Jackson 2 序列化 JSON 字符串发送（Boot 4 默认 Jackson 3 转换器无法序列化 Jackson 2 JsonNode payload）。360 测试全绿（JDK 21 + Enforcer verify 通过）。
- CI（`.github/workflows/ci.yml`）JDK 17 → 21（`setup-java` temurin 21），与 Enforcer 要求一致。

- 代码语义标注并发化（`CodeSemanticAnnotator`）：LLM 批次标注按固定线程池并行（默认 `min(4, 核数/2)`，可构造注入调整），结果顺序与输入一致；熔断语义适配并发（失败批次计数达阈值后，未提交批次走静态标注，已提交批次快速失败），`annotate` 耗时从串行的 500-750 次调用降为并行吞吐。新增并发顺序与熔断适配测试。


### Fixed

- 审查整改（0.8.5 安全与索引边界）：
  - 身份头最小权限：身份头只断言身份，角色取自可选角色头（缺失/非法 → READONLY，伪造身份头无法执行写入/评审）；非法角色值返回 401；可选受信来源（IP 前缀/CIDR）拒绝非受信来源的身份头请求。
  - 共用默认 collection 的缓存失效：collection 无法唯一归属项目时改用全量失效（`invalidateAll`），不再只清理默认项目缓存。
  - 旧 chunk ID 滚动读取支持分页（`next_page_offset` 翻页），超大文件更新后不再残留第二页起的旧 chunk。
  - 文档明确索引协调为单 JVM 内锁，多实例部署需单写者或外部调度（跨 JVM 协调列为已知缺口）。
  - 新增测试：身份头无角色不能执行 WRITE、非法角色 401、非受信来源 401、共用 collection 全量失效、scroll 跨页删除。
### Fixed

- 增量索引第四轮修复（0.8.5）：
  - 重试安全：删除前从旧 ID 集合排除本次 upsert 的新 chunk ID——部分失败后按提示重试同一 commit 范围不再删除刚写入的数据；新增重试场景回归测试（live 含旧 ID + 上次失败残留新 ID）。
  - 受检异常原类型恢复：`CodeIndexLockService.execute` 改为支持受检异常的 `ThrowingSupplier`，`IOException`/`InterruptedException` 按原类型透出（不再被包装成 RuntimeException），InterruptedException 同时恢复中断位。
  - 最终一致策略文档化：`docs/retrieval-status-contract.md` 明确增量索引的文件级安全替换与重试收敛语义（升级 staging + alias 原子发布留待强一致需求时实施）。
### Fixed

- 增量索引第三轮修复（0.8.5）：
  - 删除失败语义明确：新 chunk 已写入但旧 chunk 清理失败时抛部分失败异常（记录待清理 ID），提示对同一 commit 范围重试收敛——不再静默新旧并存。
  - `indexedChunks` 统计修正为真实扫描 chunk 数（此前误用文件数）。
  - 缓存失效纳入项目维度：`RetrievalResultCache.invalidate(projectId, documentId, version)`，`ProjectRegistry.findProjectIdByRequirementCollection` 反查项目（默认 collection 映射默认项目），不再无差别清除其他项目缓存。
  - 默认 profile 认证 fail-closed：`default-admin-allowed` 默认改为 `false`，本地开发由 `application-local.yml` 显式开启（生产/默认 profile 直连一律 401）。
  - 增量扫描 commit provenance 测试：dirty worktree 下 `scanFiles` 仍从 `git show` 读取目标 commit 内容（不混入未提交修改）。
### Fixed

- 增量索引第二轮修复（0.8.5）：
  - 删除 API 不再按 filePath 删除（会误删新 chunk）：先滚动快照旧 chunk ID，再 upsert 新 chunk（新 ID），最后只按旧 ID 删除；任一步失败旧数据保留，删除失败最多新旧并存（下次索引收敛）。
  - 全量与增量索引共用项目级锁（`CodeIndexLockService`），杜绝并发发布乱序与交错写入。
  - 需求导入缓存失效统一到两条写入分支之后（默认 collection 路径此前遗漏）。
  - 启动校验：默认管理员模式且未配置身份头时，监听非 loopback 地址拒绝启动（fail-closed，不依赖部署 profile 自觉）。
  - 新增测试：删除只按旧 ID（不调用按文件删除）、默认/显式 collection 导入均失效缓存。
- 审查整改（0.8.5 证据与上下文安全）：
  - 需求上下文切片恢复 fail-closed：`requirementContextSlice` 只纳入 Evidence 白名单内（已注册 `evidenceId`）的分块，未注册分块直接跳过（不再生成 `evidenceId=?` 附带正文）；预算不足时只追加完整 block，放不下的计入 omitted（不再截断 evidence ID/文件名并虚报 included）。
  - 需求证据 provenance 补全：`REQUIREMENT` Evidence 的 source 改为项目标识（质量门对所有证据执行项目边界校验，关闭 REQUIREMENT 跨项目绕过）；filePath/documentId、symbol/parentId、commit/contentHash 承载原始 chunk 的 documentId、parentId 与内容哈希，页面 `requirementSources` 恢复真实 documentId/entryId/contentHash，可参与需求 stale 检测。
  - 需求来源版本契约明确：`requirementVersion` 与页面版本不一致时构建拒绝（删除"独立需求版本"语义，消除与跨版本质量门的矛盾）。
  - REST 入口接入：`POST /api/wiki/modules/build` 接收可选 `documentId` / `requirementVersion`，Phase 3 跨域样例可经真实产品入口使用。
  - Java AST shadow 测试断言收窄到实际能力：构造器以 `constructor` 种类断言、重载按行号区分、嵌套限定名精确；显式记录 adapter 限制（record 紧凑构造器不单独成符号、extends/implements/annotations 尚未填充），不再虚假声明覆盖。
  - 模块知识草稿补齐 `build.json` 构建产物（`ModuleKnowledgeBuildService` / `ModuleStaleRebuildService`），与既有发布链路（NO_CHANGES 检查、发布审计）契约一致；此前模块草稿无法通过 `publish` 发布。

## 0.8.4-SNAPSHOT — 2026-08-07

### Added

- 新增 `tools/generate-fengshen-retrieval-eval.py`，分别生成 200 道需求文档/业务名词题和 500 道代码题；两套评估集各自输出可运行的 JSONL Gold 与便于人工评估的 Markdown 答案集。
- 新增 `docs/wiki-next-iteration-module-slice.md`，将 Wiki 下一迭代收敛为 Module 页面纵向闭环，定义 ModuleFactBundle、Evidence Registry、声明级证据、发布质量门、REST/MCP 交付、符号级过期传播、stale-to-draft 流程与 MVP 验收标准。
- 按 `docs/wiki-next-iteration-module-slice.md` 实现 Module 页面纵向闭环：
  - 新增 `ModuleFactExtractor`：从符号图（`SQLiteSymbolGraphStore`）与仓库文件系统确定性抽取 `ModuleFactBundle`（公开符号、对外入口、核心调用链、上下游依赖、HTTP/消息/定时路由、数据对象、配置、测试与未解析调用诊断），支持显式 `modulePath`；无图快照时产出诊断而非失败。
  - 新增 `ModuleWikiPlanner`：Bundle 编译为 MODULE 页面源，自动生成职责/入口/流程/依赖/数据配置/测试/知识缺口七类 Claim，按代码、调用图、依赖、路由、数据、配置、测试和诊断事实引用对应类型的已注册证据。
  - 新增 `ModuleClaimQualityGate` 发布质量门：MODULE 页无代码证据、受支持 Claim 无证据、引用越界/跨项目/跨版本证据均阻止发布（已挂入 `WikiGenerationService` 发布链路）。
  - 新增 `ModuleKnowledgeBuildService`：抽取 → 规划 → 质量门 → 落盘草稿（wiki-source + module-bundle）→ 初始化审核，不自动发布。
  - 新增 `ModuleStaleRebuildService`（stale-to-draft）：从已发布模块页重建事实包，对比新旧 Claims 输出 ADDED/MODIFIED/REMOVED/UNCHANGED 差异，落盘 `claim-diff.json` 草稿供审核人只审变化声明。
  - REST：`POST /api/wiki/modules/build`、`POST /api/wiki/modules/rebuild`。
  - MCP：新增 `nexus_wiki_index`（按 pageType/stale 过滤的索引摘要），`nexus_wiki_page` 响应新增 pageType、claims、support、evidenceIds、gaps、stale 与当前代码提交。
  - 新增 14 个模块闭环测试（抽取/门禁/构建/重建四类）。
- 按 `docs/wiki-strengthening-plan.md` 的 MVP 落地 Wiki 知识编译增强：新增 `PageType`（OVERVIEW/MODULE/FEATURE/API/DATA/VERSION）与声明级证据 `Claim`（claimId/section/text/support/evidenceIds），渲染进 Markdown「声明与证据」段，向后兼容既有源定义。
- 版本知识构建管线（`VersionKnowledgeBuildPipeline`）自动编译项目概览页与模块页：概览页汇总版本、代码提交、模块清单与风险；模块页按源码顶层目录聚合代码入口、符号与关联功能，均标注 DRAFT 待人工审核。
- 功能识别改为按需求文件标题跨文件合并（同名条目并入同一功能页），不再由单个文件名直接决定页面。
- 需求证据绑定内容哈希（块自带哈希或父文本 SHA-256），为增量失效检测提供依据。
- 新增 `WikiStalenessService`：基于 Git commit（`git rev-parse HEAD` + 文件级 diff 命中页面代码入口）与需求内容哈希对比检测过期页面，只读计算、不覆盖已发布内容；`GitDiffService` 新增 `latestCommit`；对外暴露 `GET /api/wiki/staleness`。
- 新增 `WikiStalenessServiceTest`（代码/需求/新鲜三场景）与管线合并、概览/模块页生成测试。


## 0.8.3-SNAPSHOT — 2026-08-06

### Added

- 新增 `tech-briefing.html` 技术选型对比演示稿及配套 `docs/nexus-technology-selection-comparison.md` 讲解文档，从代码上下文平台、企业知识搜索、编码执行 Agent 三个宏观方向，深入比较 Sourcegraph、Atlassian Rovo、GitHub Copilot 与 Qoder 的公开技术方式，补充通义灵码、CodeBuddy、文心快码 Comate、TRAE、MarsCode 等国内产品全景，以及 Repo Wiki、DeepWiki、Context Files 的提效方式，并说明研发证据平台的规划方案和预期优势；演示稿支持键盘翻页、目录导航和横向打印。
- 新增 Agentic 检索编排骨架：策略层（`RetrievalStrategy`/`StrategyResult`）、规则版证据反射器（命中阈值/唯一父块/双侧覆盖 + 稳定 reason code）与最多两跳的编排循环。
- 新增单线检索策略（`RequirementsOnlyStrategy`/`CodeOnlyStrategy`）与基于画像/查询意图的规则选择器；`nexus_search_requirements` 已接入编排器。
- 新增 LLM 代码语义标注（`CodeSemanticAnnotator`）：中英文业务描述、关键词、用户问题与同义词；分层标注（核心走 LLM、非核心走静态）、三连败熔断与哈希缓存。
- 代码索引升级为 `dense` + `desc_dense` 双向量（业务语义向量，检索经 schema 感知的三路 RRF 融合）。
- 新增确定性检索质量门禁 `RetrievalQualityGateTest`：冻结评测集黄金命中随 CI 默认回归，不依赖外部服务。
- 新增代码召回评测脚本 `tools/code-recall-eval.py`（57 查询，含耗时统计）。

### Changed

- BGE 重排新增带分数结果（`rerankScored`）；配置 `llm-rerank-skip-gap` 后，BGE top1 与后续候选分差达到阈值时跳过 LLM 重排（默认 0 不启用，可回退完整链路）。
- 新增配置档位 `local` / `evaluation` / `production` 三个 profile，以及启动期配置校验器（`RagConfigValidator`）：URL 合法性、collection 非空、topK 关系、超时与缓存非负、嵌入模型与仓库路径缺失均在启动时失败。
- 平台迁移至 JDK 17（pom/Dockerfile/CI），替换全部 Java 21 API（`getFirst`/虚拟线程/`getLast`）。
- 迁移至 Spring AI Alibaba 基线：Spring AI 2.0 → 1.1.2、Spring Boot 4.1 → 3.4.9、Jackson 3 → 2；模型接入保持 OpenAI 兼容网关。
- MCP 层按 Spring AI 1.x 重写：`@Tool` 注解（工具契约 `nexus_*` 不变）、ThreadLocal 用户上下文 + HTTP Filter 鉴权、1.x Resources/Prompts 注册方式。
- 嵌入模型切换为网关 `text-embedding-v4`；LLM 按任务路由（开发计划 gpt-5.6-sol、存疑评审 glm-5.2、代码标注 claude-opus-5、重排 claude-sonnet-4.6），标注读取超时放宽至 300s。
- 移除内部 API Key 认证（`ApiKeyAuthenticationService`/`AuthProperties`/Filter），身份与权限交由外部统一网关，内部以默认管理员上下文运行。
- 需求索引发布改为安全语义：先幂等写入并校验新点可读，再删除过期点；任一步失败保留旧版本。
- 代码索引发布改为版本化物理 collection + Qdrant Alias 原子切换（`<base>-live`），校验失败不发布，保留最近两个版本。
- GitLab webhook 提交至 `CodeIndexJobService` 统一治理（同项目并发合并），不再自行创建线程。
- 跨项目检索改用有界 retrieval executor，总超时后取消未完成 future。
- Embedding 缓存键纳入向量维度指纹，模型或维度变化自动失效。
- 检索结果缓存键纳入配置指纹，配置变更自动失效。

### Removed

- 移除 OWASP dependency-check 安全扫描 job（NVD 全量下载频繁超时且不触发失败通知）。
- 移除本地 BGE 重排服务依赖（`code-bge-rerank-enabled` 默认关闭）；重排降级为 LLM 重排 + 混合顺序兜底。

## 0.7.0-SNAPSHOT — 2026-07-28

### Added

- 新增基于 Tree-sitter 的 Java、Go、Python、TypeScript 多语言 AST 代码索引，Kotlin 通过启动能力探测安全启用。
- 新增独立 SQLite 符号图，按项目和 Git commit 事务化保存符号、调用关系、解析置信度和未解析调用。
- 新增符号图与影响分析 REST API，以及 `nexus_code_graph`、`nexus_impact_analysis` 两个 MCP 工具。
- 新增 `nexus_review_doubts` MCP 工具及 `nexus://wiki/{projectId}/{version}/{featureId}` Resource Template。
- 新增符号影响、commit 范围影响、入口与测试回归建议；目标图谱缺失时显式降级到文件差异。

### Changed

- `CodeChunk` 与 `nexus_search_code` 增加向后兼容的 `language` 字段。
- 全量和增量代码索引不再硬编码 `.java`，由语言注册表统一识别支持的源码。
- NEXUS 平台版本提升到 `0.7.0-SNAPSHOT`；业务需求版本继续独立管理。

### Safety

- 只有 `EXACT` 和 `SAME_FILE` 调用边计入确定影响；启发式匹配单独返回，歧义和动态调用保持 `UNRESOLVED`。
- 图谱只保存仓库相对路径与结构化元数据，不保存源码正文、向量、凭据或 Qdrant 内部数据。

## 0.6.0-SNAPSHOT — 2026-07-28

### Added

- 新增 `/mcp` Streamable HTTP Server，为 Codex、Cursor 和其他 MCP 客户端提供六个只读研发知识工具。
- MCP 结果统一返回解析后的项目/版本、受控数据、稳定证据、质量、显式告警和截断状态。
- 新增 MCP 调用指标与安全审计日志，不记录查询正文、API key 或完整证据。
- 新增固定版本的 stdio 兼容桥、Codex/Cursor/Claude Code 配置说明和常见错误排查。
- 新增非 root 多阶段容器镜像及包含 NEXUS、Qdrant、Prometheus、Grafana 的 Compose 配置。

### Changed

- API key 认证、角色权限和项目白名单抽取为 REST 与 MCP 共用的传输无关服务。
- Codex 与 Cursor 的项目级 MCP 配置随仓库提供，密钥只从 `NEXUS_API_KEY` 环境变量读取。
- NEXUS 平台版本提升到 `0.6.0-SNAPSHOT`；业务需求版本继续独立管理。

### Security

- MCP 初始化及后续 HTTP 请求都校验 `X-API-Key`，工具调用继续执行既有角色与项目授权。
- MCP 响应统一限制列表、源码行数、摘录、证据和总字符数，并移除绝对路径与内部存储标识。
- 源码读取按真实路径校验仓库边界，拒绝通过仓库内符号链接读取仓库外文件。
- 容器构建上下文排除业务数据、仓库、向量存储、凭据、日志、归档和本地环境文件。

## 0.5.0-SNAPSHOT — 2026-07-28

### Added

- 新增 NEXUS 平台首页与 `/api/runtime/status`，集中展示依赖、Wiki、需求和代码索引的真实可用状态。
- 新增 `scripts/nexus.sh` 一键启动、停止、状态检查和日志入口。
- 新增后台代码索引任务和状态接口，代码工作台可在索引期间继续使用并轮询完成状态。
- 新增 Wiki schema 2 结构化知识契约，支持需求来源、处理流程、代码入口、数据影响、边界条件、验收标准、版本变化和质量缺口。
- 目标版本 Wiki 改为按有效需求条目生成独立功能页，并提供需求、开发、测试和证据四类可阅读视图。
- 新增统一知识声明、冲突和报告契约，支持需求、代码、测试与派生 Wiki 的确定性冲突检测。
- 新增受项目权限保护的 `POST /api/knowledge/conflicts/analyze`，返回版本污染、项目污染、来源间冲突和 Wiki 证据缺口。
- 新增请求级证据白名单，为需求和代码检索结果生成稳定、受控且不暴露本地绝对路径的证据引用。
- 同步和 SSE 开发方案新增逐条引用状态、非法引用过滤、缺失引用告警与回答证据覆盖率统计。

### Changed

- 版本 Wiki 生成器不再为目标版本生成仓库模块清单页；版本概览只说明覆盖率，功能页只记录可追溯的需求与静态代码证据。
- 没有真实测试快照时统一显示“没有真实执行快照”，不再把需求事实重复包装成测试知识。
- schema 1 历史 Wiki 保持可读；schema 2 产物执行完整一致性校验。
- 非流式开发方案响应追加 `conflictReport`；旧构造方式保持兼容，检索证据不会被自动仲裁或伪装为一致。
- 代码工作台可查看结论的需求/代码证据、支持状态和整体可信度；代码证据保持源码跳转，需求证据只展示受控摘录。

### Fixed

- 认证默认启用并在凭据为空时拒绝启动；仅显式本地配置允许关闭认证并输出安全警告。
- BGE 重排客户端增加 2 秒连接超时和 5 秒读取超时，依赖假死时可及时进入安全降级。
- 清理生产代码中的静默异常处理；降级、回退和清理失败现在都会留下安全日志。
- 真实业务表格和生成导出从 Git 跟踪范围移除，保留为本地数据并由 `.gitignore` 隔离。
- 修复旧启动脚本仍引用 `0.0.1-SNAPSHOT`、不加载 `.env` 且缺少停止流程的问题。
- 一键启动现在能识别并恢复项目自有的 Qdrant/NEXUS 假存活进程，启动失败会清理新进程；其他程序占用端口时不会被误停。
- 根路径不再直接进入依赖最重的代码工作台；BGE 缺失会明确标记为可降级。
- 修复完整代码索引并发调用本地 Ollama 导致请求失败的问题，改为受控批量嵌入并隔离异常输入。
- 完整索引失败前不再删除旧索引；大型 Java 类型和方法会切分为嵌入安全的片段，并减少类正文与方法正文的重复索引。

### Release boundaries

- 需求评审、开发方案和知识草稿共享统一检索管线，同时保留请求级证据白名单、版本隔离和显式降级。
- 知识构建只生成草稿；草稿必须经过提审和批准才能原子发布，并保留可审计状态历史与回滚快照。
- NEXUS 平台版本提升到 `0.5.0-SNAPSHOT`；业务需求版本继续独立管理。

# Changelog

## 0.4.3-SNAPSHOT — 2026-07-27

### Security

- 生成后的需求快照改为仅保留在本机并由 Git 忽略，避免把真实业务需求正文提交到远程仓库。

### Fixed

- 修正需求版本链把增量文档误当完整清单的问题：新版本未重复出现的历史需求现在继续有效，不再被批量判定为删除。
- 需求快照会沿 `baseRequirementVersion` 递归合成完整需求视图；同一稳定条目再次出现时才产生修改。
- 只有结构化 `REMOVE` 操作会删除历史需求，正文中的“删除、取消、移除”等业务词不会触发需求删除。
- Qdrant 兼容回退不再根据条目缺席推断删除，避免在缺少完整增量链时产生错误结论。

### Changed

- 快照生成工具支持从专用操作或状态列识别精确删除状态，旧快照缺少操作字段时默认按 `UPSERT` 处理。
- 增加需求增量继承、显式删除、普通业务词、缺失基线、继承环和真实基线到目标版本链路回归测试。
- NEXUS 平台版本提升到 `0.4.3-SNAPSHOT`。

## 0.4.2-SNAPSHOT — 2026-07-26

### Added

- 新增 `RequirementSnapshotRepository` 和非向量需求快照模型，按需求版本或业务版本 alias 读取可审阅需求事实。
- 新增 `VersionManifestResolver`，合并正式版本档案、Wiki 版本索引和需求快照，并根据相邻 commit 补齐业务基线版本。
- 新增 `tools/build-requirement-snapshots.py`，从现有历史需求表和本地可选产品文档包生成确定性的轻量 JSON 快照。
- 为有明确材料的 20 个需求基线生成受控快照，覆盖当前基线到目标版本比较链路。

### Changed

- 版本档案列表和读取接口统一使用解析后的有效档案；人工档案优先，缺失需求引用时可由可信快照补齐。
- 需求差异优先比较仓库中的受控快照，仅在快照缺失时回退到 Qdrant payload。
- 缺少独立 VersionManifest 时，版本中心仍可展示真实需求新增、修改、删除及前后摘要。
- NEXUS 平台版本提升到 `0.4.2-SNAPSHOT`，与业务需求版本继续严格分离。

### Security and data boundaries

- 需求快照只包含来源、文本、顺序和哈希，不包含向量、embedding、Qdrant point、storage、snapshot、WAL、凭据或本地索引。
- 2.8GB 原始产品文档包继续由 Git 忽略，不进入仓库；提交内容仅包括生成后的轻量需求事实。
- 没有可靠需求材料的业务版本继续返回 `NOT_AVAILABLE` 和安全 warning，不推断为“没有变化”。

## 0.4.1-SNAPSHOT — 2026-07-24

### Added

- 新增 `tools/build-version-wiki.py`，从真实 Git 历史为已登记版本补齐代码结构、模块边界、版本文件差异和受控证据。
- 为已配置项目的历史版本重新生成实质性 Wiki 页面，保留已有人工页面并补充代码证据页。
- 版本中心改为直接读取 `/api/wiki/versions`，没有独立 VersionManifest 时也可以基于 Wiki 版本索引进行代码和 Wiki 差异比较。

### Changed

- `/api/versions/compare` 在缺少独立版本档案时降级到 Wiki 版本比较；需求和测试来源仍明确标记不可用。
- 版本中心时间线展示 Wiki 页面数量、基线 commit 和目标 commit，不再把“无版本档案”误报为“无版本”。

### Boundaries

- 自动生成内容只描述 Git 文件路径、结构和有限证据，不复制完整源码，也不生成未经需求原文核验的产品规则。
- 测试页面没有真实执行记录时统一显示“没有真实执行快照”，不会把建议测试点伪装成执行结果。
- 不提交向量、embedding、Qdrant storage、WAL、本地索引、IDE 数据或凭据。

本文件记录 **NEXUS 平台版本**。业务需求版本（例如 `2026.07`）继续记录在 Wiki source、knowledge manifest 和构建请求中，两者不得混用。

## 0.4.0-SNAPSHOT — 2026-07-24

### Added

- 新增 `/versions` 版本中心入口和原生静态浏览页面。
- 新增项目、版本档案和起止版本选择，形成版本时间线并优先使用 manifest 的 `baseVersion`。
- 新增需求、代码、测试和 Wiki 四类差异页签，支持统计、明细、降级状态和安全 warning 展示。
- 新增 Wiki 差异到 `/wiki?projectId=...&version=...&featureId=...` 的深链接，可自动定位目标功能页面。
- 新增版本中心和 Wiki 页面契约测试，覆盖页面资源、导航、API 路径、API Key 和安全转义约定。

### Changed

- Wiki 页面增加版本中心导航，并支持通过项目、版本和功能 ID 查询参数打开指定页面。
- NEXUS 平台版本提升到 `0.4.0-SNAPSHOT`；平台版本继续与业务需求版本严格分离。

### Security and data boundaries

- 版本中心对 API 返回的文本统一 HTML 转义，不渲染内部异常、绝对路径、凭据或向量数据。
- 测试差异只展示版本档案中记录的真实执行快照；缺失快照明确显示“没有真实执行快照”。
- 单个需求、代码、测试或 Wiki 来源不可用时只展示安全降级信息，不影响其他来源的差异浏览。

### Known limitations

- 代码差异当前是文件级比较，不提供 AST 或符号级调用影响分析。
- 页面不创建或修改 VersionManifest；版本档案仍需通过 API 或构建流程记录。
- 页面不执行测试，也不把建议测试点当成测试执行结果。

## 0.3.0-SNAPSHOT — 2026-07-24

### Added

- 新增 `VersionManifestService`，按项目和业务版本安全保存、更新、读取和列出独立版本档案。
- 新增 `VersionComparisonService`，聚合需求、代码、测试和 Wiki 四类结构化版本差异，并为不可用来源返回明确状态和安全 warning。
- 新增版本档案保存、列表、读取和版本比较 API，接入项目存在性、项目访问权、`WRITE` 与 `PUBLIC_READ` 权限校验。
- 新增共享 `GitDiffService`，支持 Git 文件新增、修改、删除、重命名和 Java、测试、配置文件分类统计。
- 新增共享 `RequirementChunkDiff` 与 `RequirementVersionDiffService`，按稳定父块标识比较需求版本且只读取 Qdrant payload。
- 新增版本档案、需求差异、多来源比较、Controller 和 Git 文件差异测试。

### Changed

- `IncrementalCodeIndexService` 改为复用统一 `GitDiffService`，移除重复的 Git 进程执行和输出解析。
- `VersionKnowledgeBuildPipeline` 改为复用统一需求父块差异算法，并使用通用、稳定且唯一的功能标识规则。
- `WikiRepository` 新增按项目和版本读取索引的 `findIndex` 能力，供版本比较使用。
- NEXUS 平台版本提升到 `0.3.0-SNAPSHOT`；平台版本继续与业务需求版本严格分离。

### Security and data boundaries

- 版本档案使用安全标识、规范化路径、同目录临时文件和原子替换，拒绝路径穿越。
- Git 比较只接受 7–64 位十六进制 commit SHA，用户输入不能变成任意 Git 或 shell 参数。
- 版本档案不保存 vector、embedding、Qdrant point、snapshot、storage、WAL、Token、密码或凭据。
- 降级 warning 和公开错误不返回依赖异常原文、内部 URL、绝对路径或敏感配置。

### Known limitations

- 代码差异当前是文件级比较，不提供 AST 或符号级调用影响分析。
- 平台不自动执行测试；测试差异只比较档案中已经记录的真实测试快照。
- 任一版本的 Wiki 索引缺失时只返回 warning，不阻断需求、代码和测试比较。

## 0.2.0-SNAPSHOT — 2026-07-24

### Added

- 新增统一 `RetrievalPipeline`、`RetrievalRequest`、`RetrievalProfile` 和 `RetrievalBundle`。
- 新增 `DEVELOPMENT_PLAN`、`REQUIREMENT_REVIEW`、`WIKI_BUILD` 三种证据检索 profile。
- 新增版本知识草稿构建器 `VersionKnowledgeBuildPipeline`。
- 新增 `POST /api/knowledge/build`，支持按 `baseVersion → version` 比较需求父块并生成知识草稿。
- 新增 `data/wiki-drafts/<project>/<version>/<buildId>/build.json` 和 `wiki-source.json` 草稿格式。
- 新增统一检索成功、零命中、单侧降级、核心失败测试，以及版本差异、相似功能边界、路径安全和 API 测试。

### Changed

- 同步与 SSE 开发方案改为使用同一 RetrievalPipeline，保留现有响应和 SSE event 合同。
- `WikiProperties` 新增 `draftPath`，默认 `data/wiki-drafts`。
- README 更新为“版本化需求、代码和测试知识平台”的自动草稿审核工作流。

### Security and data boundaries

- 自动构建只读取 Qdrant payload，不读取或写出向量。
- 草稿禁止保存 vector、embedding、Qdrant point、snapshot、storage、token、密码和凭据字段。
- 自动构建不得覆盖正式 `data/wiki-sources/` 和 `data/wiki/`。
- `.idea` 保留在本机并继续由 Git 忽略；`.codegraph`、Qdrant storage、snapshot、WAL、模型缓存和 `.env` 不进入版本库。

### Known limitations

- 第一版按变化需求文件形成候选功能，不会用 LLM 自动断言数值规则。
- 代码命中是候选关联，测试点是待实现建议；发布前仍需产品、开发和测试人工审核。
- 需求评审中的 BGE/LLM 重排尚未完全迁移到统一管线。
- 检索 Gold Dataset 当前为 10 条，后续仍需扩展到约 50 条。
