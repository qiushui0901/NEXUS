## 0.9.5 — 2026-08-25

### Added

- **需求语义 Chunk 增强最小闭环（Phase 1 + Phase 2，方案：`docs/requirement-semantic-chunk-and-hybrid-retrieval-development-plan-0.9.5.md`）**，新增包 `requirement/semantic`，默认全部关闭（`app.rag.requirement-semantic.enabled=false`）：
  - **语义契约（Phase 1）**：`RequirementSemanticModels` 定义受控枚举（certainty/operator/valueType/questionType/错误码）与 LLM JSON 契约（entities/conditions/events/numericFacts/claims/questionExpansions/uncertainties/missingContext）；枚举在 JSON 绑定层保持 String，由 Validator 归一化校验，未知枚举不会误判为解析失败。
  - **Prompt 服务**：`RequirementSemanticPromptService` 版本化 Prompt（`requirement-semantic-v1`），系统 Prompt 内置十条抽取约束（不补造、证据子串回查、缺失入 missingContext、factKey 受控格式、只返回 JSON）。
  - **服务端校验器**：`RequirementSemanticAnnotationValidator` 执行 §7.3 十项校验（数组上限、枚举、evidenceQuote 必须是原文连续子串、数值/操作符一致性（含 BETWEEN 区间）、factKey 格式、Claim 主体引用存在性、去重、missingContext 保留），失败抛稳定错误码（`SEMANTIC_SCHEMA_INVALID / EVIDENCE_UNAVAILABLE / NUMERIC_INVALID / FACT_KEY_INVALID`）。
  - **语义文本生成器**：`RequirementSemanticTextComposer` 按 §9.2 固定字段顺序渲染稳定语义文本（[原文]/[主体]/[条件]/[事件]/[事实]/[可能的问题]/[缺失上下文]），同一事实不因字段顺序漂移；同时生成单行结构化摘要。
  - **LLM 标注服务**：`RequirementSemanticAnnotationService` 有界重试（仅对超时/限流/不可用/JSON 解析失败重试，指数退避），错误分类为稳定错误码，token 粗估用于预算控制；非重试性 Schema/证据错误立即失败。
  - **SQLite 幂等存储（Phase 2）**：`SQLiteRequirementSemanticStore` 新增 `requirement_semantic_annotation` + 5 张子表（entity/condition/event/numeric_fact/question，外键级联）；幂等键为（项目、文档、需求版本、稳定 chunkId、内容哈希、模型、Prompt、**Schema 版本**——修复了方案 §6.1 唯一键缺 schema_version 导致版本升级撞键的问题）；Prompt/Schema/内容变化生成新记录而非覆盖；失败记录可见、可重试且重试替换不重复；单事务保存。
  - **构建编排**：`RequirementSemanticBuildService` 加载父块（Qdrant scrollVersion + 去重）→ 稳定 chunkId（filename|parentId|parentOrder，不依赖向量 point ID）→ 短块整块/长块复用 `RequirementGraphWindowPlanner` 结构感知切窗不丢尾部 → 幂等跳过未变化内容 → 预算控制（maxModelCalls/maxWallClockSeconds/maxEstimatedTokens，超限停呼并携带 `SEMANTIC_BUDGET_*` 警告）→ 部分失败语义（SUCCESS/PARTIAL_FAILURE/FAILED + 每块错误码）；`retryFailedOnly=true` 只重跑失败项；结构化运行日志只记录 ID/状态/预算，不落原文与模型输出；Micrometer 指标 `nexus.requirement.semantic.started/completed/failed/latency`。
  - **配置**：`app.rag.requirement-semantic.*` 全套开关与预算（默认关闭，`REQUIREMENT_SEMANTIC_*` 环境变量），注册于 `WebMvcConfig`。
  - **测试**：新增 Validator（12 例）、fixtures 回归（12 条固定 JSON：主体/条件/事件/数值/单位/否定/范围/时间/IN 列表/缺失上下文/空抽取）、Prompt、Composer、错误分类、Store 幂等/版本隔离/失败重试、Build 跳过未变/部分失败/预算停呼/长交本切窗共 40 例。

### Fixed（0.9.5 — Review 修复批次）

依据外部代码 Review（P0×1 / P1×5 / P2×2）修复语义模块正确性问题，模块继续保持默认关闭：

- **P0 字符串 EQ/NE 误判为数值错误**：数值校验改为由 valueType（NUMBER/DURATION/RANGE）与严格比较操作符（GT/GTE/LT/LTE）驱动，`EQ + STRING/ENUM/BOOLEAN/DATE`（货币、渠道、状态、品质等事实）不再被 `SEMANTIC_NUMERIC_INVALID` 拒绝；fixtures 新增 `string-equality` 场景（EQ + STRING + ENUM）。
- **P1 预算超限伪装成 SUCCESS**：构建状态改为 `预算中断 || 存在未处理输入 || failed>0` → PARTIAL_FAILURE/FAILED；Token 预算预检把下一窗口预估计入（不再只在事后累计）；`annotate(input, remainingModelCalls)` 把剩余预算传入标注服务，单窗口内重试不再突破 maxModelCalls。
- **P1 数值归一化错误**：服务端 `parseNumber`（支持千分位逗号）成为归一化唯一权威，模型提供的 `normalizedValue` 与其不一致直接拒绝；修复 `normalizedUnit` 误用 `unit` 的 bug（现在优先保留模型归一化单位，缺失时回退原文单位）；`BETWEEN` 区间禁止进入 `numericFacts`（单值事实），必须用 conditions 表达，避免区间被压成单值。
- **P1 空条件 NPE 与错误码误判**：Validator 对 conditions 空成员抛 `SEMANTIC_SCHEMA_INVALID`（纵深防御）；`classify` 把 NPE 归类为 `SCHEMA_INVALID` 而非 `MODEL_UNAVAILABLE`（JSON 契约 null 成员在绑定层被拒绝，且不会误导重试）。
- **P1 sourceRevision 不稳定与无 active generation**：sourceRevision 输入先按稳定键（文件名→父块顺序→父块 ID→内容哈希）排序，对底层返回顺序不敏感；新增 `requirement_semant_build` 构建代际表——非 FAILED 构建把范围内 SUCCEEDED 记录的 `source_revision` 对齐到当前值并切换 active（旧构建保留但非 active），查询提供 `activeSourceRevision()` / `listActive()` 只暴露 active 构建下可消费的成功标注，多次增量构建不会混入旧 revision/旧 Prompt 结果。
- **P1 窗口坐标未持久化**：`SemanticAnnotationInput/Record` 与存储表新增 `window_index/start_offset/end_offset`（旧库 addColumnIfMissing 自动补列），列表查询按 `source_file, parent_order, window_index, start_offset` 稳定排序，审核与跨窗口拼接可还原窗口顺序与坐标。
- **P2 SQLite 并发与迁移**：连接统一开启 `PRAGMA journal_mode=WAL` + `busy_timeout=5000`（构建与查询并发不再 database is locked），与项目其他 SQLite store 一致；新增列全部走 `addColumnIfMissing` 幂等迁移。
- 测试：语义模块新增 19 例（总 59 例）：字符串/枚举 EQ/NE、normalizedValue 不一致拒绝、normalizedUnit 保留与回退、BETWEEN 数值事实拒绝、NPE 分类、千分位解析、Token 预算预检、sourceRevision 顺序不敏感、active revision 对齐与隔离、FAILED 构建不激活、窗口坐标排序持久化、重试不突破剩余预算。
- 说明：Review 第七条（语义结果未接入检索链路）属 Phase 3+ 范围，本批次不处理；模块仍仅作离线标注管线使用。

