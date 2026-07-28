# NEXUS 0.6 MCP Server MVP

## Goal

让开发人员在 Cursor、Codex、Claude Code 等支持 MCP 的 IDE / Coding Agent 中直接调用 NEXUS，查询带可回查证据的需求、代码、源码、开发方案、Wiki 页面和版本差异，不再依赖手工切换网页或复制 REST 请求。

## Background

- NEXUS 0.5 已完成统一检索、请求级证据白名单、证据覆盖率、版本隔离、草稿审核发布和认证 fail-safe。
- 当前所有研发能力只通过 REST、SSE 和原生网页提供，仓库中没有 MCP Server 依赖或实现。
- 当前项目是 Java 21、Spring Boot 4.1、Spring AI 2.0 的单 Maven 模块。
- MCP 必须是现有领域服务之上的薄适配层，不得复制检索、生成、Wiki、版本比较或权限逻辑。

## Requirements

### R1. MCP transport and discovery

- 使用 `spring-ai-starter-mcp-server-webmvc` 和 `spring.ai.mcp.server.protocol=STREAMABLE` 提供 `/mcp` Streamable HTTP 入口。
- Spring AI 2.0 已弃用旧 MCP SSE transport，因此 0.6 不新增旧式 MCP SSE 入口；现有业务 SSE 路由保持兼容。
- 提供适用于无法直连共享 HTTP 服务场景的轻量 stdio bridge。
- MCP transport 必须与现有 REST 服务共存，不改变现有 REST/SSE 路由和响应。

### R2. Initial read-only tools

第一批工具：

1. `nexus_search_requirements`
2. `nexus_search_code`
3. `nexus_get_source`
4. `nexus_development_plan`
5. `nexus_wiki_page`
6. `nexus_version_diff`

工具层必须分别复用 `RetrievalPipeline`、`CodeKnowledgeService`、`DevelopmentPlanService`、`WikiRepository` 和 `VersionComparisonService`。

### R3. Shared response contract

- 所有工具返回统一外层结构：`resolved`、`data`、`evidence`、`quality`、`warnings`、`truncated`。
- `resolved` 回显服务端最终使用的 `projectId`、`version` 和可选 `documentId`，防止 Agent 误用默认项目或跨版本。
- 证据编号继续使用 0.5 的 `requirement:*` / `code:*` 语义和请求级白名单。
- 需求摘录、代码片段、列表长度和总响应字符数必须有硬上限；发生截断时显式标记。
- 源码路径只允许仓库相对路径，不返回凭据、向量、Qdrant 内部 ID、绝对路径或内部异常。

### R4. Authentication and authorization

- HTTP MCP 复用 `X-API-Key`、角色、权限和项目白名单，不建立第二套权限模型。
- 从 `ProjectAuthInterceptor` 中抽取可被 REST 和 MCP 共同调用的认证/项目授权服务；MCP 工具不得伪造 Servlet 请求完成鉴权。
- 无 key / 错 key / 越权项目分别保留 401 / 401 / 403 语义，不泄漏项目是否存在。
- stdio bridge 从本地安全配置读取 key 并仅转发，不打印或持久化 key。
- 共享 HTTP 部署由公司反向代理终止 TLS；0.6 不在 NEXUS 容器内管理证书。

### R5. Degradation and observability

- Qdrant、BGE 或 LLM 部分不可用时，工具返回带稳定 `warnings` 的结构化降级结果；没有任何可用核心证据时才返回工具错误。
- MCP 工具调用必须记录工具名、actor、project、version、耗时、结果状态和 warning code；不记录 query 正文、凭据或完整证据。
- MCP 指标接入现有 Micrometer / Actuator 体系。

### R6. Documentation and compatibility

- 新增 `docs/mcp-quickstart.md`，包含 Cursor、Codex 和 Claude Code 的 HTTP 配置、本地 stdio bridge、API key 使用和常见错误。
- MCP Inspector 用于协议初始化、工具发现和契约自动验证；Codex 与 Cursor 都是 0.6 的真实客户端发布门禁。
- Codex 与 Cursor 的发布门禁均使用 Streamable HTTP 直连；stdio bridge 仅作为不支持远程 HTTP 客户端的兼容入口。
- Claude Code 提供配置示例，但不作为 0.6 每次发布的阻断性人工回归门禁。
- 每个工具具有契约测试，覆盖入参校验、解析后的项目/版本、权限、降级、证据过滤、截断和路径脱敏。
- 现有 0.5 REST、SSE、页面、证据契约和安全降级行为保持兼容。

### R7. Minimal containerized delivery

- 0.6 同时提供 NEXUS 多阶段 `Dockerfile`，使用 JDK 21 构建和精简 JRE 运行。
- `compose.yml` 增加 NEXUS 服务定义并复用现有 Qdrant 等依赖，使 MCP HTTP 服务可以在干净环境中启动。
- 容器不得内置业务文档、代码仓库、向量存储、API key 或其他凭据；运行数据和仓库只通过显式 volume / environment 注入。
- 本版本只解决单实例 MCP 服务的构建、启动和健康检查；PostgreSQL、SSO、多副本和生产镜像发布仍留到 0.9。

## Constraints

- 保持单 Maven 模块，除非技术验证证明 Spring AI MCP 集成必须拆模块。
- 不在 0.6 引入数据库、SSO、多语言索引、符号级影响分析、缓存或重排管线重构。
- 不允许 MCP 直接访问 Qdrant、文件存储或 ChatClient 绕开现有服务。
- 所有新增外部调用必须设置连接和读取超时。

## Acceptance Criteria

- [x] 支持 MCP 客户端完成初始化、列出六个工具并调用。
- [ ] 真实需求查询返回受控需求/代码证据及稳定证据编号。
- [ ] `nexus_development_plan` 返回解析后的项目/版本、逐条引用和证据覆盖率。
- [x] 无 key、错误 key 和越权项目具有正确且不泄漏信息的错误语义。
- [ ] BGE 或 Qdrant 停止时返回显式降级结果，而不是无提示空结果或 500。
- [x] 所有源码路径为仓库相对路径，超限内容被截断并标记。
- [x] 六个工具均有契约测试，JDK 21 下 `./mvnw -B verify` 通过。
- [x] `git diff --check` 通过，现有 REST/SSE 回归测试保持通过。
- [x] MCP Inspector 完成初始化、工具发现和调用验证。
- [x] Codex 与 Cursor 均完成 Streamable HTTP 配置、工具发现和至少一次真实工具调用。
- [ ] `docker compose up` 能启动 NEXUS、Qdrant 和必需依赖，NEXUS 健康检查通过且镜像中不包含业务数据或凭据。

## Out of Scope

- `nexus_impact_analysis`、`nexus_code_graph`、`nexus_review_doubts` 和 `nexus_conflict_check`。
- MCP Resources 与 MCP Prompts。
- PostgreSQL、SSO/LDAP、持久化审计、配额和限流。
- 多语言代码扫描、符号级调用图和真实 CI 测试结果导入。
