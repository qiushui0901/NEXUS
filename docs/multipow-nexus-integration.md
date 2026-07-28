# multipow × NEXUS 集成方案

> 状态：可执行设计（给 Codex / 人工分仓实现）
> 日期：2026-07-28
> 关联：`docs/nexus-improvement-roadmap.md`、`docs/mcp-quickstart.md`、multipow `cli/`
> 原则：**不合并仓库**；multipow 管流程，NEXUS 管证据；用 MCP + skills 衔接。
> **代码专节：第 3 节**（双副本、索引、MCP 只读、Impact Gate、试点缺口）。

---

## 1. 目标与非目标

### 1.1 Goals

1. 开发用 `multipow` 开工作区后，Cursor / Codex **自动具备 NEXUS MCP**，无需手配。
2. Agent 走 multipow 工作流时，**先查 NEXUS 证据再写文档/改代码**。
3. 公司正式知识只认 NEXUS；multipow 本地 `docs/` / 可选本地 wiki 只做任务笔记。
4. 组内可试点：共享一台 NEXUS + 每人 `NEXUS_API_KEY`，不要求每人本地跑全套依赖。

### 1.2 Non-Goals（本方案不做）

- 不把 NEXUS 打进 multipow npm 包，不做本地嵌入式知识库。
- 不把 multipow skills 搬进 NEXUS 仓库。
- 不在本阶段做 SSO / 自动发 Key（沿用现有 `X-API-Key`）。
- 不自动把个人 `docs/` 发布进正式 Wiki（回流走显式审核，见第 7 节）。
- 不改 NEXUS 检索算法、不引入 0.8/0.9 范围外能力。

### 1.3 成功标准（试点）

| 指标 | 通过条件 |
|------|----------|
| 开箱可用 | `multipow init` 后工作区含 MCP 配置；Cursor/Codex 能看到 `nexus_*` 工具 |
| 流程闸门 | brainstorm / plan / implement 至少各强制一次 NEXUS 调用（有则用，无则显式降级） |
| 证据可回查 | proposal / plan 中出现 `requirement:*` 或 `code:*`，或写明 `NEXUS_UNAVAILABLE` |
| 无密钥入库 | 工作区配置只引用 `${env:NEXUS_API_KEY}`，不写明文 |

---

## 2. 架构

```text
┌─────────────────────────────────────────────────────────┐
│  multipow CLI（本机）                                     │
│  init → 模板 / skills / MCP 配置 / MEMORY 占位            │
└───────────────────────────┬─────────────────────────────┘
                            │ 生成工作区
                            ▼
┌─────────────────────────────────────────────────────────┐
│  Agent Workspace                                          │
│  AGENTS.md + MEMORY.md + docs/ + .cursor/mcp.json         │
│  .codex/config.toml + .agents/skills/multipow-*           │
└───────────────────────────┬─────────────────────────────┘
                            │ MCP Streamable HTTP
                            ▼
┌─────────────────────────────────────────────────────────┐
│  NEXUS（组内共享服务）                                     │
│  /mcp → nexus_search_* / development_plan / impact_...    │
│  证据白名单 + 版本隔离 + 项目权限                           │
└─────────────────────────────────────────────────────────┘
```

**唯一真相**：公司需求 / 代码符号 / 版本 Wiki / 影响分析 → NEXUS。
**任务上下文**：当前需求澄清、设计、计划、验收 → multipow `docs/`。

---

## 3. 代码怎么处理（核心）

> 本节是集成里最需要先对齐的部分：**NEXUS 只读懂并检索代码；真正改代码只在本机 multipow 工作区完成。**

### 3.1 双副本模型

```text
┌─ multipow 工作区（开发者本机）─────────────────────────┐
│  <workspace>/<repo>/     ← git clone 的可写真源码         │
│  Agent / 人在这里编辑、编译、测试、commit / push           │
└────────────────────────────────────────────────────────┘
                    ▲ 只通过 MCP 问「在哪、影响谁」
                    │ 不通过 MCP 写仓库文件
┌─ NEXUS 服务机 ─────────────────────────────────────────┐
│  CODE_REPOSITORY_PATH / PROJECT_N_REPO_PATH              │
│  （compose 试点：nexus_repository → /workspace/repository）│
│  扫描 → Qdrant 语义索引 + SQLite 符号调用图                │
│  对外：search / source / graph / impact（只读）            │
└────────────────────────────────────────────────────────┘
```