### Added（0.9.5 — Review 第三批：语义候选接入多源检索）

落地 Review 第三批核心链路（构建触发 → active 标注 → 候选适配 → 多源融合检索 → 冲突治理），模块仍默认全关，`candidate-retrieval-enabled` / `normative-retrieval-enabled` / `allow-inferred-candidate` 三个开关开始被真实消费：

- **语义构建 HTTP API**：新增 `RequirementSemanticBuildController`（`app.rag.requirement-semantic.enabled=true` 时装配）——`POST /api/requirement-semantic/builds` 触发构建（`WRITE` 权限 + 项目访问控制，`retryFailedOnly` 只重跑失败项），`GET /builds/latest` 轮询最近构建状态；`ApiExceptionHandler` 把 `RequirementSemanticException` 映射为 400 + 稳定 `SEMANTIC_*` code。
- **REQUIREMENT_SEMANTIC 来源类型**：`SourceType` 新增枚举值；`SourceFilterStrategy` 把语义候选加入 NORMATIVE/PARAMETER/VALIDATION/IMPACT/CONSISTENCY/GENERAL 意图（DOUBT 除外）；`MultiSourceCandidateAdapter` 新增意图感知 `load` 重载（默认实现保持旧契约）。
- **语义候选适配器**：`RequirementSemanticCandidateAdapter` 把 active 构建下的成功标注（实体/条件/数值事实/Claim 候选）投影为统一 Claim 参与多源检索——新增 `SQLiteRequirementSemanticStore.listActiveByProjectVersion`（与构建代际表按 revision+模型+Prompt+Schema 对齐连接，非 active 构建标注不可见）；治理边界：状态固定 `EXTRACTED`（单独出现时结论只能是 SUPPORTED，不会推成 CONFIRMED）、EXPLICIT 权威最高 SECONDARY（原文 Chunk 才是 PRIMARY）、INFERRED/UNKNOWN 默认不进候选、NORMATIVE 意图需显式开启 `normative-retrieval-enabled`、重叠窗口重复事实按（主体|谓词|值|单位|值类型）折叠、存储故障降级为空候选不阻断检索；factKey/subject|predicate 与参数表等来源对齐。
- **冲突治理**：`MultiSourceConflictAnalyzer` 新增需求语义候选与参数表的不一致检测（`REQUIREMENT_PARAMETER` 冲突，语义侧带候选标记），代码/参数表在来源稳定排序中天然优先于语义候选（CODE 与对齐层 > 语义候选）。
- **修复**：标注失败 outcome 的 token 估算不再为 0（按输入 token × 实际调用次数计入预算，失败重试不再突破 Token 上限）。
- **测试**：新增 16 例——适配器投影/门禁/INFERRED 过滤/active 隔离/窗口折叠/故障降级（7），端到端检索集成（语义候选进入结果、与参数表值冲突被报告且结论 CONFLICTED、NORMATIVE 默认不可见、语义单独出现只 SUPPORTED、CODE 同分优先，5），Controller 委托与访问控制（2），`enabled=true/false` 两种配置下整条语义 Bean 链装配/不装配（2）。
- 未实现：`vector-index-enabled`（语义向量写入 Qdrant + 向量候选召回）仍为预留开关，当前无消费方；RetrievalPipeline 文档分支未接入语义候选（避免影响既有 Recall，待金标评测后灰度）。

### Fixed（0.9.5 — Review 第四批：构建代际与候选治理）

依据外部代码 Review（P0×2 / P1×4 / P2×2）修复语义模块构建代际与候选事实治理问题：

- **P0 删除 alignSourceRevision 全范围批量更新**：新增 `requirement_semantic_build_input(build_id, source_chunk_id, window_id, content_hash)` 构建输入表；构建完成时保存当前输入集合，`listActive` / `listActiveByProjectVersion` 通过构建输入 join 严格限定 active 标注（source_chunk_id + content_hash + coalesce(window_id,'')），已删除/过期窗口的旧成功记录不再被重新激活；旧记录的 `source_revision` 不再被批量改写。
- **P0 窗口策略纳入构建身份**：`sourceRevision` 现在包含 `maxInputChars` / `windowOverlapChars` / `WindowPlanner` 类名 / 结构感知标记，窗口策略变化会生成新 buildId 与新 active 代际，避免旧窗口结果与新窗口混合。
- **P1 只有 SUCCESS 构建切换 active**：`PARTIAL_FAILURE` / `FAILED` 不再接管线上结果；active 查询额外要求 `b.build_status='SUCCESS'`。
- **P1 使用 SemanticClaimCandidate.factKey**：候选适配器优先使用模型输出的领域 `factKey`（如 `growth_fund.unlock.min_level`），不再一律用 `project|version|subject|predicate` 丢弃模型输出；该 factKey 未经领域词汇表校验，后续由 `BusinessConceptService`/人工词汇表归一化。
- **P1 allow-inferred-candidate 默认关闭**：`application.yml` 默认值改为 `false`，并新增 `RequirementSemanticPropertiesTest` 绑定默认值断言。
- **P2 Controller 空请求体防护**：`RequirementSemanticBuildController.build` 对 `null` 请求返回稳定 `SEMANTIC_REQUEST_INVALID`，不再 NPE。
- **P2 contentHash fallback**：`sourceRevision` 对缺失 contentHash 的 Chunk 回退到归一化父块正文 SHA-256，避免内容变化被忽略。
- 测试：更新语义 Store/Build/候选检索测试以覆盖构建输入过滤（已删除 Chunk 不可见、复用 Chunk 仍可见、FAILED 构建不激活）、窗口策略 revision、Controller 空请求。
- 全量测试：815 tests 通过。

### Fixed（0.9.5 — Review 第五批：候选治理补强）

- **P1 语义存储异常不再伪装成空结果**：`RequirementSemanticCandidateAdapter` 对存储/查询失败抛出稳定 `SEMANTIC_CANDIDATE_LOAD_FAILED`；`MultiSourceSearchService` 逐适配器捕获并写入 `RagWarning`，单来源故障不阻断其他来源，但不再静默“无语义结果”。
- **P1 候选加载按 query 过滤**：新增 `SQLiteRequirementSemanticStore.listActiveByProjectVersion(projectId, version, limit, query)`，对 `semantic_summary / semantic_text / result_json` 做词项 LIKE 过滤；适配器把 query 传入并提高上限到 5000，缓解固定截断导致后段相关候选不可见的问题。
- **P2 同来源多值冲突**：`MultiSourceConflictAnalyzer` 不再只取每组第一条，对同一事实同来源多个不同值生成 `VERSION_INTERNAL` 内部冲突；`conflictGroups` 同步纳入内部冲突分组；新增 `MultiSourceConflictAnalyzerTest`。
- 全量测试：817 tests 通过。

### Fixed（0.9.5 — Review 第六批：构建代际生命周期与召回修复）

依据外部代码 Review（P0×1 / P1×4 / P2×2）修复构建生命周期与候选召回问题：

