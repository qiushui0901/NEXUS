# NEXUS

**让 AI 写代码时，不再瞎编需求。**

NEXUS 是一个面向研发团队的证据型知识平台：把产品需求、Git 仓库与测试信号整理成按**项目 + 版本**隔离的知识服务。人浏览同一套 Wiki；Cursor / Codex 里的编码 Agent 通过 **MCP** 调用同一批事实——每条结论都带可点开的 `requirement:*` / `code:*` 证据编号，而不是一段无法核对的模型输出。

> **93.6% Recall@1 · 99.6% Recall@10 · MRR 0.9596**
> —— 封神项目 500 道真实代码定位题，四套系统同题对比第一
> （对照：BM25 MCP 92.0%、RAGFlow 78.6%、LightRAG 46.6%，详见[评测报告](docs/fengshen-code-retrieval-four-way-comparison.md)）

---

## 你可能正遇到这些问题

| 症状 | NEXUS 的回答 |
|------|--------------|
| 问 AI「这个需求怎么实现」，它给你编了一版规则 | 检索结果只允许引用本次召回的需求证据，编号可回查原文 |
| 文档更新到 v2，AI 还在拿 v1 的规则回答 | 需求按 `documentId + version` 严格隔离，版本泄漏在评测里是硬性用例 |
| 代码同名方法满仓库，检索分不清 `handle` 在哪个文件 | Tree-sitter 符号图 + 精确符号通道 + 类名限定召回，按文件+符号双命中统计 |
| 改一个方法，不知道会波及谁 | 静态调用图影响分析，`EXACT` / `SAME_FILE` / `HEURISTIC` / `UNRESOLVED` 分级呈现，不夸大 |
| AI 生成的 Wiki 看着像样，没人敢用 | 一切自动产物都是**草稿**，人工审核发布后才成为正式知识 |

---

## 它是怎么工作的

```text
需求文档（PDF/DOCX/XLSX/HTML/ZIP）      Git 仓库（Java/Go/Python/TS）
        │                                      │
        ▼                                      ▼
  结构化分块 + 版本快照                 Tree-sitter 符号 + 调用图
        │                                      │
        └──────────────┬───────────────────────┘
                       ▼
              统一检索管线（dense+sparse RRF → BGE/LLM 重排）
                       │
        ┌──────────────┼──────────────────┐
        ▼              ▼                  ▼
   版本化 Wiki     开发方案/存疑评审      MCP 服务（/mcp）
  （草稿→审核→发布）  （证据白名单引用）    （10 个工具，喂给 IDE Agent）
```

**给人用的**：知识库管理台（导入进度、失败重试、检索测试）、GitLab 管理台（连接、同步、Webhook）、版本化 Wiki、监控面板。

**给 Agent 用的**：`nexus_search_requirements`、`nexus_search_code`、`nexus_get_source`、`nexus_code_graph`、`nexus_impact_analysis`、`nexus_development_plan`、`nexus_review_doubts`、`nexus_conflict_check` 等 MCP 工具，全部返回 `resolved / data / evidence / quality / warnings / truncated` 结构化信封。

---

## 核心能力

- **证据契约** — 每条结论绑定稳定证据 ID；模型只能引用本次请求召回的白名单，越界引用直接过滤
- **版本隔离** — 需求证据按 `documentId + version` 过滤；需求版本落后产品版本时，显式标记 `REQUIREMENT_VERSION_BEHIND`，而不是把旧需求冒充新需求
- **代码智能** — 多语言 AST 索引、SQLite 符号调用图（按 commit 快照）、保守分级影响分析
- **业务项目多仓库** — 一个业务项目挂多个 GitLab 仓库：共享一套需求、聚合代码检索、每条命中携带仓库 ID 与 commit
- **GitLab 自动接入** — PAT 账号发现、批量导入、Webhook 推送增量索引；凭据 AES-256-GCM 加密，Secret 只展示一次
- **知识生命周期** — Wiki 草稿 → 评审 → 发布 → 过期检测，全链路有人工闸门
- **安全降级** — 依赖故障返回 `DEGRADED` + 警告码；「真没结果」和「服务挂了」永远是两种状态
- **需求语义图（实验）** — 借鉴 LightRAG 思路的实体/关系抽取，但强制绑定原文证据、按版本隔离、默认关闭，未经审核不进生产链路

