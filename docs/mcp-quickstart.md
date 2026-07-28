# NEXUS MCP Quickstart

NEXUS 0.6 exposes six evidence-bound tools at the Streamable HTTP endpoint:

```text
http://localhost:8080/mcp
```

The endpoint requires `X-API-Key` when authentication is enabled. Keep the key in an environment variable:

```bash
export NEXUS_API_KEY='replace-with-your-key'
export NEXUS_MCP_URL='http://127.0.0.1:8080/mcp'
```

Do not commit either value.

## Codex

This repository already includes the following project-scoped configuration in `.codex/config.toml`.
Use the same block in `~/.codex/config.toml` only when you want NEXUS available outside this project:

```toml
[mcp_servers.nexus]
url = "http://127.0.0.1:8080/mcp"
env_http_headers = { "X-API-Key" = "NEXUS_API_KEY" }
startup_timeout_sec = 20
tool_timeout_sec = 120
```

Trust the project, restart Codex, then verify:

```bash
codex mcp list
```

The environment variable must be visible to the process that launches Codex.

## Cursor

This repository already includes `.cursor/mcp.json`. Use the same configuration in `~/.cursor/mcp.json` only
when you want NEXUS available globally:

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

Restart Cursor and open Settings → MCP. If the key is not resolved, launch Cursor from a shell where
`NEXUS_API_KEY` is exported and inspect View → Output → MCP Logs. Never replace the environment reference in a
committed project file with a real key.

Cursor asks for one-time approval when it first loads a project MCP server. Approve `nexus` in Settings → MCP,
or run `cursor-agent mcp enable nexus`.

## Claude Code and stdio-only clients

The repository includes a pinned bridge:

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

The launcher delegates to `mcp-remote@0.1.38` in HTTP-only mode. It passes the header as an environment
placeholder so the key is not expanded into the launcher process arguments, and it never writes the key.
Node.js 18 or newer and `npx` are required.

Codex and Cursor should use direct Streamable HTTP. The bridge is only for clients that require stdio.

## Tools

| Tool | Permission | Purpose |
| --- | --- | --- |
| `nexus_search_requirements` | `PUBLIC_READ` | Search version-scoped requirement evidence |
| `nexus_search_code` | `PUBLIC_READ` | Search repository code evidence |
| `nexus_get_source` | `PUBLIC_READ` | Read a bounded repository-relative source excerpt |
| `nexus_development_plan` | `OPERATE` | Generate an evidence-cited development plan |
| `nexus_wiki_page` | `PUBLIC_READ` | Read a published Wiki page |
| `nexus_version_diff` | `PUBLIC_READ` | Compare requirement, code, test, and Wiki knowledge |

Every successful result includes `resolved`, `data`, `evidence`, `quality`, `warnings`, and `truncated`.
Always use the project/version returned in `resolved`; do not assume the requested defaults were selected.

## Docker Compose

Set a key only in the current shell or an ignored `.env`:

```bash
export AUTH_USER_1_KEY='replace-with-a-long-random-key'
docker compose up --build nexus qdrant
```

The image contains no business documents, source repository, vector storage, or credentials. Runtime Wiki data
and the initially empty repository path use named volumes. Mount company repositories through an environment-
specific Compose override and prefer read-only mounts.

## Reverse proxy

Terminate TLS at the company reverse proxy and forward `/mcp` without buffering. Preserve `Accept`,
`Content-Type`, `Mcp-Session-Id`, and `X-API-Key` headers, and allow long-lived HTTP responses. Do not expose an
unencrypted MCP endpoint outside a trusted local network.

## Troubleshooting

- `401 Missing or invalid API key`: the header is absent, blank, or not configured on the server.
- `Insufficient permissions` MCP tool error (REST equivalent: HTTP 403): the user role cannot call the tool or
  the requested project is outside the configured whitelist. The response intentionally does not disclose
  whether another project exists.
- `DEGRADED` with warnings: one dependency failed but useful evidence remains. Inspect warning codes rather than
  treating the result as complete.
- `truncated: true`: narrow the query, reduce the requested scope, or fetch a smaller source range.
- No tools discovered: confirm the endpoint is `/mcp`, the client uses Streamable HTTP, and both
  `spring.ai.mcp.server.enabled` and `app.mcp.enabled` are enabled.