- **P0 同 buildId 失败重跑不再覆盖 active 成功构建**：构建执行与代际从数据模型上分离——新增 `requirement_semantic_build_run` 表（run_id 每次执行唯一，含状态/统计/warnings/时间戳）；`saveBuild` + `saveBuildInputs` 合并为新的 `recordBuildRun(run, inputs)` 单事务方法：SUCCESS 才切换代际 active（先取消同范围其他 active）；非 SUCCESS 且同 buildId 已有 active 代际时只记录 run、不触碰代际行与输入集合；非 SUCCESS 且无代际/代际非 active 时写入 inactive 代际。旧实现 `insert or replace` 按主键覆盖会把 SUCCESS/active=1 行改成 PARTIAL_FAILURE/active=0，一次失败重跑即清空线上语义候选——已修复并有回归测试锁定。
- **P1 构建发布原子化**：run 记录、代际行 upsert（update-else-insert，避免 `insert or replace` 的 ON DELETE CASCADE 清空输入与 run 历史）、active 切换、输入集合重建在同一 SQLite 事务内完成；消除"active 构建存在但输入未写入"的查询窗口与"输入保存失败导致 active 空构建"的风险。`latestBuild` 改为返回最新 run 的状态/统计（join 代际行取 active 与元数据），构建轮询能看到失败重跑的真实结果，同时 `active` 字段仍反映当前生效代际。
- **P1 中文查询预过滤召回修复**：`likeTerms` 与 `MultiSourceSearchService.tokenize()` 同策略（整词 + CJK 2-gram，上限 50 词项防御超长查询）；SQL 词项间由 AND 改为 OR 宽召回——"成长基金冷却时间"（无空格、语义文本为"成长基金的冷却时间为30秒"）不再因整体匹配失败漏召回，"成长基金 冷却时间" 不再要求两词出现在同一条标注（不同窗口各含一词均可进入候选，最终相关性由内存评分决定）。
- **P1 检索 warning 不再暴露异常原文**：适配器加载失败对外只返回稳定码 `MULTI_SOURCE_CANDIDATE_LOAD_FAILED:<SOURCE_TYPE>`，异常类型记入服务端日志；异常消息中的路径/SQL/provider URL 等内部信息不再进入 API 响应。
- **P1 候选去重键纳入 factKey**：重叠窗口折叠键由（主体|谓词|值|单位|值类型）扩展为（factKey|主体|谓词|值|单位|值类型），不同领域事实（`growth_fund.reward_currency` 与 `lottery.reward_currency`）拥有相同字段值时不再被错误吞并。
- **P2 内部冲突值归一化**：同来源多值冲突判定改用"数值（去尾零、千分位）+ 单位别名（秒/s、分钟/min、小时/h、天/d、%）"的规范化比较——"30秒"与"30.0秒"、"1,000"与"1000"不再误报冲突；单位不同（秒 vs 分钟）仍判冲突（不做跨单位换算，避免掩盖真实不一致）。
- **P2 候选上限可配置化**：新增 `app.rag.requirement-semantic.max-candidate-annotations`（默认 5000，100~100000），命中上限输出 `SEMANTIC_CANDIDATE_TRUNCATED` 警告日志；分页加载/FTS5/语义向量召回留待后续批次。
- 测试：新增 11 例——同 buildId 失败重跑保留 active 成功代际（输入集合不清空、latestBuild 显示最新 run）、SUCCESS 重跑刷新统计、首个 FAILED run 建 inactive 代际、中文无空格 2-gram 召回、多词 OR 宽召回、不同 factKey 不去重、warning 稳定码（不含异常原文）、数值等价/单位别名不产生内部冲突、单位不同仍冲突、max-candidate-annotations 默认值；另修复 2 个无效测试（mock 未命中 4 参重载的 vacuous 存储故障测试改为断言稳定异常；窗口折叠测试原先因 saveBuild 级联清空输入而"碰巧"通过，现改为同一构建输入集合下的真实去重）。
- 全量测试：828 tests 通过。
- 未实现（Review 第三批建议）：SQLite FTS5、semantic_text 向量索引、统一候选融合排序、active generation 并发集成测试。

### Fixed（0.9.5 — Review 第七批：旧库迁移、截断可观测性与冲突一致性）

依据外部代码 Review（P1×4 / P2×2）修复构建状态可查询性、候选截断可观测性与冲突判定一致性：

- **P1 旧库升级后 latestBuild 查不到既有构建**：`initialize()` 在创建 `requirement_semantic_build_run` 后执行幂等回填——为没有 run 记录的既有构建补一条 `migration:<buildId>` run（created_at 取 coalesce(finished_at, started_at, now)，保证后续真实 run 排序在其后）；升级后的 `latestBuild` 可返回升级前最后一次构建的状态且 active 查询不受影响；新增真实升级场景测试（手工建旧版 build 表 → 新版 Store 初始化 → latestBuild 返回迁移记录，重启不重复回填）。
- **P1 候选截断可观测性**：适配器改用 `limit + 1` 精确探测截断（替代 `size >= limit` 启发式），截断时除日志外返回 `SEMANTIC_CANDIDATE_TRUNCATED` 非致命警告；`MultiSourceCandidateAdapter` 新增 `loadDetailed` 默认方法与 `CandidateLoad(claims, warnings)` 结果类型，检索层把适配器警告并入 `MultiSourceSearchResponse.warnings`——上层不再把"候选被按文档位置截断"误读为"没有召回能力"（截断候选仍按文档序截断，FTS5/BM25 相关性排序留待后续）。
- **P1 跨来源与内部值比较统一归一化**：删除仅剥离 `%/分钟/min/逗号` 的旧 `sameValue`+`decimal`，跨来源（需求-参数、语义-参数、参数-测试）与内部冲突共用"值 + claim 单位联合归一化"——`30秒` vs `30s`、`5分钟` vs `5min`、`2小时` vs `2h`、`1,000` vs `1000` 跨源不再误报冲突；语义数值事实的分离单位（value=`5`+unit=`分钟`）与参数表内嵌单位（`5分钟`）等价；单位不同（秒 vs 分钟）仍判冲突（不做跨单位换算）。
- **P1 冲突分组与去重的 factKey 语义一致**：分组改为双维度——内部冲突（VERSION_INTERNAL）按 factKey 分组（不同领域事实如 `growth_fund.reward_currency` 与 `growth_fund.vip_reward_currency` 不再被 subject|predicate 误并组产生假冲突与假惩罚）；跨来源冲突维持 subject|predicate 对齐（参数表/测试用例/需求图的 factKey 口径尚不一致，统一词汇表对齐前不能只靠 factKey 跨源分组——`MultiSourceKnowledgeRoutingTest` 锁定的 PARAMETER_TEST 行为保持不变）；`conflictPenalty` 改用分析器公开的 `groupKeys`（内部 + 跨源两个维度取并集），不再复制一份分组逻辑。
- **P2 latestBuild 返回模型拆分执行与代际状态**：新增 `SemanticBuildStatusView`（runId / latestRunStatus / generationActive / activeGenerationStatus + 最新 run 统计与代际元数据），`GET /api/requirement-semantic/builds/latest` 改返回该视图——"最新执行 PARTIAL_FAILURE 但成功代际仍在线"不再依赖单字段组合推断，同 buildId 多次重跑可从 runId 追踪具体执行。
- **P2 注释与 SQL 实际行为对齐**：`listActive` / `listActiveByProjectVersion` javadoc 不再声称"绑定 source_revision"——实际 join 是构建元数据 + 输入集合，revision 变化时内容未变的 Chunk 允许复用旧标注（by design）。
- 测试：新增 10 例（旧库迁移升级 + 重启幂等、limit+1 截断探测与未超限对照、截断警告进入检索响应、不同 factKey 同 subject|predicate 不冲突、factKey 分组惩罚隔离、跨源秒/分钟/小时单位别名等价、分离单位与内嵌单位等价、跨源不同值仍冲突、跨源 factKey 口径不一致仍按 subject|predicate 对齐）；适配器/Controller/Store 测试同步迁移到视图与 loadDetailed 契约。
- 全量测试：839 tests 通过。
- 未实现：SQLite FTS5/BM25 相关性排序（截断仍按文档位置）、跨源 factKey 统一词汇表对齐（BusinessConcept 映射）、金标样本与 Recall@K/误冲突率/截断率评测。