---

## 快速开始

```bash
cp .env.example .env          # 填写 OpenAI 兼容网关地址与 Token
./scripts/nexus.sh start      # 启动本地 Qdrant + 应用
```

Embedding 与 LLM 统一走 OpenAI 兼容网关，不需要本地跑模型。启动后：

| 入口 | 地址 |
|------|------|
| 首页 | http://localhost:8080/ |
| 知识库管理 | http://localhost:8080/knowledge |
| Wiki | http://localhost:8080/wiki |
| GitLab 管理 | http://localhost:8080/settings/gitlab |
| MCP | http://localhost:8080/mcp |

把 MCP 接进 Cursor / Codex 只需三行配置（见 [MCP 快速入门](docs/mcp-quickstart.md)）：

```json
{
  "mcpServers": {
    "nexus": {
      "url": "http://127.0.0.1:8080/mcp",
      "headers": { "X-API-Key": "${env:NEXUS_API_KEY}" }
    }
  }
}
```

30 秒冒烟：上传一份你熟悉的需求文档，问一个文档里有的问题，检查返回里的 `requirement:*` 证据能否对回原文——完整清单见[人工冒烟手册](docs/manual-smoke-test.md)。

---

## 文档

| 文档 | 内容 |
|------|------|
| [用户指南](docs/user-guide.md) | 安装、配置、需求摄入、代码索引、API |
| [MCP 快速入门](docs/mcp-quickstart.md) | Cursor / Codex / Claude Code 配置与排障 |
| [人工冒烟手测](docs/manual-smoke-test.md) | 亲手验证效果的可复制清单 |
| [四向检索对比](docs/fengshen-code-retrieval-four-way-comparison.md) | NEXUS vs BM25 MCP vs RAGFlow vs LightRAG |
| [GitLab 自动接入](docs/gitlab-auto-onboarding-guide.md) | 账号连接、批量导入、Webhook |
| [multipow × NEXUS](docs/multipow-nexus-integration.md) | Agent 工作区脚手架与证据闸门 |
| [更新日志](CHANGELOG.md) | 版本变更记录 |

---

## 架构一览

| 层 | 职责 |
|----|------|
| 摄入 | Tika 文档解析、结构感知分块、来源级差量导入、GitLab 同步 |
| 存储 | Qdrant（向量）、SQLite（符号图 / 知识管理状态 / 业务项目目录）、文件型 Wiki 与版本档案 |
| 检索 | `RetrievalPipeline`：路由、并行召回、超时熔断、分级降级 |
| 知识 | Wiki 生成、版本对比、草稿生命周期、冲突检测、需求语义图（实验） |
| 访问 | REST + SSE + MCP；项目级权限，身份由网关头管理 |

**技术栈**：Java 21 · Spring Boot 4.1 · Spring AI 2.0 · Qdrant · Tree-sitter · SQLite · Docker Compose · Prometheus / OTLP

**代码边界**：开发在本机仓库编辑代码；NEXUS 只读索引服务端副本，用于检索、源码摘录与影响分析。代码相关 MCP 工具全部只读，不替代本地 Git。

---

## 项目状态

`0.9.1`，活跃开发中。584 项测试、JaCoCo 门禁、MCP 契约测试均在 CI 通过。面向全组生产的加固（SSO、配额、多实例共享状态）见[路线图](docs/nexus-improvement-roadmap.md)。

自动构建只产生**草稿 / 待审核**知识。未经发布的模型输出，不得当作已确认的产品事实——这句话同样适用于 NEXUS 自己。

---

## 参与贡献

1. JDK 21，提交前跑 `./mvnw -B verify`。
2. 小而聚焦的变更；遵守证据与版本隔离契约。
3. 不提交 `.env`、向量存储或私有业务文档。
4. 改检索或版本知识前，先读 [retrieval-and-version-knowledge 规范](.trellis/spec/backend/retrieval-and-version-knowledge.md)。

许可证尚未发布，当前仅限内部评估使用。
