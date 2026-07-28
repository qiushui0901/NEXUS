# NEXUS 0.7 多语言索引与影响分析

## Goal

让 Java 以外的团队能够复用 NEXUS 代码知识，并让开发者在 Codex、Cursor 或 REST 中回答“这个符号或 commit 会影响什么”，得到带静态依据、置信度和显式未解析边界的结果。

## Background

- `docs/nexus-improvement-roadmap.md` 将 0.7 定义为多语言代码索引与符号级影响分析版本。
- 当前 `JavaCodeScanner` 使用正则且只扫描 `.java`；`IncrementalCodeIndexService` 同样硬编码 Java 扩展名。
- `CodeChunk`、Qdrant 代码 payload、代码检索和证据编号已经稳定，0.7 必须保持向后兼容。
- 现有 `/api/code/graph` 是基于语义命中的展示图，不是持久化静态调用图。
- 旧规划任务 `07-16-ast-code-indexing` 只覆盖 JavaParser shadow collection；本任务以路线图的多语言与影响分析为准，不沿用其 Java-only 范围。
- Tree-sitter 官方 Java 绑定要求 JDK 22+，与项目 Java 21 基线冲突；Tree-sitter NG 支持 Java 21 运行环境和目标容器架构。

## Requirements

### R1 — Multi-language scanner contract

- 抽象统一 `CodeScanner` 契约和语言注册表，完整索引与增量索引不得再硬编码 `.java`。
- 使用 Tree-sitter AST 支持 Java、Go、Python 三种硬门槛语言；同时支持 TypeScript 作为第四种常用语言。
- Kotlin 保留明确的语言注册/扩展点；若本轮未达到与前三种相同的 AST 质量，必须显式标记为未启用，不得伪装成功。
- `CodeChunk` 新增 `language`，旧构造方式、旧 Qdrant payload 和现有消费者保持可读。
- 解析单个文件失败时记录安全日志和文件级诊断；不得静默吞异常，也不得让一个坏文件中断整个仓库索引。

### R2 — Symbol and relation model

- 为类型、函数/方法和可定位入口生成稳定 `CodeSymbol`。
- 提取静态调用关系，至少包含 caller、目标名称、解析后的 callee（若唯一可确定）、文件和行号。
- 关系必须标记 `EXACT`、`SAME_FILE`、`HEURISTIC` 或 `UNRESOLVED` 置信度/解析状态。
- 反射、依赖注入、消息、动态分派或重名歧义无法可靠解析时保留 `UNRESOLVED`，不得计入确定影响。
- 图数据不得包含源码全文、向量、凭据或仓库绝对路径。

### R3 — Persistent graph and indexing lifecycle

- 符号图独立持久化到 SQLite；Qdrant 继续只保存语义检索 chunk。
- 完整索引以项目和 commit 为边界原子替换图数据。
- 增量索引同时处理新增、修改、删除和重命名文件，清理旧 symbol/relation 后写入新结果。
- SQLite 写入必须事务化并适配当前单实例部署；0.9 多实例迁移前不宣称支持多副本写入。

### R4 — Impact analysis

- 给定 `projectId + symbol`，支持向上调用者和向下被调用者遍历，可限制方向、深度和结果数。
- 给定 `projectId + fromCommit + toCommit`，从 Git 文件差异映射到变更符号，再扩展到受影响调用者。
- 返回确定影响、推测影响、未解析边和建议回归入口，逐项携带依据与置信度。
- 结果路径全部为仓库相对路径，并受 MCP 0.6 的总响应、摘录和列表上限保护。
- 若图数据或 commit 快照不可用，显式返回 `NOT_AVAILABLE`/warning，并保留文件级差异作为降级依据。

### R5 — Interfaces

- 新增受既有权限与项目白名单保护的 REST 影响分析和调用子图接口。
- 新增只读 MCP 工具 `nexus_impact_analysis` 和 `nexus_code_graph`。
- `nexus_search_code` 返回语言字段但不破坏现有字段。
- MCP 返回继续使用 `resolved / data / evidence / quality / warnings / truncated` 外层契约。

### R6 — Compatibility and safety

- 保留 REST、SSE、现有六个 MCP 工具、证据 ID 和文件级版本差异兼容性。
- 禁止绝对路径、Git 任意参数、静默异常、无界图遍历和跨项目图边。
- Tree-sitter 原生库必须在开发机架构及 Linux x86_64/aarch64 容器上可加载。

## Acceptance Criteria

- [x] Java、Go、Python fixture 均能提取稳定符号、行号、语言和调用关系；TypeScript 能完成索引与基本关系提取。
- [x] 完整索引与增量索引共用语言注册表，不存在 `.java` 专用过滤分支。
- [x] 给定本仓库一个方法，调用者/被调用者结果与 fixture 预期一致。
- [x] 给定两个测试 commit，返回变更符号、确定/推测影响、未解析边和建议回归范围。
- [x] 动态或歧义调用标记 `UNRESOLVED`，不进入确定影响计数。
- [x] SQLite 图更新具备项目/commit 隔离、事务回滚及删除/重命名回归测试。
- [x] REST 与 MCP 权限、项目隔离、响应截断和路径脱敏测试通过。
- [x] 现有代码检索、开发方案、Wiki、版本差异和六个 MCP 工具回归通过。
- [ ] Codex 与 Cursor 至少各调用一次 `nexus_impact_analysis` 或 `nexus_code_graph`。
- [x] JDK 21 下 `./mvnw -B verify` 和 `git diff --check` 通过。

复测记录（2026-07-28）：JDK 21 完整 `verify` 共 181 项测试通过；Codex 已完成
`nexus_code_graph` 调用。Cursor 已连接 `/mcp` 并完成协议初始化，服务端识别到 Cursor
1.0.0；实际工具调用被 Cursor 团队用量上限阻断，因此客户端双门禁中的 Cursor 一侧
暂不勾选。

## Out of Scope

- 跨仓库符号解析、完整类型推断、运行时调用追踪和 100% 动态分派解析。
- 0.8 的重排、并发召回、缓存与 CI 检索质量门禁。
- 0.9 的服务端仓库 clone/fetch、PostgreSQL、SSO、限流、多实例和 Vue 工作台。
- 将静态推测自动发布为 Wiki 已验证事实。