### Fixed（0.9.5 — Review 第八批：状态语义、run 排序与跨页冲突）

依据外部代码 Review（P1×4 / P2×2）修复构建状态语义、run 排序稳定性与冲突判定的分页/对齐问题：

- **P1 activeGenerationStatus 不再误表达**：`SemanticBuildStatusView` 的 `activeGeneration*` 三字段（buildId / sourceRevision / status）改为通过 LEFT JOIN 查询同范围内**真正 active** 的代际，不再返回最新 run 所属代际的状态——"rev-1 SUCCESS active + rev-2 FAILED inactive" 时正确返回 `latestRunStatus=FAILED, generationActive=false, activeGenerationStatus=SUCCESS`（此前会误导性地返回 FAILED）；无 active 代际时三字段为 null。新增状态语义测试锁定。
- **P1 run 排序改用 epoch 毫秒**：run 表新增 `created_at_epoch_ms integer not null default 0`（DDL + addColumnIfMissing 迁移 + 按 created_at 回填旧数据 + 迁移 run 一并写入 epoch），`latestBuild` 改按 `created_at_epoch_ms desc, rowid desc` 排序——`Instant.toString()` 小数精度可变（"Z" 字符排序高于"."）且迁移数据是 `datetime('now')` 格式，字符串排序不等价于时间排序，可能选错最新 run；同毫秒连写由 rowid 决胜保证返回最后插入的 run（有测试）。
- **P1 跨源冲突区分确定与疑似对齐**：跨源配对（需求-参数 / 语义-参数 / 参数-测试）双方 factKey 一致 → 确定冲突（消息不变）；不一致 → 消息加 `POTENTIAL_CROSS_SOURCE_CONFLICT:` 前缀，明示配对只是 subject|predicate 推测而非确定对齐（如两个不同领域事实共享 subject|predicate、参数表实际对应其中一个时，不再直接认定为确定冲突）——待 BusinessConcept/统一词汇表对齐后升级。既有跨源冲突检测行为保持（Routing 回归通过）。
- **P1 冲突状态不再随分页改变**：`AnswerStatus` 改为按本查询全部命中候选（scored 全集）计算，`conflicts` 仍只返回当前页涉及的冲突详情，新增响应字段 `hasConflictsOutsidePage` 提示页外存在冲突——"第 1 页 CONFLICTED、第 2 页才出现冲突"的翻页不一致消除（有跨页一致性测试：两页状态一致、单条页面 conflicts 为空但页外标志为 true、全量单页详情可见且标志为 false）。
- **P2 /builds/latest 响应模型变更说明**：`SemanticBuildRecord` → `SemanticBuildStatusView` 属破坏性 JSON 契约变更，但该端点与语义模块同批引入、`enabled` 默认关闭且从未发布，无既有调用方依赖旧字段，不加兼容层。
- **P2 召回截断仍按文档位置**：`SEMANTIC_CANDIDATE_TRUNCATED` 只解决可观测性；相关候选可能未进入评分阶段的问题需 FTS5/BM25 或按文件配额，留待后续批次。
- 测试：新增 5 例（旧 active+新 failed 状态视图、同毫秒连写 latestBuild、factKey 未对齐 POTENTIAL×2、跨页冲突状态一致），更新既有断言（无 active 代际时 activeGeneration* 为 null、确定冲突用对齐 factKey 验证无 POTENTIAL 前缀）。
- 全量测试：844 tests 通过。
- 未实现：BusinessConcept 驱动的跨源 factKey 统一对齐（POTENTIAL 升级为确定）、FTS5/BM25、按文件配额的候选截断。

### Fixed（0.9.5 — Review 第九批：冲突分级生效与集合比较）

依据外部代码 Review（P1×3 / P2×3）让 POTENTIAL 冲突分级真正生效、跨源比较与输入顺序无关：

- **P1 POTENTIAL 冲突不再影响最终状态与排序**：`resolveStatus` 区分确定冲突与 `POTENTIAL_CROSS_SOURCE_CONFLICT`——仅 POTENTIAL 时结论最多 `REVIEW_REQUIRED`，不再被推成 `CONFLICTED`（即使参数表等 PRIMARY 来源在场）；`conflictGroups` 只收录确定冲突分组（内部 factKey 冲突 + factKey 对齐且双方单值的跨源冲突），POTENTIAL 不再参与 conflictPenalty 扣分——上一批只加了消息前缀，状态与惩罚仍按确定冲突处理，与注释语义不符，本批对齐。
- **P1 跨源比较改为按来源"值集合"，不再取每种来源第一条 Claim**：`firstType` 全部移除，配对（需求-参数、语义-参数、参数-测试）改为规范化值集合比较——集合相同→无冲突；集合不同且双方各自唯一且任一 Claim 对 factKey 相等→确定冲突；否则（factKey 未对齐，或任一来源存在多个不同值）→ POTENTIAL 并附"某来源存在多个值，需人工复核"。参数表存在 30秒/60秒 两值时不再因输入顺序得到"冲突/不冲突"两种结论；展示值按字典序排序，冲突消息内容与 Claim 输入顺序完全无关（有正反序对照测试）。
- **P1 hasConflictsOutsidePage 改集合差集**：由数量比较（`queryConflicts.size() > conflicts.size()`）改为"全量冲突中存在不在当前页冲突集合中的条目"——数量相同但内容不同、多值消息文本变化等场景不再误判。
- **P2 active 代际唯一性数据库约束**：初始化先修复历史脏数据（同 project/document/version 保留 rowid 最新一条 active，其余置 0），再创建 partial unique index `uq_req_semantic_active_generation`（where active=1）——并发构建/进程中断/手工修复产生的多条 active 会被修复并从数据库层杜绝；`latestBuild` 的 LEFT JOIN 不再因多行 active 随机取值。有"旧版无索引库两条 active → 初始化修复 → 再激活被拒"测试。
- **P2 /builds/latest 兼容字段**：`SemanticBuildStatusView` 增加 `@Deprecated buildStatus()`（=latestRunStatus）与 `@Deprecated active()`（=generationActive）兼容访问器——依赖旧 `SemanticBuildRecord` JSON 字段（buildStatus/active）的调用方不失效，新字段语义更准确，逐步迁移。
- 测试：新增 6 例（POTENTIAL 状态不越级、POTENTIAL 不惩罚+确定对照、跨源结论顺序无关、多值场景 POTENTIAL（factKey 对齐亦然）、旧库重复 active 修复+唯一索引拒绝）；跨页/检索集成断言同步更新。
- 全量测试：849 tests 通过。
- 未实现：BusinessConcept 驱动的跨源 factKey 统一对齐（POTENTIAL 升级为确定）、FTS5/BM25 相关性排序与按文件配额（候选仍按文档位置截断）。

### Fixed（0.9.5 — Review 第十批：兼容字段序列化与生命周期硬约束）

