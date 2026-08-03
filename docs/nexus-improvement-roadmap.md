# NEXUS 缺陷分析与版本改进路线图

> 版本：基于 `0.4.3-SNAPSHOT` 代码现状（main @ `906e9ff`）编写
> 最终目标：**公司内部开发人员日常可用的研发知识平台，并以 MCP Server 形态直接嵌入 IDE / Coding Agent，提升研发效率**
> 执行方式：交给 Codex 按版本分批实现，每个版本一个 Trellis 任务

---

## 0. 阅读方式

- 第 1 节是**现状盘点**，说明已经具备什么，避免重复造轮子。
- 第 2 节是**缺陷清单**，每条带证据（文件/行号）、影响面和严重级别，是后续所有工作的唯一依据。
- 第 3 节是**版本路线图**，`0.5 → 1.0`，每个版本给出目标、范围、实现要点、验收标准。
- 第 4 节是 **MCP 形态设计**，这是最终目的的核心，单独展开。
- 第 5 节是**最终效果与度量指标**。
- 第 6 节是 **Codex 执行手册**，包含任务拆分方式、prompt 模板、验收命令和红线。

严重级别定义：

| 级别 | 含义 |
|------|------|
| P0 | 阻断"公司内可用"这个目标，必须先解决 |
| P1 | 严重影响可用性、准确性或安全性 |
| P2 | 影响体验、维护成本或长期演进 |

---

## 1. 现状盘点

### 1.1 已具备的能力

| 领域 | 现状 |
|------|------|
| 需求 RAG | Tika 解析 → 降噪 → Parent/Child 分块 → SHA-256 去重 → Qdrant Dense+Sparse 混合检索 → BGE rerank → LLM rerank → 存疑生成 |
| 代码知识 | 全量/增量索引、后台索引任务与状态轮询、语义检索、符号定位、调用关系图、源码片段、GitLab Webhook |
| 统一检索 | `RetrievalPipeline` 承担项目路由、双源召回、去重、阶段诊断与降级 |
| 证据引用 | `EvidenceRegistry` 请求级白名单，`requirement:*` / `code:*` 稳定编号，非法引用过滤，覆盖率统计 |
| 版本知识 | 版本化 Wiki（schema 2）、版本档案、需求快照、四类差异比较、知识草稿构建 |
| 冲突检测 | 结构化声明比对，跨项目/跨版本污染阻断，Wiki 派生源不得覆盖原始证据 |
| 可观测性 | Actuator、Prometheus、OTLP、`/monitor` 工作台、RAG 阶段指标 |
| 权限 | `X-API-Key` + 角色 + 项目白名单 + `@RequiresPermission` |

### 1.2 规模

```
主代码   139 个 Java 文件，约 12,000 行
测试     51 个测试文件
前端     4 个静态 HTML（monitor.html 2427 行原生 JS）
CI       1 个 workflow，仅 ./mvnw -B verify
```

### 1.3 设计上做对的地方（改造时必须保留）

1. **证据优先**：所有结论必须挂可回查证据，模型不能引用白名单外内容。
2. **版本隔离**：`projectId + version + featureId` 三元主键，跨版本证据被显式阻断。
3. **安全降级**：单一来源不可用时标记 `NOT_AVAILABLE` + warning，绝不伪装成"没有变化"。
4. **草稿不自动发布**：自动构建只产出 `DRAFT / PENDING_REVIEW`。
5. **数据边界**：向量、Qdrant storage、WAL、凭据一律不入库不外泄。

> 这五条是这个项目相对普通 RAG 玩具的核心价值，后续任何改造都不得削弱。

---

## 2. 缺陷清单

### A. 交付形态缺陷（阻断"公司内可用"）

#### A1 · P0 · 完全没有 MCP Server

全仓检索不到任何 `modelcontextprotocol` / MCP 相关依赖或代码。目前所有能力只能通过 `curl` 调 REST 或打开网页使用。

**影响**：开发在 IDE 里写代码时无法调用 NEXUS，必须切窗口、复制粘贴，效率收益接近于零。这是与最终目标之间最大的差距。

#### A2 · P0 · 没有可分发的运行形态

- 无 `Dockerfile`，`compose.yml` 只启动 Qdrant / Prometheus / Grafana，NEXUS 本体靠 `scripts/nexus.sh` 在本机 Maven 构建后运行。
- 服务默认只监听 `127.0.0.1`。
- 依赖本机 Ollama（`bge-m3`）和本机 BGE reranker 服务。

**影响**：每个同事都要自己装 JDK 21 + Ollama + BGE + Qdrant，落地成本极高，实际不可能推广。

#### A3 · P0 · 代码索引要求仓库在同一台机器上

`app.rag.code.repository-path` / `PROJECT_N_REPO_PATH` 是本地文件系统路径，`JavaCodeScanner.scan()` 直接遍历本地目录，`GitDiffService` 走本地 Git。

**影响**：无法作为共享服务索引公司多个仓库；每个人只能索引自己 clone 的那份，索引结果无法复用。

#### A4 · P1 · 单机文件存储，无并发保护，无法水平扩展

`data/wiki`、`data/wiki-sources`、`data/wiki-drafts`、`data/version-manifests`、`data/requirement-snapshots` 全是 JSON 文件；`KnowledgeDraftLifecycleService`、`VersionManifestService`、`WikiGenerationService` 用临时目录 + 原子替换写入。