| 动作 | 谁做 | 说明 |
|------|------|------|
| clone / 编辑 / 提交 | multipow 工作区（本机） | 开发真源 |
| 索引 / 语义搜 / 调用图 / 影响分析 | NEXUS（共享） | 组内只读知识 |
| 决定改哪里、回归什么 | Agent 读 NEXUS → 写入 `docs/` → 再改本机代码 | Impact Gate |

**禁止假设**：multipow clone 完的仓库会自动进入 NEXUS；本机未 push 的改动会出现在 NEXUS impact 里。两边是不同副本，靠 `projectId` + 已索引 commit 对齐。

### 3.2 NEXUS 代码入库链路

```text
仓库路径
  → MultiLanguageCodeScanner（Tree-sitter：Java / Go / Python / TypeScript；Kotlin 能力探测）
  → 两路写入：
       Qdrant CodeChunk     —— 语义检索（nexus_search_code）
       SQLite Symbol + Call —— 静态图（nexus_code_graph / nexus_impact_analysis）
```

| 方式 | 入口 | 行为 |
|------|------|------|
| 全量索引 | `POST /api/code/index` 或 `/index/start` | 扫整仓，替换项目向量；图按 project+commit 事务替换 |
| 增量索引 | `POST /api/code/incremental-index` 或 GitLab webhook | Git diff → 只重扫支持语言的变更文件 → upsert chunk；仅当工作区 HEAD 与目标 commit 一致时才替换图快照 |
| 读源码 | `GET /api/code/source` / `nexus_get_source` | 仓库相对路径 + 行号；校验路径不逃逸；行数上限 |

索引产物约束（与 NEXUS 安全边界一致）：

- 图与检索 payload **不存**源码全文、向量原文外泄、凭据、绝对路径。
- 对外路径一律仓库相对路径。

### 3.3 Agent 侧代码查询（MCP）

| MCP | 底层 | 用途 |
|-----|------|------|
| `nexus_search_code` | Qdrant hybrid | 「相关类/方法在哪」→ 稳定 `code:*` 证据 |
| `nexus_get_source` | 读服务端仓库文件 | 按相对路径取受控片段 |
| `nexus_code_graph` | SQLite 图 | 符号上/下游调用（depth/limit 有界） |
| `nexus_impact_analysis` | 图 ± Git diff | 改某符号，或某 commit 范围会影响谁 |

影响分析置信度（实现必须遵守，skills 文档也要写清）：

- **确定影响**：仅 `EXACT` / `SAME_FILE` 边
- **推测影响**：`HEURISTIC`，单独列出，不得写成已确认
- **UNRESOLVED**：反射、DI、重名歧义等，**不计入**确定影响
- 目标 commit 无图快照 → `NOT_AVAILABLE`，降级返回**文件级差异**，并带 warning

`nexus_development_plan` 会同时拉需求 + 代码证据，但仍是只读建议；落盘改文件只发生在本机仓库。

### 3.4 明确不做什么

1. **MCP 不写代码**：全部 `readOnlyHint`；不替代本地 Git。
2. **不把 NEXUS 当工作副本**：服务端挂载仓用于索引/读片段，不是开发者 push 目标（除非运维另行规定）。
3. **不把向量/图输出当已验证实现事实**：未经需求核验不得写入正式 Wiki。
4. **不自动把 multipow clone 注册进 NEXUS**：试点靠运维配置 `projectId` + 仓库路径并先跑索引；服务端自助 clone 属 0.9。

### 3.5 编码时序（与 Skills 对齐）

```text
1. multipow 将业务仓 clone 到本机工作区          ← 写代码的地方
2. 确认 NEXUS 已对同一 projectId 完成索引        ← 查代码的地方
3. implement 前 Impact Gate：
     nexus_search_code → 定位
     nexus_impact_analysis → 写入 docs/impact-notes.md（或 plan 段）
4. Agent 只在本机 <workspace>/<repo>/ 改文件并跑测试
5. push 后（可选）webhook / 增量索引 → NEXUS 跟上
```