依据外部代码 Review（P1×2 / P2×2）修复兼容字段的 Jackson 序列化与 active 生命周期硬约束：

- **P1 兼容字段真正出现在 JSON 中**：`SemanticBuildStatusView` 的 `buildStatus()` / `active()` 补 `@JsonProperty`——普通无 get 前缀方法 Jackson 默认不序列化，上一批只加 `@Deprecated` 访问器时旧字段实际不在 HTTP 响应里（review 实测确认）；新增 MockMvc JSON 测试断言 `$.buildStatus` / `$.active` 与新字段同时输出。
- **P1 Store 层强制"只有 SUCCESS 才能 active"**：`recordBuildRun` 不再信任调用方传入的 `active` 标记，一律由 `buildStatus == SUCCESS` 推导（review 推荐方案：模型中不再存在可互相矛盾的 buildStatus/active 双字段）——FAILED/PARTIAL_FAILURE 即使误传 `active=true` 也不会取消既有成功代际或发布为线上代际；`updateGenerationRow` / `insertGenerationRow` 的 active 改为推导值传入。语义修正："SUCCESS 但不发布" 从合法状态变为矛盾状态（既有测试用该状态表达"不接管"，已改为 PARTIAL_FAILURE 真实场景）。
- **P2 旧库 active 清理改时间优先**：重复 active 修复从 `max(rowid)` 改为按 `coalesce(finished_at, started_at)` epoch 降序 + rowid 决胜——rowid 只代表插入顺序，导入/人工修复/重写场景下较大 rowid 未必是较新构建；新增"rowid 大但 finished_at 旧 vs rowid 小但 finished_at 新"测试锁定保留时间更新者。
- **P2 结构化 ConflictFinding**：维持挂起（review 亦定位为长期项）；当前字符串前缀分级已被 resolveStatus/conflictGroups 正确消费，后续重构时同步引入 conflictId 供 hasConflictsOutsidePage 使用。
- 测试：新增 3 例（JSON 兼容字段、FAILED+active=true 不发布且保留既有 active、时间优先清理），修正 1 例语义过时测试；全量 852 tests，0 失败 0 错误（含 `McpHttpIntegrationTest`，该测试对环境端口绑定权限敏感，受限环境下会出现非业务断言的启动错误）。

### Added（0.9.5 — 语义召回前端接入，方案 `docs/semantic-retrieval-frontend-integration-plan-0.9.5.md` 第一批）

在知识库"检索测试"页面并行接入语义 Claim 检索（Phase 1-4 前端部分），传统 Chunk 检索链路与协议完全不变：

- **三种检索模式**（`knowledge.html`）：新增"传统 Chunk / 语义 Claim / 对比检索"模式切换；传统模式保留原有表单、指标、阶段明细与结果展示不动；切换模式清空其它模式状态，避免结果串联残留（方案风险 5）。
- **API 封装**（`knowledge-api.js`）：新增 `semanticSearch`（POST /api/knowledge/multi-source/search）、`semanticBuildStatus`（GET /api/requirement-semantic/builds/latest）、`buildSemantic`（POST /api/requirement-semantic/builds，超时 10 分钟），全部复用 `NexusApi.request`（API Key 注入/超时/错误结构一致）。
- **语义构建状态提示**：语义/对比模式顶部常驻状态条，按 latestRunStatus/generationActive/activeGenerationStatus 分级展示——已发布（含构建 ID 与 sourceRevision）、未启用或未构建（404 区分）、部分失败（"仅供调试"）、失败（"不能伪装成无召回"）、成功代际在线但最新 run 失败（区分执行与代际）；无 active 代际时不再显示"无结果"假成功。
- **Claim 结果展示**：来源类型彩色标签（需求/需求语义/参数表/测试用例/测试结果/存疑/代码/Wiki）、factKey、subject·predicate·value·unit、claimId/valueType/authority/version；点击展开证据详情，evidenceLocation 缺失时显式"证据不可回查"；结果摘要含 answerStatus（结论状态）、命中数/总数、耗时、冲突/存疑计数；分页（上一页/下一页）+ 页外冲突提示（hasConflictsOutsidePage）。
- **冲突/存疑/关系/警告**：冲突区分"确定/疑似"（POTENTIAL_CROSS_SOURCE_CONFLICT 前缀翻译为"疑似"标签并去前缀展示）；OPEN/UNDER_DISCUSSION 存疑使用独立警示底色与状态标签，不与确认事实混排；跨源关系只读展示；warnings 稳定码翻译（截断/来源加载失败/未启用），不展示异常原文。
- **对比检索**：同一查询并行调用旧 Chunk 与语义接口（相同 projectId/version/query/limit），双栏展示（移动端上下排列），各自独立耗时/状态/错误提示——一侧失败不覆盖另一侧。
- **人工评测闭环（第一阶段 localStorage）**：每个 Claim 支持"相关/部分相关/不相关"标记（再次点击取消、切换替换），查询级"漏召回"标记；记录 query/mode/projectId/version/buildId/sourceRevision（绑定构建代际）；支持导出 JSON 与清空，页面刷新后标记不丢失。
- **状态词条**（`status-contract.js`，纯新增）：多源 answerStatus/KnowledgeStatus 枚举的中文标签/字形/tone 映射。
- 说明：按方案非目标，本批未实现 claimHits 评分/Recall 自动计算（Phase 5）、评测落库（第二阶段）、semantic_text 向量索引；生产默认开关不变（语义能力仍默认关闭，本地经 `REQUIREMENT_SEMANTIC_ENABLED=true` 等环境变量开启）。
- 验证：改动仅前端静态资源（html/js/css）与共享状态词条，`node --check` 语法通过；后端接口测试回归通过（MultiSourceKnowledgeController/RequirementSemanticBuildController/MultiSourceSearchService，本次未修改 Java）。

### Fixed（0.9.5 — Review 第十一批：语义检索前端默认链路与冲突状态修复）

依据外部代码 Review（P1×5 / P2×3）修复语义检索前端的默认链路可用性与冲突状态正确性，默认流程不再出现“假性无结果”：