**影响**：单进程内没有跨请求的写锁语义，多人同时审核/发布同一版本会互相覆盖；服务无法多副本部署。0.5 明确不引入数据库是合理的阶段决策，但企业化阶段必须解决。

#### A5 · P2 · 前端是三个手写静态页

`monitor.html` 2427 行原生 JS，`pom.xml` 里定义了 `vue.version=3.5.13` 却没有任何前端构建产物。

**影响**：交互能力受限，改一次页面成本高，无法支撑审核工作流、评论、看板这类需要状态管理的界面。

### B. 能力与精度缺陷

#### B1 · P0 · 代码索引只支持 Java

```
JavaCodeScanner.java:47          .filter(path -> path.toString().endsWith(".java"))
JavaCodeScanner.java:72          if (!normalized.endsWith(".java")) { ... }
IncrementalCodeIndexService:65   .filter(path -> path.endsWith(".java"))
```

**影响**：公司里的 Go / Python / TypeScript / Kotlin 仓库完全用不了，直接把大部分潜在用户挡在门外。

#### B2 · P1 · 只有文件级差异，没有符号级影响分析

README 自述为 known limitation：代码差异是文件级比较，不提供 AST 或符号级调用影响分析，也没有回归范围推荐。

**影响**："改这个需求会影响哪些接口、需要回归哪些用例"这个最高频、最值钱的问题答不了。

#### B3 · P1 · 重排没有进入统一检索管线

`RetrievalPipeline.execute()` 只做召回 + 去重 + `limit` 截断，没有任何 rerank 阶段。BGE 和 LLM 重排目前只存在于 `DoubtReviewService`（`:192`、`:194`）。

**影响**：走 `DEVELOPMENT_PLAN` / `WIKI_BUILD` profile 的开发方案和知识草稿用的是**未重排的原始召回**，证据质量明显低于需求存疑路径，且两条路径质量无法统一评估。

#### B4 · P1 · 检索串行且无缓存

`RetrievalPipeline.execute()` 里需求检索、语料 scroll、代码检索三次调用顺序执行；全仓只有 `CrossProjectSearchService` 用了 `ExecutorService`，没有任何 `@Cacheable` / CacheManager。

**影响**：单次开发方案请求的延迟是三段耗时叠加；重复查询（同一需求被多人问）每次都重新算 embedding 和检索。

#### B5 · P1 · 评测集太小，质量无门禁

`RetrievalEvaluationDataset` 176 行，量级约 10 条；README 自述目标是扩到 50 条。CI 只跑 `./mvnw -B verify`，没有检索质量回归门禁。

**影响**：改 prompt、改 chunk 策略、改 top-k 都没有客观判据，容易改坏了没人发现。

#### B6 · P1 · 没有真实测试执行结果接入

测试知识全靠"建议测试点"，没有 CI 测试报告导入，页面只能显示"没有真实执行快照"。

**影响**：测试同学拿不到增量价值，三方（产品/开发/测试）里少了一方。

#### B7 · P2 · 外部依赖超时配置不完整

`AiConfiguration` 只给 Qdrant RestClient 设了 2s/5s 超时；`bgeReranker` 用的 `RestClient.Builder` 没有设置 `requestFactory`，即没有连接/读取超时；LLM 走 Spring AI 默认。

**影响**：BGE 服务假死时请求会长时间挂住，`ResilientBgeReranker` 的降级逻辑要等到底层超时才生效。

### C. 工程质量缺陷

#### C1 · P1 · 多处吞异常，违反团队最高原则

```
CodeKnowledgeService.java:85-87   catch (IllegalArgumentException ignored) { }
CodeKnowledgeService.safeSearch   catch (RuntimeException ignored) → return Stream.empty()
ProjectIdResolver.java:53         catch (RuntimeException ignored) { }
```

`ignored` 关键字在主代码中出现于 10 个文件、共 20+ 处（`KnowledgeConflictService` 6 处、`CodeKnowledgeService` 5 处、`VersionKnowledgeBuildPipeline` 4 处）。

**影响**：违反 `AGENTS.md` 里"严禁吞异常"的最高原则；跨项目检索静默返回空结果，用户看到的是"没搜到"，而真实原因是 Qdrant collection 不存在或网络失败——这与项目自己的"安全降级"原则也矛盾（降级必须可见）。

#### C2 · P2 · 兼容构造函数堆积

`DevelopmentPlanService` 有 4 个重载构造函数，其中 3 个注释为 "Backward-compatible constructor kept for focused unit tests"；`KnowledgeBuildController` 也有一个 `this(pipeline, guard, null, null)` 的测试兼容构造，并在运行时用 `requireDraftService()` 抛 `IllegalStateException` 兜底。

**影响**：为测试便利牺牲了生产代码的类型安全，`null` 依赖会把配置错误推迟到运行时才暴露。

#### C3 · P2 · 大类偏多

`DevelopmentPlanService` 591 行、`CodeKnowledgeService` 530 行、`VersionKnowledgeBuildPipeline` 440 行、`DevelopmentPlanStreamService` 417 行。

#### C4 · P2 · 规范文档大面积未填写

`.trellis/spec/backend/index.md` 中 5 份指南状态仍是 "To fill"，只有 `retrieval-and-version-knowledge.md` 是 Active。

**影响**：Codex / 新同事缺少可执行的编码契约，产出一致性靠人盯。