第 3 步即第 6 节 `multipow-implement` 的 **Impact Gate**：问影响，不改远端仓。

### 3.6 当前缺口（试点必须知情）

| 缺口 | 影响 | 应对 |
|------|------|------|
| 服务端仍要本地/挂载路径 | 不能只填 Git URL | compose volume 挂仓；MEMORY 写明已索引 commit |
| multipow clone ≠ NEXUS 入库 | 新人开完工作区仍可能搜不到代码 | 试点清单增加「确认 projectId 已索引」 |
| 未 push 的本地改动 | impact / graph 看不到 | 以服务端已索引 commit 为准；文档标明 |
| 增量时 HEAD ≠ newSha | 可能跳过图快照更新 | 运维保证索引机 checkout 到目标 commit，或先全量 |

### 3.7 MEMORY.md 代码相关字段

在 NEXUS 段中补充（模板与 init 预填）：

```markdown
- Code project ID: (usually same as Project ID)
- Indexed commit (NEXUS side): UNKNOWN | <sha>
- Local repo path(s): <workspace-relative>
- Index note: (e.g. last full index time, webhook enabled?)
```

Agent 在调用 `nexus_impact_analysis`（commit 模式）前，应核对 MEMORY 中的 Indexed commit；不一致时先警告用户。

---

## 4. 配置契约

### 4.1 环境变量（开发者本机 / CI Agent）

| 变量 | 必填 | 说明 |
|------|------|------|
| `NEXUS_API_KEY` | 是（接 NEXUS 时） | API Key，不入库 |
| `NEXUS_MCP_URL` | 否 | 默认 `https://nexus.internal/mcp`；本地调试可用 `http://127.0.0.1:8080/mcp` |
| `NEXUS_PROJECT_ID` | 建议 | 可写入 MEMORY；缺省时 Agent 必须向用户确认 |
| `NEXUS_VERSION` | 建议 | 业务需求版本，不是 NEXUS 平台版本 |

### 4.2 工作区文件（multipow 生成）

```text
<workspace>/
  AGENTS.md                 # 增加 NEXUS Evidence Gate
  MEMORY.md                 # 增加 NEXUS 段
  .cursor/mcp.json          # Cursor MCP
  .codex/config.toml        # Codex MCP（或增量片段）
  docs/
    .gitkeep
  .agents/skills/           # 改造后的 multipow-* skills
```

### 4.3 `.cursor/mcp.json` 模板

路径：`cli/assets/templates/cursor-mcp.json`（init 时复制为 `.cursor/mcp.json`）

```json
{
  "mcpServers": {
    "nexus": {
      "url": "${env:NEXUS_MCP_URL}",
      "headers": {
        "X-API-Key": "${env:NEXUS_API_KEY}"
      }
    }
  }
}
```

若目标 Cursor 版本不支持 url 里的 `${env:NEXUS_MCP_URL}`，则：

- 模板写死占位注释字段 `NEXUS_MCP_URL_PLACEHOLDER`
- init 时用环境变量或 catalog 扩展字段替换为真实 URL
- **禁止**把 API Key 写进文件

推荐默认 URL（组内）：`https://nexus.internal/mcp`
本地开发覆盖：`export NEXUS_MCP_URL=http://127.0.0.1:8080/mcp`

### 4.4 `.codex/config.toml` 模板片段

路径：`cli/assets/templates/codex-mcp.toml`（init 时写入 `.codex/config.toml`，若已存在则 **追加且不覆盖** 其他段）

```toml
[mcp_servers.nexus]
url = "https://nexus.internal/mcp"
env_http_headers = { "X-API-Key" = "NEXUS_API_KEY" }
startup_timeout_sec = 20
tool_timeout_sec = 120
```

`url` 在 init 时可被 `NEXUS_MCP_URL` 替换。

### 4.5 MEMORY.md 新增段

在现有模板末尾（`## Learnings` 之前）插入：