- **P1 需求知识库默认版本为空导致语义请求 400**：`KnowledgeBaseView` 新增明确的 `requirementDocumentId` / `latestRequirementVersion` 字段（Controller 从 `BusinessProject` 透传，不让前端猜测 `publishedRevision` / `targetRevision` 语义）；前端版本回退链 `publishedRevision → targetRevision → latestRequirementVersion`，多源接口的 `@NotBlank version` 不再被空值触发校验失败。
- **P1 语义构建状态查询用错 documentId**：前端不再把展示名称（如“Immortal 需求”）当文档 ID，改用后端返回的 `requirementDocumentId`（如 `fengshen`）——构建已成功时不再显示“尚未执行语义构建/语义模块不可用”。
- **P1 文档 ID 输入框无实际过滤作用（采纳 Review 方案 A）**：移除该输入框，改为只读“语义范围”展示（项目 / 版本 / 全部文档或文档 ID）；构建状态内部使用后端返回的文档 ID，语义候选仍按 projectId+version 跨文档召回（后端 `MultiSourceSearchRequest` 未加 documentId，真正过滤待后续按需实现）。
- **P1 语义 Claim 自然语言信息在评分阶段被丢掉**：`UnifiedKnowledgeClaim` 新增 `searchText` 字段（兼容旧构造器），语义适配器把 `semanticSummary`（回退 rawText）传入；评分阶段 searchText 作低权重兜底（整句命中 +1.5、token 命中 +0.75）——数据库预过滤命中摘要但结构化字段（subject/predicate/value）未含查询词时候选不再被归零过滤，查询“玩家达到指定等级后什么时候可以领取奖励”可命中摘要中完整表达的事实。
- **P1 人工评测标记跨版本/代际污染**：评测键统一为 projectId|version|buildId|sourceRevision|query|mode|resultId，5.0 与 5.1 同 query 同 claimId 的判断互不串用，重建后旧标注不再显示在新结果上；评测上下文记录完整键字段供导出分析。
- **P2 代码知识库隐藏语义/对比入口**：`semanticAvailable()`（type!==CODE）控制模式切换按钮显隐，进入检索页重置为传统模式——代码库不再出现无需求版本可填的语义表单。
- **P2 冲突状态被无关失败测试污染（附本批发现的残留 bug）**：`resolveStatus` 的确定冲突分组判定不再解析冲突消息文本（原实现 `substring(index+"factKey=".length())` 会把分组键后的描述文本误当分组键，导致 factKey 对齐的确定冲突永远升不到 CONFLICTED，端到端语义-参数冲突测试实际已被此 bug 破坏）；改为复用 `conflictGroups` 同一套分组判据（内部 factKey 冲突 + factKey 对齐跨源冲突/失败测试），孤立失败测试结果（组内只有 TEST_RESULT）不因候选集其它位置有 PRIMARY 而越级 CONFLICTED。
- **P2 构建状态查询未等待与请求竞态**：语义检索先 `await loadSemanticBuild()` 再发起搜索（状态条不再短暂误显“不可用”）；`requestId` 序列号丢弃旧响应，快速连点不再被旧结果覆盖；对比模式同样等待构建状态并使在飞语义请求失效。
- 测试：新增 2 例（factKey 对齐确定冲突→CONFLICTED、孤立失败测试→REVIEW_REQUIRED）；修复后端到端检索集成 2 个被残留 bug 破坏的断言（语义-参数值冲突、跨页冲突状态一致）恢复通过；全量 854 tests，0 失败 0 错误；前端三个 JS `node --check` 语法通过。
- 说明：结构化 `ConflictFinding`（conflictId/groupKey/claimIds/severity）仍为长期项，当前字符串前缀分级已被 resolveStatus/conflictGroups/前端“疑似”标签正确消费。

### Fixed（0.9.5 — Review 第十二批：评测数据可信度与聚合状态补强）

依据外部代码 Review（P1×3 / P2×3）修复评测数据可信度问题——配置关闭、表单修改、分页 rank 三类失真源全部堵住，并补齐聚合构建状态的契约测试：

- **P1 语义候选关闭时静默返回空结果**：`RequirementSemanticCandidateAdapter.loadDetailed` 在 `candidate-retrieval-enabled=false` 与 NORMATIVE 拦截时不再返回空 `CandidateLoad`，改为携带稳定警告码 `SEMANTIC_CANDIDATE_RETRIEVAL_DISABLED` / `SEMANTIC_NORMATIVE_RETRIEVAL_DISABLED`；`/api/requirement-semantic/builds/aggregate` 新增 `candidateRetrievalEnabled` / `normativeRetrievalEnabled` 透传开关；前端状态条在开关关闭时明确提示“构建已发布但候选不参与检索，本次结果不得作为评测数据”，`markJudgement` / `markMissedRecall` 对 SEMANTIC 模式直接拒绝写入评测并提示。
- **P1 评测标记绑定可编辑表单而非已执行请求**：`runSemanticSearch` 请求发起时保存不可变 `semantic.responseContext` 快照（projectId/version/query/intent/limit/page/activeBuildIds/activeDocumentCount），后端分页元数据返回后合并真实 page/limit；`evaluationKey` / `evaluationContext` / 分页 rank 全部改读快照——用户改查询/版本但未重新检索时，旧结果不再被记为“新查询/新版本”的评测数据。
- **P1 第二页以后评测 rank 错误**：`knowledge.html` 评测按钮改用 `index + 1 + page * limit`（快照中的真实分页元数据），第二页第一条保存为 rank=11 而非 1，Precision@K / MRR / 首个相关位置不再失真。
- **P2 对比检索两侧耗时统一为慢速链路**：`runCompareSearch` 改为每个 Promise 自身 finally 记录独立耗时（错误请求也记录），不再等 `allSettled` 后统一计算——传统 100ms / 语义 5s 时两侧显示各自真实耗时，可正确对比。
- **P2 compare.requestId 真正生效**：`runCompareSearch` 开头生成 `compare.requestId`，所有 then/catch 写入前校验仍为当前请求；切换检索模式时递增 `compare.requestId` 吊销在飞对比请求并重置 loading——慢返回旧请求不再写入 `compare.*`，也不阻塞其它模式提交。
- **P2 构建状态异常分支仍可写入旧上下文**：`loadSemanticBuild` 抽取统一 `stillCurrent()`（requestId + projectId + version 三项），成功/异常/finally 三支共用——请求期间改版本但未触发新状态请求时，旧请求失败不再落到新版本页面。
- **收口：`semanticAvailable` 去重**：删除 methods 中的重复定义，仅保留 computed（模板已正确引用 computed，删除避免 Vue 选项冲突与误改维护）。
- **收口：测试补强**（新增 11 例）：Controller 聚合接口 JSON 字段（含 retrieval 开关）/ 空体（200 null）/ 访问控制拒绝不吞异常（3）；Store 聚合跨文档 active 计数、FAILED 不计入 active、顺序稳定可重入、无 run 空、项目/版本隔离（5）；语义适配器关闭警告码契约（2）；`searchText` 被 `@JsonIgnore` 排除在序列化 JSON 之外（1）。
- 遗留：知识完整性（expectedDocumentCount/coverageStatus=COMPLETE/PARTIAL/EMPTY）需业务文档清单支撑，留待正式评测强调完整性时再引入；`latestRunWarnings` 已可携带截断/禁用等稳定码供前端消费。

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
  - **文档级需求抽取（四次迭代垂直切片）**
    - Phase 0+1：`RequirementGraphWindowPlanner` 新增 `PlanOptions`（minWindowChars / minProgressChars / maxWindowCountPerParent / 结构感知边界），无短窗、保证最小推进、超窗保留尾部不丢内容。
    - Phase 2：新增 `DocumentStructureExtractor`（标题/编号需求/表格行/列表 → 结构树 + 不可变 `SourceAnchor`）与 `LogicalUnitPlanner`（REQUIREMENT/TABLE/LIST 逻辑单元不拆散）。
    - Phase 3+4：新增 `CrossWindowIntegrator`（REQ 编号引用 → 跨窗口候选，缺目标端降级 `UNAVAILABLE`，绝不当作已证实）、`EvidenceComposer`（DIRECT/COMPOSITE_SUPPORTED/INFERRED/UNAVAILABLE 证据包）、`BuildFingerprintFactory` 与 `RequirementDocumentStructureStore`（结构/锚点/单元/证据包/指纹持久化）。
    - 新增 `DocumentLevelBuildService` 与 `RequirementDocumentLevelController`（`/api/requirement-graphs/document-level/*`：build/structure/units/bundles）。
    - **LLM 局部实体抽取与跨窗二次验证接入**：新增 `LocalEntityExtractor` / `CrossWindowVerifier` SPI，默认 `Rule*` 实现；`LlmLocalEntityExtractor` / `LlmCrossWindowVerifier` 通过 `app.rag.document-level.llm-enabled=true` 启用（默认关，`@ConditionalOnProperty` 保证单实现注入，任何 LLM 失败 fail-open）。LLM 只确认/拒绝候选，只发送两端证据片段，不能创建新实体或伪造证据；未确认候选降级 `INFERRED + CANDIDATE`，不进入已证实集。
    - 新增 `RequirementGraphWindowPlannerSafetyTest`、`DocumentStructureExtractorTest`、`DocumentLevelBuildServiceTest`、`RequirementDocumentLevelControllerTest`。
  - **对齐关系人工审核生命周期（Phase 5）**
    - `CodeCentricAlignmentStore` 新增 `findAlignmentRelationById` / `reviewAlignmentRelation`（HUMAN_CONFIRMED / REJECTED / STALE），`CodeCentricAlignmentController` 新增 `POST /api/knowledge/alignment/alignment-relation/review`。
  - **需求语义图金标评测器**
    - 新增 `RequirementGraphGoldLoader` / `RequirementGraphGoldEvaluator` / `RequirementGraphGoldPredictor`（SPI）与 `RuleGoldPredictor`、`LlmGoldPredictor`（可插拔，LLM fail-open）。
    - 支持按场景聚合：实体/关系/Claim Precision/Recall/F1、负例错误率、存疑召回、代码事实召回、金标证据可回查率；`RETRIEVAL_TEST_CASE` 不计入抽取 F1。
    - 新增 `RequirementGraphGoldEvalIT`（`-Dgold.eval=true`，默认规则预测器，`-Dgold.llm=true` 启用 LLM，`-Dgold.limit=N` 可先跑子集验证连通性，`-Dgold.parallelism=N` 并行预测），输出 `docs/reports/requirement-graph-gold-eval-YYYY-MM-DD.md`。
    - 新增 `RequirementGraphGoldEvaluatorTest`。
    - 纳入金标数据集 `evaluation/requirement-graph-gold-v0.1` / `v0.2`（84 条，含 REAL_WINDOW_COMPOSITE/DOCUMENT_DRIFT_REVIEW/DOCUMENT_CONFLICT/负例/测试用例）作为可复现评测输入。
    - **首份 LLM 金标基线（v0.2，84 条，并行 8，deepseek-v4-flash）**：总体实体 F1=0.275、关系 F1=0.017、Claim F1≈0.064、负例错误率=0.400、存疑召回=0.308、代码事实召回=0.000；报告见 `docs/reports/requirement-graph-gold-eval-2026-08-24.md`。
    - **评测器自检与预测契约修复**：新增 `OracleGoldPredictor`（自检，实体/关系/Claim F1=1.0、负例错误率=0、存疑/代码/漂移=1.0）与 `EmptyGoldPredictor`（空基线）并在报告中并列展示；`Prediction` 扩展 `PredictedCodeFact` / `DriftDecision` / `PublicationDecision` / `PredictionStatus` / `errorCode` / `latencyMs` / `retryCount`；`LlmGoldPredictor` 不再吞异常，返回 SUCCESS/EMPTY_RESULT/FAILURE + errorCode + latency；指标新增漂移准确率，`goldEvidenceTraceabilityRate` 改名 `goldEvidenceFieldCompletenessRate`；报告输出 predictionStatusCounts 与平均延迟。
  - **测试**
    - 新增/更新对齐与文档级用例（含窗口安全、结构抽取、文档级构建、关系审核、文档级控制器、金标评测器）。
    - 全量测试：739 tests 通过。