#### C5 · P1 · CI 能力单薄

只有 JDK 21 + `./mvnw -B verify`。没有覆盖率门禁（无 JaCoCo）、无静态检查、无依赖漏洞扫描、无镜像构建与发布、无集成测试环境（Qdrant service container）。

### D. 安全与运维缺陷

#### D1 · P0 · API Key 明文配置，无轮转无审计

`application.yml` 里用户和 key 从环境变量注入，`ProjectAuthInterceptor.resolveUser()` 遍历明文列表做常量时间比较。没有哈希存储、没有有效期、没有吊销、没有访问审计日志。

**影响**：公司内多人使用时，key 泄漏无法定位、无法回收；"谁在什么时候查了哪个项目的需求"无法追溯，这在有敏感需求文档的场景里是硬伤。

#### D2 · P0 · 没有限流和配额

任何持有 key 的用户都能无限次触发 LLM 生成和全量代码索引。

**影响**：单个循环脚本就能打爆 AI 网关成本或把 Ollama/Qdrant 压垮。

#### D3 · P1 · 认证可被整体关闭

`AUTH_ENABLED=false` 时 `ProjectAuthInterceptor` 直接注入 `UserContext.defaultAdmin()` 放行。0.5 的 PRD 已经计划做 fail-safe，需要落实。

#### D4 · P2 · 仓库卫生

仓库根目录存在 `5.1存疑.xlsx`、`封神5.1存疑.xlsx`、`封神版本问题整理.xlsx`；`data/产品文档.zip` 2.6GB；`tools/` 下有 `app.log`、`app.pid`、`qdrant.tar.gz`、`qdrant/` 等运行产物。虽然 `.gitignore` 覆盖了 `*.zip` 和部分产物，但 xlsx 未被忽略。

**影响**：真实业务文件混在代码仓库里，团队协作和对外分享都有泄漏风险；工作副本体积巨大。

#### D5 · P2 · 无部署与运维文档

没有生产部署指引、备份恢复方案、容量规划、故障处置手册。

### E. 面向"公司内使用"的整体缺失

| 缺失项 | 说明 |
|--------|------|
| 用户体系 | 无 SSO / LDAP 对接，无个人工作区概念 |
| 使用统计 | 不知道谁在用、用了什么、哪些回答有用 |
| 反馈闭环 | 用户无法对回答打分或纠错，知识不会因使用而变好 |
| 冷启动 | 新项目接入需要手工配环境变量 `PROJECT_N_*`，没有自助接入流程 |
| 多仓管理 | 仓库注册、凭据管理、定时同步全部缺失 |

---

## 3. 版本路线图

总体节奏：**先收敛现有（0.5）→ 打通入口（0.6 MCP）→ 补齐能力（0.7/0.8）→ 企业化（0.9）→ GA（1.0）**。

```
0.5  草稿审核发布闭环 + 统一检索 + 认证 fail-safe        [收敛]
0.6  MCP Server MVP，IDE / Codex 内可直接调用           [入口]  ← 最终目的的第一个里程碑
0.7  多语言代码索引 + 符号级影响分析                     [能力]
0.8  检索质量与性能：重排入管线、并行、缓存、评测门禁     [质量]
0.9  企业化：镜像化部署、元数据存储、SSO/审计/限流、多仓  [规模]
1.0  GA：契约冻结、SLO、文档、内部推广                   [交付]
```

---

### 0.5 —— 收敛现有能力

> 已有 PRD：`.trellis/tasks/07-28-nexus-0-5/prd.md`，本节只做补充和排序。

**目标**：把已经写到一半的能力收口，不引入新架构。

**范围**

1. 需求存疑评审改走 `RetrievalPipeline`（`REQUIREMENT_REVIEW` profile），保留 BGE/LLM 重排行为与响应结构。
2. 知识草稿生命周期：`DRAFT / IN_REVIEW / APPROVED / REJECTED / PUBLISHED / SPLIT / MERGED`，含审核人、时间、备注、发布与回滚。
3. 只有 `APPROVED` 草稿可发布；发布走原子替换，保留回滚历史。
4. 认证 fail-safe：非本地环境启用认证；启用但 key 为空时**启动失败**；关闭认证时打印显著警告。
5. 检索评测补充项目泄漏、版本泄漏、空结果、依赖降级四类用例。
6. 移除生产 prompt 与默认值中的具体产品名和版本假设。

**补充建议（本文档新增，建议一并做）**

7. **清理吞异常**（缺陷 C1）：把 `ignored` 全部改为"记录 WARN 日志 + 转成显式 `RagWarning` 降级"或"向上抛出"，不允许静默返回空集合。这是团队最高原则，越早清越便宜。
8. **补全 BGE reranker 超时**（缺陷 B7）：给 `bgeReranker` 的 `RestClient` 设置 `connectTimeout=2s`、`readTimeout=5s`。
9. **仓库卫生**（缺陷 D4）：把根目录 xlsx 移入 `data/` 并加入 `.gitignore`；清理 `tools/` 运行产物。

**验收**

- [x] 构建草稿 → 提审 → 批准 → 发布 → 读到 Wiki 全链路通过
- [x] 未批准草稿无法发布，无法覆盖正式 Wiki
- [x] 每次状态流转记录 actor / timestamp / status / comment
- [x] 回滚到上一个已发布快照，过程中不出现半成品 Wiki
- [x] 存疑评审在请求的 project / document / version 边界内
- [x] 主代码中不再存在 `catch (...) { }` 空块或无日志的 `ignored`
- [x] `./mvnw -B verify` 通过，`git diff --check` 通过