```markdown
## NEXUS Knowledge Service

- Enabled: YES | NO | UNKNOWN
- MCP URL: (from NEXUS_MCP_URL or workspace MCP config)
- Project ID:
- Requirement document ID:
- Business version:
- Default code project ID: (usually same as Project ID)
- Indexed commit (NEXUS side): UNKNOWN | <sha>
- Local repo path(s):
- Notes: (auth, known gaps, NEXUS_UNAVAILABLE reason)

### Evidence discipline

- Prefer NEXUS tools before guessing requirements or call graphs.
- Record stable evidence IDs (`requirement:*`, `code:*`) in docs when used.
- Never paste API keys or absolute private paths into MEMORY.md.
- Local cloned repos are for editing; NEXUS indexed commit may lag behind unpushed local work.
```

`initializeWorkspace` 渲染 MEMORY 时：若环境有 `NEXUS_PROJECT_ID` / `NEXUS_VERSION`，预填；否则留空并标 `TODO`。代码相关字段（Indexed commit、Local repo path）见第 3.7 节，模板一并带上。

### 4.6 AGENTS.md 新增段

在 `## Read First` 之后增加：

```markdown
## NEXUS Evidence Gate

When NEXUS MCP tools are available in this workspace:

1. Before brainstorming or planning a feature, call `nexus_search_requirements`
   and/or `nexus_wiki_page` with the projectId and version from MEMORY.md.
2. Before implementing non-trivial code changes, call `nexus_search_code`
   and `nexus_impact_analysis` (symbol or commit range).
3. Prefer evidence IDs over paraphrased memory. If NEXUS is down or unauthorized,
   write `NEXUS_UNAVAILABLE: <reason>` into the current docs and continue with
   explicit uncertainty — do not invent requirement or impact facts.
4. Local `docs/` is task-scoped. Company-truth knowledge lives in NEXUS Wiki.
```

---

## 5. multipow CLI 改动（仓库：`multipow`）

### 5.1 init 任务扩展

文件：`cli/src/initWorkspace.ts`

在现有 copy skills 任务后增加（均 **no-overwrite**）：

| Task name | 行为 |
|-----------|------|
| `Ensure .cursor directory` | `ensureDir(.cursor)` |
| `Copy Cursor MCP config` | `templates/cursor-mcp.json` → `.cursor/mcp.json` |
| `Ensure .codex directory` | `ensureDir(.codex)` |
| `Write Codex MCP config` | 无文件则写模板；有文件则若不含 `[mcp_servers.nexus]` 则追加 |
| `Render MEMORY NEXUS section` | 已含在 MEMORY 模板；渲染时注入 env 默认值 |

失败策略：MCP 模板缺失 → init **失败**（与现有 assets 校验一致）。
已有 `.cursor/mcp.json` → **不覆盖**（与 AGENTS/MEMORY 策略一致），在最终 summary 提示用户手动合并。

### 5.2 Catalog 可选扩展（非必须，Phase 2）

在 catalog item 或顶层增加可选字段，便于按业务线预填：

```json
{
  "nexus": {
    "mcpUrl": "https://nexus.internal/mcp",
    "projectId": "example-service",
    "documentId": "requirements",
    "version": "2026.07"
  },
  "repos": [ ... ],
  "wikis": [ ... ]
}
```

交互 init 确认步展示这些值；写入 MEMORY。无该字段时不影响现有 catalog。

### 5.3 测试

`cli/src/initWorkspace.test.ts` 增补：

1. init 后存在 `.cursor/mcp.json`，且内容不含明文 key。
2. 二次 init 不覆盖已有 MCP 文件。
3. MEMORY 含 `## NEXUS Knowledge Service`。
4. AGENTS 含 `## NEXUS Evidence Gate`。

---

## 6. Skills 改造（仓库：`multipow`）

改造原则：

- **有 NEXUS 则必须先调用**；调用失败 → 文档中写 `NEXUS_UNAVAILABLE`，不得假装查过。
- 工具名以 NEXUS 0.7 为准（见下表）。
- `projectId` / `version` 优先读 MEMORY；缺失则 **先问用户**，禁止猜。

### 6.1 工具映射