### Changed

- **金标评测器 Review 修复（评测结果可信性）**：
  - **一对一匹配**：实体/关系/Claim/代码事实改为最大二分匹配（Kuhn 算法），每条 Gold 与每条 Prediction 最多匹配一次，杜绝重复预测虚高 Precision；关系/实体不再使用包含式模糊匹配。
  - **Claim 严格匹配**：`factKey && value` 双字段精确匹配（值先做归一化：秒/s、分钟/min、小时/h、天/d），不再 `keyMatch || valueMatch`。
  - **代码事实严格匹配**：要求 repository/commit/factKey/value 全匹配，新增 `codeFactPrecision` / `codeFactF1` 指标。
  - **Gold 显式 decision**：金标增加 `decision{type,status,publication,evidenceIds}`，评测与 Oracle 都逐字段精确比较期望决策，不再从 scenario 硬编码；数据集 8 条漂移场景已补写显式 decision。
  - **证据实际回查**：新增 `goldEvidenceSourceMatchRate`（quote 是否真实存在于 sourceFile）、`goldEvidenceOffsetValidityRate`（offset 是否指向 quote）、`goldEvidenceClaimSupportRate`（claim 是否有可回查证据），替代仅字段完整率。
  - **CI 质量门禁**：新增 `RequirementGraphGoldDatasetSelfCheckTest`（无需 LLM、默认跑），断言真实数据集上 Oracle 实体/关系/Claim/代码事实/漂移全 1.0、Empty 全 0；`RequirementGraphGoldEvalIT` 同步增加 Oracle/Empty 强断言。
  - **生产链路评测**：新增 `ProductionGraphPredictor`，把金标用例路由到真实 `RequirementGraphExtractionService.extract`（生产 Prompt + Schema/证据/本体校验），多窗口逐窗口抽取并按生产合并语义跨窗口整合；`LlmGoldPredictor` 更名为 `PromptExtractionBenchmarkPredictor`，明确定位为“单次 Prompt 抽取基准”，避免被误认为生产链路评测器。
  - **LLM 提示基准修复**：模型输入不再泄漏内部场景标签；代码事实采用明确输入契约（`input.codeFacts` 提供时才回写，CODE_VERIFIED 用例已补输入）；实现真实有限重试与异常分类（超时/限流/JSON/Schema/其他），`retryCount` 记录实际次数；非法 `publicationDecision` 返回 `SCHEMA_INVALID`，不再静默降级为 `NOT_PUBLISHED`。
  - **并行评测加固**：拆分 `InterruptedException` / `ExecutionException`，不再误设中断标记；单条预测超时（120s）取消该任务，避免一个卡死请求拖住整个评测。
  - **生产链路单元测试**：新增 `ProductionGraphPredictorTest`（Mockito 桩 `RequirementGraphExtractionService`），覆盖单窗口合并、链路异常上报、部分窗口失败保留成功结果。
  - **完整 BuildService 构建链路评测入口**：新增 `ProductionBuildGraphPredictor`，把金标用例合成到进程内 `RequirementSnapshotRepository` 后走真实 `RequirementGraphBuildService.build`（窗口规划 → 真实抽取 → BuildAccumulator 跨窗口合并 → Evidence → SQLite 持久化 → 快照状态流转）；IT 通过 `-Dgold.build=true` 启用（串行、单条超时放宽到 900s，临时 DB），并新增 `ProductionBuildGraphPredictorTest` 单元测试。
  - **正式关系本体进入评测约束**：新增 `RelationOntologyMapper` 与 `ontologyAlignedRelationF1`；只统计生产 `RelationType` 精确匹配，金标非本体谓词（业务属性/边界约束）单独计数，报告输出 `nonOntologyGoldRelationCount` / `boundaryConstraintGoldRelationCount`。
  - **报告可诊断性**：新增 `predictionErrorCodeCounts`（按 errorCode 计数），单条并行超时可配置（`evaluateParallel` 重载，BuildService 链路单条放宽）。