复测记录（2026-07-28）：草稿生命周期、发布失败恢复、版本隔离、认证 fail-safe 和检索降级相关测试通过；JDK 21 下完整 `verify` 共 174 项测试通过。

---

### 0.6 —— MCP Server MVP（最关键的一步）

**目标**：开发在 Cursor / Codex / Claude Code 里，不离开编辑器就能查需求、查代码、拿到带引用的开发方案。

**范围**

1. 新增 Maven 模块或包 `com.example.requirementrag.mcp`，基于 Spring AI 2.0 的 MCP Server 支持（`spring-ai-starter-mcp-server-webmvc`），暴露 Streamable HTTP / SSE 端点。
2. 提供 stdio 适配（一个轻量 bridge，把 stdio 转发到 HTTP），覆盖不允许直连服务的场景。
3. 第一批工具（详见第 4 节）：
   - `nexus_search_requirements`
   - `nexus_search_code`
   - `nexus_get_source`
   - `nexus_development_plan`
   - `nexus_wiki_page`
   - `nexus_version_diff`
4. 鉴权：MCP 请求头透传 `X-API-Key`，复用 `ProjectAuthInterceptor` 的用户解析、权限与项目白名单，不新开一套权限模型。
5. 所有工具返回**结构化 JSON + 证据引用**，与 REST 的证据编号语义完全一致（`requirement:*` / `code:*`）。
6. 错误语义：依赖不可用返回带 `warnings` 的降级结果而不是抛栈；越权返回明确的权限错误文案，不泄漏项目列表。
7. 提供 `docs/mcp-quickstart.md`：Cursor / Codex 的配置片段、如何申请 key、常见问题。

**关键实现点**

- MCP 工具层必须是**薄适配层**，直接复用 `RetrievalPipeline`、`DevelopmentPlanService`、`WikiRepository`、`CodeKnowledgeService`，不得复制业务逻辑。
- 工具入参必须显式包含 `projectId` / `version`，缺省时从服务端配置解析并在响应里回显解析结果，避免 Agent 误跨版本。
- 单次工具响应设置字符上限（建议 requirement 摘录 ≤ 1200 字/条，code 片段 ≤ 200 行），超出截断并标注，防止把 Agent 上下文打爆。
- 绝对路径脱敏：源码路径统一输出仓库相对路径。

**验收**

- [x] 在 Cursor 中配置 NEXUS MCP 后，工具列表可见且可调用
- [x] 用一个真实需求提问，返回结果包含可回查的 `requirement:*` / `code:*` 编号
- [x] 无 key / 错 key / 越权项目分别返回 401 / 401 / 403 语义
- [x] BGE 或 Qdrant 停掉时，工具返回降级结果而不是 500
- [x] 有针对每个工具的契约测试（入参校验、权限、降级、截断）

复测记录（2026-07-29）：保留原有 MCP HTTP 初始化、工具发现、真实脱敏语料证据链验证，并补齐 6 个 0.6 工具的 6 × 4 入参、权限、降级、截断契约矩阵。`NexusMcpV06ContractTest` 52 项通过，含逐工具单字段静默截断回归；相关 MCP 定向回归通过；JDK 21 `clean verify` 共 258 项测试通过，`git diff --check` 通过。

---

### 0.7 —— 多语言代码索引与符号级影响分析

**目标**：让非 Java 团队也能用；把"改这个会影响什么"变成可回答的问题。

**范围**

1. 抽象 `CodeScanner` 接口，`JavaCodeScanner` 成为其中一个实现；新增 Go / Python / TypeScript / Kotlin 实现。
   - 建议统一走 **Tree-sitter** 做语法解析，避免为每种语言维护一套手写解析。
   - 保留现有 chunk 契约（`CodeChunk`），新增 `language` 字段。
2. 索引配置从"路径过滤 `.java`"改为"语言注册表 + 扩展名映射"，`IncrementalCodeIndexService` 和 `GitDiffService` 同步改造。
3. 构建**符号级调用图**并持久化，支持：
   - 给定符号 → 上游调用者 / 下游被调用者（可指定深度）
   - 给定 commit 差异 → 受影响符号集合 → 受影响接口/入口
   - 受影响范围 → 关联的需求功能页 → 建议回归范围
4. 新增 MCP 工具 `nexus_impact_analysis`。
5. 版本差异从文件级升级为符号级（保留文件级作为降级）。

**关键实现点**

- 影响分析结果必须标注**置信度和依据**（静态调用边 / 同文件 / 同模块），不得把推测输出成事实——延续项目既有原则。
- 动态调用（反射、Spring 注入、消息队列）无法静态解析时要显式标记 `UNRESOLVED`，不能假装完整。
- 调用图规模会很大，需要考虑存储方案（建议独立 SQLite 或 Qdrant payload 之外的图存储），并支持增量更新。

**验收**

- [x] 至少 Java + Go + Python 三种语言可索引可检索（同时提供 TypeScript，Kotlin 走能力探测）
- [x] 给定一个方法，能返回正确的调用者列表（项目/commit 隔离，支持入站/出站和深度限制）
- [x] 给定两个 commit，能返回受影响符号和建议回归范围（目标快照缺失时明确降级到文件级）
- [x] 无法静态解析的调用被显式标记，不计入"确定影响"

