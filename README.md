# NEXUS

**面向需求、代码与测试的版本化知识平台 —— 结论可回查、证据可验证。**

NEXUS 把分散的产品文档、Git 仓库与测试信号，整理成按**项目 + 版本**组织的知识服务。团队浏览同一套 Wiki；编码 Agent 通过 **MCP** 调用同一批事实，拿到带稳定编号的引用，而不是无法核对的模型空话。

> 当前版本：`0.8.5-SNAPSHOT`  
> 技术栈：Java 21 · Spring Boot 4.1 · Spring AI 2.0· Qdrant · Tree-sitter

---

## 为什么需要 NEXUS

多数「文档 RAG」能回答问题，却说不清答案来自**哪个版本、哪个项目、哪条证据**。NEXUS 按更严格的契约设计：

| 原则 | 含义 |
|------|------|
| 版本隔离 | 回答 `v1` 时不得静默使用 `v2` 内容 |
| 证据优先 | 结论带有可打开的 `requirement:*` / `code:*` 编号 |
| 安全降级 | 来源缺失标为不可用，绝不伪装成「没有变化」 |
| 草稿 ≠ 已发布 | 自动构建的 Wiki 须经人工审核后才能成为正式内容 |
| 面向 Agent | 同一套知识以 MCP 工具形式供给 Cursor / Codex |

---

## 它做什么

```text
需求文档 + Git + 测试
        │
        ▼
  索引与版本事实 ──► Wiki（JSON / Markdown / 浏览页）
        │
        ├──► 统一检索管线 ──► 存疑、开发方案、引用回查
        └──► MCP（/mcp）──► IDE Agent（检索、源码、影响、Wiki、差异）
```

**产品** — 按版本浏览功能页：规则、流程、风险与证据。  
**开发** — 检索代码（Java / Go / Python / TypeScript），阅读受控源码片段，查看调用图与影响面。  
**Agent** — 调用返回结构化数据、告警与引用质量的 MCP 工具，而不是黑盒聊天。  
**团队** — 跨需求 / 代码 / 测试 / Wiki 做版本对比，并明确标记各来源是否可用。

---

## 核心能力

- **版本化 Wiki** — 主键 `projectId + version + featureId`；可读 Markdown + 机器可读 JSON  
- **统一检索** — 需求与代码共用管线，带降级诊断  
- **引用白名单** — 模型只能引用本次请求召回的证据  
- **代码智能** — 多语言 AST 索引、SQLite 符号图、保守影响分析（`EXACT` / `SAME_FILE` vs `HEURISTIC` / `UNRESOLVED`）  
- **MCP 服务** — `/mcp` Streamable HTTP，适配 Cursor、Codex 等客户端  
- **冲突检测** — 结构化声明比对；Wiki 不得覆盖需求 / 代码 / 测试原始证据  
- **可运维** — Docker Compose、健康检查、Prometheus / OTLP

---

## 快速开始

```bash
cp .env.example .env          # 填写网关 Token 与服务地址（OpenAI 兼容）
./scripts/nexus.sh start      # 本地 Qdrant + 应用（详见用户指南）
```

Embedding 与 LLM 统一走 OpenAI 兼容网关（`text-embedding-v4` + 按任务路由的生成/重排模型），
不依赖本地模型服务。启动前确认 `.env` 中的 `OPENAI_BASE_URL` / `OPENAI_API_KEY` 可达。

启动后访问：

| 入口 | 地址 |
|------|------|
| 首页 | http://localhost:8080/ |
| Wiki | http://localhost:8080/wiki |
| 版本中心 | http://localhost:8080/versions |
| 监控 | http://localhost:8080/monitor |
| MCP | http://localhost:8080/mcp |

Compose 共享部署、MCP 客户端、索引与 API 说明见下方文档，不在本 README 展开。

---

## 文档

| 文档 | 内容 |
|------|------|
| [生态介绍页](docs/nexus-ecosystem.html) | NEXUS × nexuspow 一页总览（可浏览器直接打开） |
| [用户指南](docs/user-guide.md) | 安装、配置、运行、代码索引、Wiki、API、数据边界 |
| [人工冒烟手测](docs/manual-smoke-test.md) | 自己验证需求/代码/MCP 效果的可复制清单 |
| [MCP 快速入门](docs/mcp-quickstart.md) | Cursor / Codex / Claude Code 配置与排障 |
| [multipow × NEXUS](docs/multipow-nexus-integration.md) | Agent 工作区脚手架与证据闸门（含代码双副本模型） |
| [改进路线图](docs/nexus-improvement-roadmap.md) | 缺陷清单与迈向全组可用 / GA 的版本计划 |
| [更新日志](CHANGELOG.md) | 版本变更记录 |

---

## 架构概览

| 层 | 职责 |
|----|------|
| 摄入 | 需求文档（Tika）、代码扫描、可选测试快照 |
| 存储 | Qdrant（向量）、SQLite（符号图）、文件型 Wiki / 档案 / 草稿 |
| 检索 | `RetrievalPipeline`：路由、混合检索、限流、告警 |
| 知识 | Wiki 生成、版本对比、草稿生命周期、冲突分析 |
| 访问 | REST + SSE + MCP；身份与权限由外部统一网关管理 |

**代码模型（重要）：** 开发在本机仓库编辑（例如 multipow 工作区）。NEXUS 保留**服务端索引副本**，用于检索、源码摘录与影响分析。面向代码的 MCP 工具为**只读**，不替代本地 Git。

---

## 技术栈

- **运行时：** Java 21、Spring Boot 4.1、Spring AI 2.0  
- **检索：** Qdrant dense + desc_dense 双向量 + sparse、LLM 重排（BGE 分差可跳过）、OpenAI 兼容网关（嵌入 + 生成）  
- **代码：** Tree-sitter 多语言解析、SQLite 调用图、LLM 语义标注  
- **交付：** Maven、Docker / Compose、Actuator、Prometheus、OpenTelemetry  

---

## 项目状态

NEXUS 仍在活跃开发（`0.8.x`）。证据检索、版本化 Wiki、MCP 与多语言代码智能已具备基础能力。面向全组生产的加固（共享仓库同步、SSO、配额、更大评测门禁等）见 [路线图](docs/nexus-improvement-roadmap.md)。

自动构建只产生**草稿 / 待审核**知识。未经发布的模型输出，不得当作已确认的产品事实。

---

## 参与贡献

1. 使用 JDK 21，提交前运行 `./mvnw -B verify`。  
2. 优先小而聚焦的变更；遵守现有证据与版本隔离约定。  
3. 不要提交 `.env`、Qdrant 存储、向量或私有业务文档。  
4. 改动检索或版本知识时，先读 [`.trellis/spec/backend/retrieval-and-version-knowledge.md`](.trellis/spec/backend/retrieval-and-version-knowledge.md)。

欢迎通过 Issue / MR 反馈问题与设计。正式的 `CONTRIBUTING.md` 与开源许可证将在对外打包时补齐。

---

## 许可证

本仓库尚未发布许可证文件。在明确许可证之前，请仅作内部评估使用。