- **金标评测入口可信性（Review 第二阶段）**：
  - **禁止默认 RuleGoldPredictor**：`RequirementGraphGoldEvalIT` 必须显式 `-Dgold.predictor=rule|llm|production|build`，未指定直接失败，报告不再被误读为生产链路能力。
  - **Formal / Exploratory 双模式**：`RequirementGraphGoldLoader.GoldLoadMode.FORMAL` 只允许 `GOLD_ACCEPTED` 且漂移用例必须显式 `decision`（禁止从 scenario 推导）；`EXPLORATORY` 允许未审核记录并兼容推导（调试用）。报告输出 `evaluatedCases / acceptedCases / formalEvaluation / sourceContext`。
  - **结构完整性校验**：caseId 非空且唯一、scenario 非空、entity id 唯一、claim/relation/decision 引用的 evidenceId 必须存在、annotation.status 非空。
  - **GoldCase 携带项目上下文**：新增 `projectId / documentId / requirementVersion / annotationStatus`；`ProductionBuildGraphPredictor` 用真实 project/version 发起 BuildRequest（documentId 用合成 `gold-<caseId>` 保证用例隔离），并在报告中展示来源上下文。
  - **真实窗口元数据保留**：Build 预测器用真实 `parentId` 作为合成快照 entryId，BuildService 转 ChunkRecord 后 `parentId/parentOrder/contentHash/filename` 得以保留（offset 仍由规划器重建，注明为已知局限）。
- **生产链路评测口径（Review 第三阶段）**：
  - **去除 ProductionGraphPredictor 金标泄漏**：不再用 Gold 实体把模型输出修正回 canonical，Gold alias/类型只参与 evaluator 匹配，不参与模型结果构造。
  - **预测实体携带类型**：新增 `PredictedEntity{type, canonicalName, aliases}`；生产链路预测器输出真实 EntityType，评测要求“类型非空时类型+名称”匹配（类型为空视为未提供、放行），可识别同名不同类型误合并。
  - **严格 vs 仅成功口径**：报告新增 `strictOverall*F1`（失败/部分失败按实际内容计）与 `successfulOnlyOverall*F1`（只统计 SUCCESS），并输出 `predictionSuccessRate / partialFailureRate / failedCaseEntityRecall`。
  - **单条超时不再中断评测**：`evaluateParallel` 超时转换为该用例 `MODEL_TIMEOUT` 并继续完成其余用例；任务异常记录为 `PREDICTION_EXCEPTION`。
  - **撤销语义不安全的本体映射**：`RelationOntologyMapper` 移除 `REWARDS→USES / CONSUMES→USES / SETS_STATE→CHANGES_STATE` 等近似映射，`ontologyAlignedRelationF1` 只统计生产 `RelationType` 精确匹配（当前仅 REQUIRES），非本体/边界约束单独计数。
  - **加载器测试**：新增 `RequirementGraphGoldLoaderTest`（FORMAL 拒绝未审核、漂移 decision 要求、evidenceId 引用、caseId 重复）。
  - **报告维度边界**：PRODUCTION/PRODUCTION_BUILD 报告明确标注只覆盖 Entity/Relation/Uncertainty/Evidence/BuildStatus，Claim/CodeFact/Drift/Publication 由跨源对齐链路评测；BuildService 链路保留真实窗口 parentId/order/hash，并注明 offset 由规划器重建的已知局限。
  - **新契约 LLM 全量重跑（v0.2，84 条）**：`-Dgold.predictor=llm` 下 SUCCESS=32 / MODEL_TIMEOUT=1 / FAILURE=51，successRate=0.381；strict 口径 实体F1=0.210 / 关系F1=0.000 / ClaimF1=0.012 / 代码事实F1=0.182；successfulOnly 口径 实体F1=0.268 / 代码事实F1=0.667（CODE_VERIFIED 用例经 input.codeFacts 契约后代码事实召回=1.0）；报告见 `docs/reports/requirement-graph-gold-eval-2026-08-24.md`。
  - **评测口径再收紧（Review 第三轮）**：
    - **strict 真正按空结果计失败**：`strictOverall*` 现在对非 SUCCESS 样本一律按空预测计分；另增 `allOutputOverall*`（所有输出含失败残留）与 `successfulOnlyOverall*`，三套口径在报告中并列。
    - **Prompt 基准多窗口逐窗抽取**：`REAL_WINDOW_COMPOSITE` 不再“拼接后截断 3000 字”，改为每个 GoldWindow 独立调用模型后合并实体/关系/Claim/存疑/代码事实；单文本仍保留 3000 字上限。
    - **Build 合成快照 entryId 唯一**：改用 `parentId|windowId`，避免同父块多窗口产生重复 Entry ID（满足正式快照 entryId 唯一约束）。
    - **证据 offset 按窗口坐标系校验**：有 `windowId` 时在 `GoldWindow.text` 内校验 `startOffset`，否则回退源文件全文；新增 `windowOffsetValidityRate` / `sourceFileOffsetValidityRate` / `quoteSourceMatchRate`，`goldEvidenceOffsetValidityRate` 在 RULE 全量从 0.000 提升到 0.370。
    - **关系端点支持 Gold alias**：关系匹配复用实体匹配语义，模型输出别名也可命中 gold canonical/alias。
    - **实体类型指标**：新增 `typedEntityRate` / `entityTypedF1` / `entityTypeAccuracy`，与名称 F1 分开报告。
    - **存疑匹配收紧**：过滤空/过短预测，新增 `uncertaintyPrecision`（RULE 全量 0.171）。
    - **重试总耗时**：Prompt 基准的 `averageLatencyMs` 改为整次预测总耗时（含重试与退避），不再只记最后一次调用。
    - **evidenceId 按 case 作用域**：`computeEvidenceMetrics` 使用 `caseId|evidenceId` 复合 key，避免跨用例同 id 覆盖。
    - **sourceFile 按数据集根目录解析**：评测器支持 `setBaseDirectory`，相对路径不再依赖当前工作目录。

### Fixed

- **Prompt 基准模型解析与生产链路一致**：`PromptExtractionBenchmarkPredictor` 不再硬编码 `deepseek-v4-flash`，改为优先用 `requirement-graph.extraction-model`、回退 `rag.llm.developmentPlanModel`，避免评测全部以 `PREDICTION_EXCEPTION` 失败。
- **Prediction 构造器类型擦除冲突**：移除 11 参 `Set<String>` 实体构造器（与 `Set<PredictedEntity>` 规范构造器擦除后同签名），Prompt 基准改为输出未携带类型的 `PredictedEntity`；修复因增量编译残留导致运行时 `Unresolved compilation problem` 的问题。
- **Excel 证据真实回查**：`RequirementGraphGoldEvaluator.readSource` 对 `.xlsx/.xls` 使用 Apache POI 提取全部 sheet 文本，`goldEvidenceSourceMatchRate` 不再因二进制读取失败而低估（全量从 0.770 提升到 0.802）。
- **Build 快照仓库并发安全**：`ProductionBuildGraphPredictor.MapRequirementSnapshotRepository` 改用 `ConcurrentHashMap`，避免并行调用时快照读写竞争（IT 仍以串行方式运行 BuildService 链路）。
- **金标 IT 门禁支持切片运行**：Oracle/Empty 断言改为按数据集切片判定——只有切片包含代码事实/漂移/负例时才断言对应 Oracle 维度 = 1.0，避免 `-Dgold.limit` 只取到无样本切片时因 0/0=0 误报失败；完整数据集门禁仍由 `RequirementGraphGoldDatasetSelfCheckTest` 无条件覆盖。
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
