# NEXUS MCP 快速入门

安装、索引、Wiki 与 REST 用法见 [用户指南](./user-guide.md)。产品概览见 [根目录 README](../README.md)。

NEXUS 0.8 在 Streamable HTTP 端点上提供十个带证据约束的工具和三个工作流 Prompt：

```text
http://localhost:8080/mcp
```

启用认证时，请求需携带 `X-API-Key`。请把密钥放在环境变量中：

```bash
export NEXUS_API_KEY='replace-with-your-key'
export NEXUS_MCP_URL='http://127.0.0.1:8080/mcp'
```

不要将上述值提交到 Git。

## Codex

本仓库已在 `.codex/config.toml` 提供项目级配置。仅当希望在本项目之外全局使用 NEXUS 时，再写入 `~/.codex/config.toml`：

```toml
[mcp_servers.nexus]
url = "http://127.0.0.1:8080/mcp"
env_http_headers = { "X-API-Key" = "NEXUS_API_KEY" }
startup_timeout_sec = 20
tool_timeout_sec = 120
```

信任项目、重启 Codex 后验证：

```bash
codex mcp list
```

启动 Codex 的进程必须能读到上述环境变量。

## Cursor

本仓库已包含 `.cursor/mcp.json`。仅当需要全局可用时，再配置 `~/.cursor/mcp.json`：

```json
{
  "mcpServers": {
    "nexus": {
      "url": "http://127.0.0.1:8080/mcp",
      "headers": {
        "X-API-Key": "${env:NEXUS_API_KEY}"
      }
    }
  }
}
```

重启 Cursor，打开 Settings → MCP。若 Key 未解析，请从已导出 `NEXUS_API_KEY` 的 shell 启动 Cursor，并查看 View → Output → MCP Logs。**不要**在已提交的项目文件里把环境变量引用换成真实密钥。

首次加载项目 MCP 时，Cursor 会请求一次性批准。在 Settings → MCP 中批准 `nexus`，或执行 `cursor-agent mcp enable nexus`。

## Claude Code 与仅支持 stdio 的客户端

仓库提供固定版本的桥接脚本：

```json
{
  "mcpServers": {
    "nexus": {
      "command": "/absolute/path/to/request-RAG/scripts/nexus-mcp-stdio.sh",
      "env": {
        "NEXUS_MCP_URL": "https://nexus.internal.example/mcp",
        "NEXUS_API_KEY": "${NEXUS_API_KEY}"
      }
    }
  }
}
```

启动器以 HTTP-only 模式委托 `mcp-remote@0.1.38`，通过环境占位传递请求头，避免把 Key 展开进进程参数，也不会落盘写入密钥。需要 Node.js 18+ 与 `npx`。

Codex 与 Cursor 应直接使用 Streamable HTTP。桥接仅给必须走 stdio 的客户端。

## 工具一览

| 工具 | 权限 | 用途 |
| --- | --- | --- |
| `nexus_search_requirements` | `PUBLIC_READ` | 按版本检索需求证据 |
| `nexus_search_code` | `PUBLIC_READ` | 检索仓库代码证据 |
| `nexus_get_source` | `PUBLIC_READ` | 读取仓库相对路径的受控源码摘录 |
| `nexus_development_plan` | `OPERATE` | 生成带证据引用的开发方案 |
| `nexus_wiki_page` | `PUBLIC_READ` | 读取已发布 Wiki 页 |
| `nexus_version_diff` | `PUBLIC_READ` | 对比需求、代码、测试与 Wiki 知识 |
| `nexus_code_graph` | `PUBLIC_READ` | 遍历入向 / 出向静态符号调用 |
| `nexus_impact_analysis` | `PUBLIC_READ` | 按符号或 commit 范围做分级影响分析 |
| `nexus_review_doubts` | `OPERATE` | 生成按版本隔离的需求存疑列表 |
| `nexus_conflict_check` | `OPERATE` | 检查需求、代码、测试与 Wiki 声明冲突 |

Prompt 模板覆盖实现需求、评审需求和评估改动影响，可由支持 MCP Prompt 的客户端直接发现。

已发布 Wiki 页也可通过需鉴权的 Resource Template 访问：  
`nexus://wiki/{projectId}/{version}/{featureId}`。

每次成功结果包含 `resolved`、`data`、`evidence`、`quality`、`warnings`、`truncated`。请以 `resolved` 中的项目 / 版本为准，不要假定请求默认值已被采用。  
图相关工具在该项目完成 0.7 代码索引前返回 `NOT_AVAILABLE`。`EXACT` 与 `SAME_FILE` 计为确定影响，`HEURISTIC` 单独列出，动态或歧义调用保持为可见的 `UNRESOLVED`。

## Docker Compose

只在当前 shell 或已被忽略的 `.env` 中设置密钥：

```bash
export AUTH_USER_1_KEY='replace-with-a-long-random-key'
docker compose up --build nexus qdrant
```

镜像不包含业务文档、源码仓库、向量存储或凭据。运行时 Wiki 与初始为空的仓库路径使用 named volume。请通过环境专用 Compose override 挂载公司仓库，并优先只读挂载。

## 反向代理

在公司反代终止 TLS，并将 `/mcp` 无缓冲转发。保留 `Accept`、`Content-Type`、`Mcp-Session-Id`、`X-API-Key`，允许长连接 HTTP 响应。不要在非受信网络暴露未加密的 MCP 端点。

## multipow 工作区

若要用 Agent 工作流脚手架自动接入本 MCP 端点，见 [multipow × NEXUS 集成方案](./multipow-nexus-integration.md)。该文档覆盖 `multipow init` 模板、skill 证据闸门与试点手册。不要合并 multipow 与 NEXUS 仓库；仅通过 MCP 配置与 skills 连接。

## 排障

- `401 Missing or invalid API key`：缺少、为空或服务端未配置 Header。
- `Insufficient permissions`（REST 对应 HTTP 403）：角色无权调用该工具，或项目不在白名单内。响应故意不透露其他项目是否存在。
- 带 `warnings` 的 `DEGRADED`：某一依赖失败但仍有可用证据。请查看 warning 码，勿当作完整结果。
- `truncated: true`：缩小查询、降低范围，或减小源码行区间。
- 发现不了工具：确认端点为 `/mcp`、客户端使用 Streamable HTTP，且 `spring.ai.mcp.server.enabled` 与 `app.mcp.enabled` 均已开启。
