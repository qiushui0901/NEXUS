## 0.9.0 — 2026-08-17

### Added

- 新增 `/settings/gitlab` 可视化管理工作台：提供五步接入向导、连接/项目/collection
  提交前校验、项目列表与详情、revision drift 和旧索引可用性、持久化同步任务时间线、
  Webhook 最近状态及 Secret 一次性轮换；支持子路径直达和浏览器前进/后退，PAT 与
  Webhook Secret 不进入 URL 或浏览器持久化存储。
- 新增 `/knowledge` 知识管理工作台，提供知识库概览、文档列表、文档处理阶段轨道、
  分块检查抽屉、失败重试和复用正式链路的检索测试；页面使用内置 Vue WebJar、
  服务端分页和可见性自适应轮询，不引入外部前端构建链。
- 新增知识库、导入任务、文档、分块和阶段事件的分页查询 API，并提供项目级重建、
  文档/分块重试及复用正式混合检索与重排链路的检索测试 API；响应保留降级诊断，
  同时截断正文并移除向量、异常原文和服务器绝对路径。
- 知识导入 Bootstrap、逐文档清洗/分块/去重及 Qdrant 嵌入/索引/验证/发布阶段已接入持久化状态目录，可查询真实文件与分块进度；状态写入失败仍不影响原索引主流程。
- 新增 RAGFlow 风格知识管理状态基础设施及回归测试：SQLite 持久化知识库、导入任务、文档、分块与阶段事件，提供稳定 ID、分页契约、公开错误脱敏和应用重启中断恢复；状态目录作为旁路能力，不改变 Qdrant 正文与向量存储。
- 新增 GitLab 项目自动接入 MVP：SUPER_ADMIN 管理 API、AES-256-GCM 凭据加密、独立 SQLite 元数据与 Webhook 去重、受控 HTTPS clone/fetch/checkout、动态项目注册、项目级无丢失串行队列、首次全量与快进增量索引、失败目标原位重试、`lastIndexedSha/targetSha` 状态、原生 `X-Gitlab-Token` Push Hook；默认由 `GITLAB_INTEGRATION_ENABLED=false` 关闭，并提供简体中文接入指南。
- 新增真实 RAG 企业评测基线：版本化 JSONL v2 数据契约、24 条拾光冻结用例、稳定 evidence ID、人工审核与 Git commit provenance、nDCG@10/唯一用例降级率指标、版本化质量阈值和可执行发布门禁；提供 `tools/run-real-rag-evaluation.sh` 与简体中文执行指南，默认 CI 仅运行无外部依赖的契约和指标测试。
- 新增 `docs/gitlab-project-integration-implementation-plan.md`，定义 GitLab 项目发现、受控仓库同步、异步索引、Webhook 幂等、权限与迁移方案。
- 新增 NEXUS 企业化收口 Trellis 父子任务，拆分发布验证、真实 RAG 评测、GitLab 自动接入和多实例共享状态四条可独立验收的工作流。

### Changed

- 首页运行状态监视从已停用的 Ollama/BGE 本地依赖更新为 Qdrant、当前 OpenAI 兼容 API 配置模型和 GitLab 连通性；模型探针会核对所有已配置模型，外部探针并行且保持短超时。
- 代码语义标注模型的默认配置由残留的 `claude-opus-5` 对齐为已验证的
  `gpt-5.6-sol`；仍可通过 `ANNOTATION_MODEL` 环境变量覆盖。
- 五个核心页面统一为 NEXUS 知识与代码运营工作台外壳，新增共享设计 Token、完整桌面/移动导航、项目上下文、连接设置、通知和错误规范化基础设施；代码工作台改为统一浅色图谱工作区，版本比较从核心入口移除并兼容保留旧路由/API。
- 知识导入链路新增可观察的 Qdrant 批次阶段回调和运行级分块进度，保持“写入新点、验证成功、再清理旧点”的发布顺序不变。
- `ProjectRegistry` 支持线程安全动态注册与卸载，静态项目保持优先且继续作为默认项目，现有静态配置和旧 GitLab Webhook 行为不变。
- 重构 `tools/verify-report.sh`：结构化读取 Maven 版本，使用独立临时文件和 Surefire XML 汇总测试结果，并通过 `clean verify` 避免旧构建产物污染报告。
- 归档已经随 `0.8.6` 交付的历史 Trellis 任务，使任务状态与发布事实一致。
- 重新生成 `0.8.6` 机器验证报告：JDK 21、430 个测试、JaCoCo 门禁和可执行 jar 全部通过；`clean` 清除了旧 `target` 中 6 条失效测试报告，修正了历史统计虚高。
- 本地运行时 SQLite 数据库（`data/*.db`）纳入 `.gitignore`，避免本地状态库进入发布提交。

### Fixed

- 修复知识管理页在状态库为空但 Qdrant 已存在历史知识时显示“空知识库”的问题：列表与详情增加 Qdrant 兜底投影，已索引知识会以 READY 状态直接展示。
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