| 场景 | MCP Tool | 最小入参 |
|------|----------|----------|
| 查需求 | `nexus_search_requirements` | query, projectId, version |
| 读 Wiki 功能页 | `nexus_wiki_page` | version, featureId, projectId |
| 生成入手方案 | `nexus_development_plan` | query, projectId, version |
| 查代码 | `nexus_search_code` | query, projectId |
| 读源码 | `nexus_get_source` | filePath, projectId, startLine?, endLine? |
| 版本差异 | `nexus_version_diff` | fromVersion, toVersion, projectId |
| 影响分析 | `nexus_impact_analysis` | projectId + symbol **或** fromCommit+toCommit |
| 调用图 | `nexus_code_graph` | symbol, projectId, direction?, depth? |
| 需求存疑 | `nexus_review_doubts` | documentId, version, projectId? |

### 6.2 `multipow-brainstorm`

在 Hard Gate 之后、写 `docs/proposal.md` 之前增加 **Evidence Pass**：

1. 读 MEMORY 的 NEXUS 段；若 `Enabled: NO` → 跳过并在 proposal 记录原因。
2. 否则调用：
   - `nexus_search_requirements`（用户问题作 query）
   - 若已知 featureId → `nexus_wiki_page`
3. `docs/proposal.md` 增加章节：

```markdown
## NEXUS Evidence

| ID | Source | Excerpt / note |
|----|--------|----------------|
| requirement:N | ... | ... |

- Coverage notes:
- Gaps / conflicts:
```

4. 无任何命中时：表格写 `none`，Goals 中标出待确认项；**禁止**把模型臆测写成已确认需求。

### 6.3 `multipow-plan`

在「读取上下文」增加：

1. 若存在 proposal 的 NEXUS Evidence，带入 plan。
2. 额外调用 `nexus_development_plan`（query = 功能一句话）。
3. `docs/plan.md` 增加：

```markdown
## Evidence & Impact Baseline

- Development plan summary: (from nexus_development_plan, bounded)
- Key requirement IDs:
- Key code IDs:
- Open risks from NEXUS warnings:
```

4. NEXUS 失败 → `NEXUS_UNAVAILABLE`，plan 仍可写，但实现任务前 implement 闸门会再次尝试。

### 6.4 `multipow-implement`

在「依次实现每个任务」之前增加 **Impact Gate**：

1. 从 plan / 用户描述提取拟改符号或模块；调用 `nexus_search_code`。
2. 对主要符号调用 `nexus_impact_analysis`（symbol 模式）。
3. 将「确定影响 / 推测影响 / UNRESOLVED / 建议回归入口」追加写入 `docs/plan.md` 或新建 `docs/impact-notes.md`。
4. 若 Impact Gate 未执行且用户未显式跳过 → **不得开始编码**（与缺 `test-case-list` 同级闸门）。
5. 用户可回复「跳过影响分析」；跳过必须记入 plan。

### 6.5 `multipow-test` / `multipow-write-case-list`

- 优先读取 `docs/impact-notes.md` 或 plan 中的回归入口。
- 用例范围覆盖：确定影响入口 + 显式 UNRESOLVED 风险项（标为探索性）。
- 不把 NEXUS「建议回归」伪装成已有测试执行结果。

### 6.6 `multipow-wiki-ingest` / `multipow-wiki-init`

文档顶部增加边界说明：

```markdown
## Boundary with NEXUS

- This skill manages a **local / cloned documentation wiki** for the workspace.
- Company versioned product knowledge is served by **NEXUS Wiki** (`nexus_wiki_page`).
- Do not tell the user that local wiki ingest publishes to NEXUS.
- To promote facts into NEXUS, use the NEXUS draft/review/publish flow (human-approved).
```

### 6.7 可选新 skill：`multipow-nexus-sync`（Phase 2）

触发词：`同步到 NEXUS`、`提交知识草稿`。

行为（只做准备，不直连写生产 Wiki）：

1. 汇总 `docs/proposal.md` / `design.md` 中的证据与结论。
2. 生成 `docs/nexus-draft-request.json`（字段对齐 NEXUS `BuildRequest`：projectId, version, documentId, …）。
3. 提示用户用 REST `POST /api/knowledge/build` 或后续 MCP 写工具提交；**本 skill 默认不调用写接口**（避免 Agent 绕过审核）。

---

## 7. 知识回流