复测记录（2026-07-28）：多语言 fixture、SQLite 项目/commit 隔离、事务回滚、删除/重命名、影响遍历、REST/MCP 边界及降级测试通过；JDK 21 下完整 `verify` 共 181 项测试通过。

---

### 0.8 —— 检索质量与性能

**目标**：让答案更准、更快，并且质量变化可度量。

**范围**

1. **重排进入统一管线**：在 `RetrievalPipeline` 内加入可配置的 rerank 阶段（BGE → LLM），三个 profile 共用，`DoubtReviewService` 改为消费管线结果。
2. **并行召回**：需求检索、语料 scroll、代码检索改为并发执行，配置独立超时和熔断。
3. **缓存**：
   - Embedding 缓存（查询文本 → 向量，带 TTL）
   - 检索结果缓存（query + project + version + profile 指纹）
   - Wiki 页面缓存（带版本索引失效）
4. **评测集扩到 50+ 条**，覆盖：正常召回、版本串线、相似功能误召回、空结果、依赖降级、跨项目污染。
5. **CI 质量门禁**：加入 JaCoCo 覆盖率下限、检索评测回归（Recall@K / MRR 不得低于基线）、依赖漏洞扫描。
6. 引入**查询改写 / 多路召回**（可选）：把口语化提问改写成检索友好的多个子查询。
7. **0.8.2 可信文档评测**：修正 v1 单文件文件级 Recall 的宽松口径，新增多文档、多章节、hard-negative 固定语料，分别报告 File / Section / Child Recall@10，并按唯一 case 统计质量。

**验收**

- [x] 三个 profile 的证据都经过统一重排；固定同条件评测中 Document/Code Recall@10 与 MRR@10 均不低于 0.7 基线
- [x] 0.8.2 多文档固定集达到 18 个文件、至少 36 个 parent、24 个 HIT case，并包含
  12 个显式映射的独立 hard negative；File/Section/Child Recall 分层且无 `parentText` 泄漏
- [x] 开发方案受控并行召回 P95 相对顺序基线下降 ≥ 30%（315 ms → 112 ms，下降 64.44%）
- [x] 固定评测集 ≥ 50 条并覆盖六类场景；确定性离线回归作为 CI 门禁，真实依赖评测保留显式开关
- [x] 覆盖率门禁生效，低于阈值构建失败

0.8 开发记录（2026-07-28）：统一 BGE→可选 LLM 重排已覆盖三个 profile；需求、版本语料和代码召回改为独立超时、分阶段熔断的并行执行；加入检索结果、Embedding、Wiki TTL 缓存及发布失效；MCP 扩展为 10 个工具和 3 个 Prompt。通用评测集已扩展为 50 条并覆盖六类场景，CI 已具备确定性离线回归门禁和可选真实依赖评测开关。最终 JDK 21 完整 `verify` 共 201 项测试通过，JaCoCo 行覆盖率 65.25%（4,275 / 6,552，门槛 35%）。

截至 2026-07-28，0.8 仅在授权的拾光仓库上完成一次真实校准，当时还不是完整的 0.7→0.8 同条件门禁：校准时 Ollama Embedding 正常，但独立 BGE `/rerank` endpoint 尚未提供，历史 0.7 提交也不包含拾光 profile、脱敏语料和同一黄金数据集。仓库中的 `retrieval-baseline-v0.7.json` 是通用门禁阈值而非拾光实测基线。该阻塞已由下述 2026-07-29 正式同条件评测闭环解除；真实依赖评测仍保留显式开关。OWASP CVSS 7 门禁已加入 CI，并通过 `NVD_API_KEY` Secret 读取密钥。

真实评测记录（2026-07-28）：使用用户授权的 `qiushui-shiguang` 只读仓库、独立 collection、
12 条稳定黄金标签和脱敏需求完成 0.8 校准。修复前→修复后：Document Recall@10
`0.900→1.000`、Code Recall@10 `0.500→1.000`、MRR@10 `0.516→0.863`、Mixed both-hit
`0.500→1.000`、P50 `3777→2933 ms`、P95 `8617→5888 ms`。真实 MCP 需求、代码和源码
证据链通过。独立 BGE `/rerank` endpoint 当时尚未提供；Ollama `/api/embed` 返回向量，不能满足
NEXUS 的 `index`/`score` 重排契约。10 条需求相关用例仍如实报告 `BGE_RERANK_UNAVAILABLE`，
因此报告为 10/12 失败且全部属于基础设施失败，不能把满召回解释为依赖完整验收。

历史 0.7 提交没有拾光 profile、脱敏语料或同一黄金数据集；已提交的 0.7 JSON 是通用门禁阈值，
不是拾光实测结果。因此在 2026-07-28 的校准记录中无法复现同条件 0.7→0.8 对照，当时第一项未勾选。
2026-07-29 已改用同一固定语料、黄金集和运行环境分别测量两个 variant，完成正式闭环；未将通用阈值伪装为实测 baseline。

