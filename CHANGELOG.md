## 0.8.0-SNAPSHOT — 2026-08-06

### Added

- 新增 Agentic 检索编排骨架：策略层（`RetrievalStrategy`/`StrategyResult`）、规则版证据反射器（命中阈值/唯一父块/双侧覆盖 + 稳定 reason code）与最多两跳的编排循环。
- 新增单线检索策略（`RequirementsOnlyStrategy`/`CodeOnlyStrategy`）与基于画像/查询意图的规则选择器；`nexus_search_requirements` 已接入编排器。
- 新增 LLM 代码语义标注（`CodeSemanticAnnotator`）：中英文业务描述、关键词、用户问题与同义词；分层标注（核心走 LLM、非核心走静态）、三连败熔断与哈希缓存。
- 代码索引升级为 `dense` + `desc_dense` 双向量（业务语义向量，检索经 schema 感知的三路 RRF 融合）。
- 新增确定性检索质量门禁 `RetrievalQualityGateTest`：冻结评测集黄金命中随 CI 默认回归，不依赖外部服务。
- 新增代码召回评测脚本 `tools/code-recall-eval.py`（57 查询，含耗时统计）。

### Changed

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