```text
任务 docs/（个人/任务）
    → 人工确认
    → NEXUS knowledge build（DRAFT）
    → 审核 APPROVED
    → publish → 正式 Wiki
```

规则：

1. Agent **不得**直接 publish。
2. 回流内容必须带原始证据 ID 或声明缺失。
3. 与 NEXUS 冲突检测不一致时，以需求/代码原始证据为准，Wiki 为派生源。

NEXUS 侧（本方案仅文档约定，实现可另开任务）：

- 保持现有 draft lifecycle；不新增自动 publish。
- 后续可增加只写 MCP 工具 `nexus_knowledge_build`（Permission.WRITE），仍不暴露 publish。

---

## 8. NEXUS 侧配合（仓库：`request-RAG`）

本集成 **不要求** NEXUS 为 multipow 改协议；只需运维与文档配合：

| 项 | 动作 |
|----|------|
| 共享部署 | 内网 `docker compose up`；`SERVER_ADDRESS=0.0.0.0`；反代 `/mcp` |
| 发 Key | 为试点同学配置 `AUTH_USER_N_*`；每人独立 key |
| 项目白名单 | key 绑定可访问的 `projectId` |
| 文档 | 在 `docs/mcp-quickstart.md` 增加「multipow 工作区」一节（链到本文） |
| 仓库挂载 | 试点阶段继续 volume 挂代码仓；0.9 再做服务端 clone |

可选小改（P2）：

- `Dockerfile` 与 `pom.xml` 版本对齐（避免 0.7 jar 名不一致）。
- 提供 `NEXUS_MCP_URL` 健康检查示例：`GET /actuator/health`。

---

## 9. 分阶段实现（Codex 任务拆分）

### Phase A — Bootstrap（multipow，约 0.5–1 天）

**仓库**：`multipow`
**范围**：模板 + init 复制 MCP 配置 + 测试
**禁止**：改 skills 业务闸门（留 Phase B）

验收：

- [ ] `multipow init /tmp/ws-nexus` 生成 `.cursor/mcp.json`、含 NEXUS 段的 MEMORY/AGENTS
- [ ] 二次 init 不覆盖已有 MCP
- [ ] `bun test` 通过

### Phase B — Skill Gates（multipow，约 1–2 天）

**仓库**：`multipow`
**范围**：brainstorm / plan / implement / test|case-list / wiki 边界说明

验收：

- [ ] 各 skill 含可检索的 `nexus_` 工具名与 `NEXUS_UNAVAILABLE` 降级句
- [ ] implement 含 Impact Gate（可用户跳过）
- [ ] wiki skills 明确不发布到 NEXUS

### Phase C — Pilot Runbook（两边文档，约 0.5 天）

**仓库**：`request-RAG` + `multipow` README

验收：

- [ ] 一份 10 步试点手册：起 NEXUS → 发 key → multipow init → Cursor 连 MCP → 跑通 brainstorm
- [ ] `docs/mcp-quickstart.md` 链到本文

### Phase D — 回流（可选，另开任务）

- `multipow-nexus-sync` skill + NEXUS build 请求模板
- 不自动 publish

---

## 10. 给 Codex 的任务 Prompt 模板

```text
背景：
- multipow：本地 Agent 工作区 CLI（Bun/TS），仓库 `<workspace>/multipow`
- NEXUS：共享知识 MCP 服务，仓库 `<workspace>/request-RAG`
- 集成方案：request-RAG/docs/multipow-nexus-integration.md

本次只做：Phase <A|B|C>
必读：该文档第 1、3（代码模型）、4、5/6/8 节（按 Phase）及第 1.2 Non-Goals

硬性约束：
1. 不合并两个仓库
2. 不把 API Key 写入任何生成文件
3. 不新增自动 publish 到 NEXUS Wiki
4. 已有文件 no-overwrite
5. NEXUS 不可用时必须显式 NEXUS_UNAVAILABLE，禁止伪造证据
6. MCP 只读代码知识；禁止假设能通过 MCP 修改仓库文件
7. 本机 multipow clone 与 NEXUS 索引仓是双副本，不得写成同一工作树

验收：按文档第 9 节对应 Phase 勾选
```

---

## 11. 试点操作手册（给组员）