2026-07-29 修复记录：新增独立 Python/Transformers reranker 服务，默认加载
`BAAI/bge-reranker-v2-m3`，监听 `127.0.0.1:8081/rerank` 并兼容现有 Java 客户端契约。
Ollama 中的 reranker 模型不直接复用，因为当前 Ollama 没有 `/api/rerank`，而 `/api/embed`
只返回向量。历史评测状态不因新增启动脚本而自动改为成功；必须完成真实模型启动和同条件重测。
同日真实模型已在 CPU 上加载并通过 `/health` 与 NEXUS 响应契约检查；这解除了 endpoint
可用性阻塞，但不会改写 2026-07-28 的校准记录。随后已完成下述同条件正式重跑。

正式 0.7→0.8 对照闭环（2026-07-29）：在同一拾光提交
`d29f32589c5bd7c190a23eb3a84f27f0069f312f`、同一脱敏语料、同一 Qdrant collection、
同一 54 条黄金集（SHA-256 `1ff996579588bfc5b859b5a483427c255325265b211e452af5eaff6471a61b18`）、
相同 profile/top-k/超时与关闭缓存条件下，分别用独立 JVM 测量 `0.7-baseline` 和
`0.8-rerank`，每个 case 预热 1 次并重复 3 次。Python/Transformers
`BAAI/bge-reranker-v2-m3` 在 CPU 上完成 144/144 次成功调用，降级 0 次；18 次无候选跳过
单独计数，两个 variant 的基础设施失败均为 0。Document Recall@10
`0.354167→0.354167`、Code Recall@10 `0.738095→0.738095`、MRR@10
`0.425617→0.425617`，质量持平且不回退；受控三分支并行 P95 `315→112 ms`，下降
64.44%。`target/retrieval-evaluation/comparison.json` 的全部 acceptance checks 为 PASS，
`manifest.json` 记录固定数据、语料、运行时、配置指纹且 `secretsRecorded=false`。真实模型端到端
延迟因 CPU BGE 增加，不用它替代受控并行召回性能指标；真实依赖评测仍通过显式开关运行，避免
默认 CI 依赖本地 Qdrant、Embedding 与 BGE 服务。

0.8.1 正式质量收口（2026-07-31）：在固定 54 条黄金集、拾光 commit、三个 profile、Top-K、Python/Transformers BGE 与运行环境下完成 `0.8-rerank` → `0.8.1-quality` 对照。0.8.1 达到 Document Recall@10 `1.000000`、Code Recall@10 `0.809524`、MRR@10 `0.823951`、no-result accuracy `1.000000`、污染率 `0`、P95 `34 ms`，相对历史 0.8 的三项质量门槛全部通过。同工作树控制组为 `1.000000 / 0.809524 / 0.806636`，因此只声明 MRR 增长 `0.017315`，不把持平的 Recall 伪装成 reranker 增益。144 次文档决策在最终仅有单候选时走结构化 singleton skip；正式 runner 已独立验证 Python 健康和 Java→BGE 实时契约，多候选仍使用真实 BGE，意外 degradation 为 0。24 次重复失败对应 8 个唯一代码 case，归因为 `CODE_CANDIDATE_RECALL_MISS=7`、`CODE_RERANK_LOSS=1`。正式 comparison 为 PASS，详见 `docs/retrieval-evaluation-history.md`。

---

### 0.9 —— 企业化

**目标**：从"一个人的本机工具"变成"一个部门的共享服务"。

**范围**

1. **容器化**：多阶段 `Dockerfile`（JDK 21 构建 + JRE 运行），`compose.yml` 增加 NEXUS 服务本体，提供一键起全栈；CI 构建并推送镜像。
2. **元数据存储迁移**：草稿生命周期、版本档案、审计日志、用户与 key 迁移到 PostgreSQL；Wiki 产物可继续留文件（便于 Git 评审），但索引和状态入库。需要提供从 `data/*.json` 的一次性迁移脚本。
3. **身份与安全**：
   - 对接公司 SSO / LDAP，key 改为服务端生成、哈希存储、可轮转可吊销、带有效期
   - 全量访问审计：谁 / 何时 / 哪个项目 / 哪个版本 / 什么操作 / 命中哪些证据
   - 按用户和按项目的**限流与配额**（LLM 调用次数、索引任务并发）
4. **多仓库管理**：仓库注册表（Git URL + 凭据 + 分支 + 语言），服务端自动 clone / fetch，定时增量索引，Webhook 触发；不再要求仓库在同机。
5. **前端重构**：用 Vue 3 重写工作台（`pom.xml` 里已经预留了 `vue.version`），支撑审核工作流、评论、看板。
6. **反馈闭环**：回答可点赞/纠错，纠错进入待审核队列；使用统计看板。

**验收**

- [ ] `docker compose up` 可在一台干净机器上拉起完整环境
- [ ] 新同事凭 SSO 登录即可使用，管理员可吊销其权限
- [ ] 所有敏感操作有审计记录，可按用户/项目/时间检索
- [ ] 超过配额的请求被限流并返回明确提示
- [ ] 新仓库通过界面注册即可自动索引，无需改环境变量

---

### 1.0 —— GA

**目标**：可以在公司内正式推广。

**范围**

1. **MCP 工具契约冻结**并版本化（`nexus.v1.*`），破坏性变更需走弃用周期。
2. **SLO 定义与保障**：可用性、P95 延迟、检索准确率基线，配 Grafana 看板与告警。
3. **完整文档**：用户指南、接入指南、运维手册、故障处置、FAQ。
4. **灰度推广**：先 1-2 个团队试点，收集反馈迭代，再全面开放。
5. **数据治理**：需求文档密级分级、按密级控制检索范围、留存与删除策略。

**验收**

