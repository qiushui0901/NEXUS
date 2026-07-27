# Changelog

## 0.4.3-SNAPSHOT — 2026-07-27

### Fixed

- 修正需求版本链把增量文档误当完整清单的问题：新版本未重复出现的历史需求现在继续有效，不再被批量判定为删除。
- 需求快照会沿 `baseRequirementVersion` 递归合成完整需求视图；同一稳定条目再次出现时才产生修改。
- 只有结构化 `REMOVE` 操作会删除历史需求，正文中的“删除、取消、移除”等业务词不会触发需求删除。
- Qdrant 兼容回退不再根据条目缺席推断删除，避免在缺少完整增量链时产生错误结论。

### Changed

- 快照生成工具支持从专用操作或状态列识别精确删除状态，旧快照缺少操作字段时默认按 `UPSERT` 处理。
- 增加需求增量继承、显式删除、普通业务词、缺失基线、继承环和真实 `5.0 -> 5.1` 链路回归测试。
- NEXUS 平台版本提升到 `0.4.3-SNAPSHOT`。

## 0.4.2-SNAPSHOT — 2026-07-26

### Added

- 新增 `RequirementSnapshotRepository` 和非向量需求快照模型，按需求版本或业务版本 alias 读取可审阅需求事实。
- 新增 `VersionManifestResolver`，合并正式版本档案、Wiki 版本索引和需求快照，并根据相邻 commit 补齐业务基线版本。
- 新增 `tools/build-requirement-snapshots.py`，从现有历史需求表和本地可选产品文档包生成确定性的轻量 JSON 快照。
- 为有明确材料的 20 个需求基线生成受控快照，覆盖当前 `5.0.2 -> 5.1` 比较链路。

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
- 为 `immortal-game-service` 的 0.1 至 5.1 共 64 个历史版本重新生成实质性 Wiki 页面，保留已有人工页面并补充代码证据页。
- 版本中心改为直接读取 `/api/wiki/versions`，没有独立 VersionManifest 时也可以基于 Wiki 版本索引进行代码和 Wiki 差异比较。

### Changed

- `/api/versions/compare` 在缺少独立版本档案时降级到 Wiki 版本比较；需求和测试来源仍明确标记不可用。
- 版本中心时间线展示 Wiki 页面数量、基线 commit 和目标 commit，不再把“无版本档案”误报为“无版本”。

### Boundaries

- 自动生成内容只描述 Git 文件路径、结构和有限证据，不复制完整源码，也不生成未经需求原文核验的产品规则。
- 测试页面没有真实执行记录时统一显示“没有真实执行快照”，不会把建议测试点伪装成执行结果。
- 不提交向量、embedding、Qdrant storage、WAL、本地索引、IDE 数据或凭据。

本文件记录 **NEXUS 平台版本**。业务需求版本（例如封神 5.1）继续记录在 Wiki source、knowledge manifest 和构建请求中，两者不得混用。

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