1. 运维启动共享 NEXUS（compose），确认 `https://nexus.internal/mcp` 与 health。
2. 管理员为你创建 API Key，绑定项目白名单。
3. 本机：`export NEXUS_API_KEY=...`（可选 `NEXUS_MCP_URL` / `NEXUS_PROJECT_ID` / `NEXUS_VERSION`）。
4. `npm install -g multipow`（或本地 `npm install -g ./cli`）。
5. `multipow init ~/workspaces/my-feature`（或交互 `multipow` 选仓）。
6. 打开工作区；Cursor / Codex 批准 `nexus` MCP。
7. 确认工具列表含 `nexus_search_requirements`、`nexus_search_code` 等。
8. 确认 NEXUS 侧该 `projectId` 已完成代码索引（MEMORY 填写 Indexed commit）；未索引则先让运维跑全量索引。
9. 说「帮我脑暴：xxx」→ Agent 应先查 NEXUS 再写 `docs/proposal.md`。
10. 实现前确认有 impact-notes 或显式跳过记录；改代码只在本机 `<workspace>/<repo>/`。
11. push 后确认增量索引/webhook（若已配置）使 NEXUS 跟上。
12. 任务结束后，重要结论走人工 NEXUS 草稿审核，不直接当正式 Wiki。

---

## 12. 风险与降级

| 风险 | 降级 |
|------|------|
| NEXUS 未部署 / 断网 | skills 写 `NEXUS_UNAVAILABLE`，流程可继续，质量降级可见 |
| Key 无权限 | 工具返回权限错误；Agent 不得枚举其他项目 |
| Cursor 不解析 env URL | init 用字面 URL 写入 mcp.json，key 仍只用 env |
| 两套 wiki 混淆 | skills 文案强制区分 local wiki vs NEXUS Wiki |
| Agent 跳过闸门 | AGENTS.md + skill Hard Gate 双重约束；评审看 docs 是否缺 Evidence 段 |
| 本机仓与 NEXUS 索引仓不一致 | MEMORY 记录 Indexed commit；impact 前核对；不一致则警告 |
| 未 push 本地改动 | 明确告知 NEXUS 看不到；以服务端快照为准 |
| 服务端无仓库挂载 | search/source/impact 不可用；试点前必须挂好 volume 并索引 |

---

## 13. 文件变更清单（实现核对）

### multipow

| 路径 | 动作 |
|------|------|
| `cli/assets/templates/AGENTS.md` | 增加 Evidence Gate |
| `cli/assets/templates/MEMORY.md` | 增加 NEXUS 段 |
| `cli/assets/templates/cursor-mcp.json` | 新增 |
| `cli/assets/templates/codex-mcp.toml` | 新增 |
| `cli/src/initWorkspace.ts` | 复制 MCP 配置任务 |
| `cli/src/initWorkspace.test.ts` | 增补用例 |
| `cli/src/workspace/memory.ts` | 可选：注入 env 默认值 |
| `cli/assets/skills/multipow-brainstorm/SKILL.md` | Evidence Pass |
| `cli/assets/skills/multipow-plan/SKILL.md` | development_plan |
| `cli/assets/skills/multipow-implement/SKILL.md` | Impact Gate |
| `cli/assets/skills/multipow-test/SKILL.md` | 回归入口 |
| `cli/assets/skills/multipow-write-case-list/SKILL.md` | 回归入口 |
| `cli/assets/skills/multipow-wiki-*/SKILL.md` | Boundary 说明 |
| `README.md` | 链到集成说明 / 试点步骤 |

### request-RAG

| 路径 | 动作 |
|------|------|
| `docs/multipow-nexus-integration.md` | 本文 |
| `docs/mcp-quickstart.md` | 增加 multipow 小节 + 链接 |
| `docs/nexus-improvement-roadmap.md` | 可选：在 0.6/全组可用处引用本文 |
| `Dockerfile` | 可选修复 0.7 jar 名（若尚未修） |

---

## 14. 一句话

**multipow 负责把人和 Agent 拉进正确流程；NEXUS 负责提供可回查的组内真相（含只读代码索引与影响分析）；本机仓库负责改代码；集成点是工作区 MCP 配置 + skill 证据闸门，而不是合并代码或共用一个工作树。**