- [ ] 连续 30 天可用性 ≥ 99%
- [ ] 试点团队 NPS 为正，且有可量化的效率数据
- [ ] 运维手册可让非开发者完成日常巡检与恢复

---

## 4. MCP 形态设计（0.6 起，1.0 冻结）

### 4.1 定位

NEXUS MCP Server 是**证据服务**，不是又一个聊天机器人。它给 Coding Agent 提供的是：**这个需求在哪、这段代码在哪、它们怎么对应、改了会影响谁**，每条都带可回查编号。Agent 拿到证据后自己写代码。

### 4.2 传输与部署

| 模式 | 场景 | 说明 |
|------|------|------|
| Streamable HTTP / SSE | 主推 | 部门共享一个 NEXUS 服务，各人用自己的 key 连接 |
| stdio bridge | 备选 | 网络策略不允许直连时，本地进程转发到 HTTP |

### 4.3 工具清单

| 工具 | 入参 | 返回 | 上线版本 |
|------|------|------|----------|
| `nexus_search_requirements` | query, projectId, version, limit | 需求证据列表 + `requirement:*` 编号 + 受控摘录 | 0.6 |
| `nexus_search_code` | query, projectId, limit, language? | 代码证据列表 + `code:*` 编号 + 相对路径 + 行号 | 0.6 |
| `nexus_get_source` | projectId, filePath, startLine, endLine | 源码片段（脱敏路径，行数上限） | 0.6 |
| `nexus_development_plan` | query, projectId, version, documentId? | 分段开发方案 + 逐条引用 + 支持状态 + 覆盖率 | 0.6 |
| `nexus_wiki_page` | projectId, version, featureId \| keyword | 功能页结构化内容（概览/需求/开发/测试/证据） | 0.6 |
| `nexus_version_diff` | projectId, fromVersion, toVersion, aspect | 需求/代码/测试/Wiki 差异，含来源可用性标记 | 0.6 |
| `nexus_review_doubts` | documentId, version, module | 需求存疑清单 + 引用 | 0.7 |
| `nexus_impact_analysis` | projectId, symbol \| commitRange | 受影响符号、入口、建议回归范围、置信度 | 0.7 |
| `nexus_code_graph` | projectId, symbol, direction, depth | 调用关系子图 | 0.7 |
| `nexus_conflict_check` | projectId, version, claims | 冲突报告 | 0.8 |

**MCP Resources**（0.7 起）：把 Wiki 功能页以 `nexus://wiki/{projectId}/{version}/{featureId}` 形式暴露，Agent 可直接引用。

**MCP Prompts**（0.8 起）：预置 `实现某需求`、`评审某需求`、`评估改动影响` 三个模板。

### 4.4 统一返回契约

每个工具的返回都遵循同一外层结构：

```json
{
  "resolved": { "projectId": "...", "version": "...", "documentId": "..." },
  "data": { },
  "evidence": [
    { "id": "requirement:3", "source": "需求文档 A / 3.2 节", "excerpt": "...", "contentHash": "..." },
    { "id": "code:1", "source": "src/main/java/.../Foo.java:120-168", "symbol": "Foo#bar" }
  ],
  "quality": { "totalClaims": 8, "supported": 6, "partial": 1, "unsupported": 1, "coverage": 0.75 },
  "warnings": [ { "code": "CODE_RETRIEVAL_UNAVAILABLE", "message": "代码检索暂时不可用" } ]
}
```

`resolved` 是防误用的关键：Agent 必须能看到服务端最终用了哪个项目和版本。

### 4.5 安全约束（必须实现）

1. 复用 `X-API-Key` 与项目白名单，MCP 不新建权限模型。
2. 越权只返回"无权限"，**不泄漏存在哪些项目**。
3. 不返回绝对路径、凭据、Qdrant 内部标识、向量数据。
4. 单工具单次响应有字符上限，超出截断并标注。
5. 每次工具调用写审计日志（0.9 起入库）。
6. 按 key 限流（0.9 起）。

### 4.6 客户端配置示例（写进 `docs/mcp-quickstart.md`）

```json
{
  "mcpServers": {
    "nexus": {
      "url": "http://nexus.internal:8080/mcp",
      "headers": { "X-API-Key": "${NEXUS_API_KEY}" }
    }
  }
}
```

---

## 5. 最终形态与效果指标

### 5.1 最终形态

```
公司 Git 仓库 ──┐
需求文档系统 ──┼──→ NEXUS 服务（容器化，部门共享）
CI 测试报告 ──┘         │
                        ├─→ MCP Server ──→ Cursor / Codex / Claude Code（开发主入口）
                        ├─→ Web 工作台  ──→ 产品评审、测试设计、知识审核
                        └─→ REST API    ──→ 其他内部系统集成
```

### 5.2 三类用户的目标体验

**开发**：在 IDE 里问"XX 需求怎么实现"，直接拿到需求原文引用、相关代码位置、改动影响面和回归范围，不用翻文档、不用问产品。

**产品**：打开版本 Wiki 就能看到某功能在各版本的完整演进、当前实现状态和待确认事项。

**测试**：从需求变更直接得到受影响代码范围和建议回归用例，测试设计有据可依。

### 5.3 度量指标

| 指标 | 基线 | 1.0 目标 |
|------|------|----------|
| 支持的代码语言 | 1（Java） | ≥ 4 |
| MCP 工具数 | 0 | ≥ 10 |
| 接入仓库数 | 手工配置 | ≥ 20，自助接入 |
| 检索 Recall@10 | 未系统度量 | ≥ 0.85（50 条评测集） |
| 开发方案 P95 延迟 | 未度量 | ≤ 8s |
| 证据覆盖率（结论有引用比例） | 已有统计 | ≥ 90% |
| 周活跃开发人数 | 0 | ≥ 30 |
| 服务可用性 | 本机 | ≥ 99% |

### 5.4 效率收益（需在试点中实测）

- 新人理解一个历史需求：从"翻文档 + 问人半天"到"IDE 里几分钟拿到带引用的答案"
- 需求评审前的存疑整理：从人工通读到自动生成初稿 + 人工确认
- 改动影响评估：从凭经验到有静态依据的受影响清单

---

## 6. Codex 执行手册

### 6.1 总原则

1. **一个版本一个 Trellis 任务**，不要把 0.6 和 0.7 混在一个分支里做。
2. **先读 spec 再写码**：`.trellis/spec/backend/retrieval-and-version-knowledge.md` 是当前唯一 Active 的契约文档，涉及检索/版本知识的改动必须先读。
3. **不得削弱五条核心原则**（见 1.3）：证据优先、版本隔离、安全降级、草稿不自动发布、数据边界。
4. **不得吞异常**：任何 `catch` 必须记日志或向上抛，静默返回空集合按缺陷处理。
5. **不得为测试便利在生产代码里加 `null` 依赖构造函数**，用测试替身或 Spring 测试切片。
6. **每个 PR 保持小而聚焦**，使用 conventional commits。

### 6.2 每个版本的任务拆分方式

以 0.6 为例，建议拆成 5 个可独立验证的子任务：

```
0.6.1  引入 MCP 依赖与最小 Server 骨架，暴露 1 个 ping 工具，打通 Cursor 连接
0.6.2  鉴权透传：复用 ProjectAuthInterceptor 的用户解析与项目白名单
0.6.3  只读检索类工具：search_requirements / search_code / get_source
0.6.4  组合类工具：development_plan / wiki_page / version_diff
0.6.5  降级、截断、脱敏、审计埋点 + 契约测试 + quickstart 文档
```

每个子任务都要能单独跑 `./mvnw -B verify` 通过。

### 6.3 给 Codex 的任务 prompt 模板

```
背景：NEXUS 是版本化需求·代码·测试知识平台（Java 21 / Spring Boot 4.1 / Spring AI 2.0）。
本次任务属于 <版本号> 里程碑，对应改进文档 docs/nexus-improvement-roadmap.md 的 <章节>。

目标：<一句话>

必读：
- docs/nexus-improvement-roadmap.md 第 1.3 节（不可削弱的核心原则）
- .trellis/spec/backend/retrieval-and-version-knowledge.md
- <本次涉及的具体文件>

范围：
- 允许修改：<明确列出>
- 禁止修改：<明确列出，尤其是不相关的未提交改动>

实现要求：
1. <具体要求>
2. 复用现有服务，不复制业务逻辑
3. 任何 catch 必须记日志或抛出，禁止静默吞异常
4. 新增外部调用必须设置连接与读取超时

验收：
- [ ] <可执行的验收项>
- [ ] ./mvnw -B verify 通过
- [ ] git diff --check 通过
- [ ] 新增/修改的行为有对应测试
```

### 6.4 每次改动的验证命令

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify
git diff --check
./scripts/nexus.sh start && curl -s localhost:8080/api/runtime/status | jq
```

### 6.5 优先级建议

如果时间有限，按这个顺序做收益最大：

1. **0.6 MCP Server**（没有它，前面所有能力对开发都是"看得见用不上"）
2. **B1 多语言索引**（决定了能覆盖多少团队）
3. **A2 容器化**（决定了能不能推广出去）
4. **D1/D2 安全与限流**（决定了能不能放心推广）
5. **B2 符号级影响分析**（决定了产品的差异化价值）

---

## 附录：缺陷 → 版本对照表

| 缺陷 | 级别 | 解决版本 |
|------|------|----------|
| A1 无 MCP Server | P0 | 0.6 |
| A2 无可分发运行形态 | P0 | 0.9 |
| A3 仓库需同机 | P0 | 0.9 |
| A4 单机文件存储 | P1 | 0.9 |
| A5 前端手写静态页 | P2 | 0.9 |
| B1 仅支持 Java | P0 | 0.7 |
| B2 无符号级影响分析 | P1 | 0.7 |
| B3 重排未入管线 | P1 | 0.8 |
| B4 串行无缓存 | P1 | 0.8 |
| B5 评测集小无门禁 | P1 | 0.8 |
| B6 无真实测试结果 | P1 | 0.9 |
| B7 超时配置不全 | P2 | 0.5 |
| C1 吞异常 | P1 | 0.5 |
| C2 兼容构造堆积 | P2 | 0.7 |
| C3 大类 | P2 | 0.8 |
| C4 spec 未填写 | P2 | 持续 |
| C5 CI 单薄 | P1 | 0.8 |
| D1 key 无轮转无审计 | P0 | 0.9 |
| D2 无限流配额 | P0 | 0.9 |
| D3 认证可整体关闭 | P1 | 0.5 |
| D4 仓库卫生 | P2 | 0.5 |
| D5 无运维文档 | P2 | 1.0 |
