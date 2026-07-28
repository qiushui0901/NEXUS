# Technical Design

## 1. Architecture

0.6 keeps the existing single Spring Boot application and adds MCP as a thin inbound adapter:

```text
Codex / Cursor ── Streamable HTTP + X-API-Key ──> /mcp
stdio-only client ── local bridge ──────────────> /mcp
                                                     │
                                                     v
                                      MCP authentication + tool facade
                                                     │
                         ┌───────────────────────────┼──────────────────────────┐
                         v                           v                          v
                 RetrievalPipeline       DevelopmentPlanService       Wiki / Version services
                         │
                         v
                existing Qdrant / BGE / LLM degradation contracts
```

The adapter never reads Qdrant, Wiki files, repositories, or `ChatClient` directly. REST, business SSE, and MCP share domain services and public safety rules.

## 2. Spring AI MCP integration

- Add `org.springframework.ai:spring-ai-starter-mcp-server-webmvc`.
- Configure the server as `STREAMABLE` at `/mcp`; do not add the deprecated MCP SSE transport.
- Register one annotated provider bean containing six `@McpTool` methods. Each tool is declared read-only, non-destructive, and idempotent where applicable.
- Inject `McpSyncRequestContext` into HTTP tool calls to obtain the transport context without exposing it in generated tool schemas.
- Keep MCP enabled behind `app.mcp.enabled`; shared/default deployment enables it, while focused tests may disable it.
- Do not change existing `/api/**`, application SSE, `/actuator/**`, or static routes.

The service listens on internal HTTP. TLS and public routing terminate at the company reverse proxy; 0.6 does not manage certificates inside NEXUS.

## 3. Shared authentication

Extract the transport-neutral logic currently embedded in `ProjectAuthInterceptor`:

```java
AuthenticationResult authenticate(String apiKey)
UserContext requireAuthenticated(String apiKey)
void requirePermission(UserContext user, Permission permission)
String requireProjectAccess(UserContext user, String requestedProjectId)
```

- `ApiKeyAuthenticationService` owns auth-enabled behavior, usable-user validation, constant-time API-key comparison, and `UserContext` creation.
- `ProjectAuthorizationService` owns permission checks, default-project resolution, and project whitelist checks.
- `ProjectAuthInterceptor` remains the REST adapter: read header, resolve endpoint permission/project, call the shared services, then attach `UserContext`.
- MCP reads `X-API-Key` from transport headers, authenticates once per call, requires `PUBLIC_READ`, resolves the effective project, and passes only the resolved scope to the facade.
- Disabled authentication retains the existing explicit local-profile `defaultAdmin` behavior and warning. Enabled authentication never falls back.
- Missing/invalid credentials map to the same safe unauthenticated semantics; unauthorized projects map to forbidden without revealing project existence.

MCP protocol errors carry stable public codes/details. Raw keys, request text, internal exception messages, and project registry contents are never returned or logged.

## 4. Tool contracts

All tools return:

```json
{
  "resolved": {
    "projectId": "project",
    "version": "optional",
    "documentId": "optional"
  },
  "data": {},
  "evidence": [],
  "quality": {},
  "warnings": [],
  "truncated": false
}
```

The Java representation is a generic `McpToolResponse<T>` with concrete records for scope, evidence, quality, and bounded data. It reuses `RagOutcomeStatus`, `RagWarning`, citation quality, and evidence registry semantics rather than creating parallel degradation concepts.

### `nexus_search_requirements`

Input: `query`, optional `projectId`, `documentId`, `version`, `limit`.

Call `RetrievalPipeline.execute` with `REQUIREMENT_REVIEW`. Return requirement hits, resolved scope, stage warnings, and request-scoped requirement evidence. Do not return vectors or storage identifiers.

### `nexus_search_code`

Input: `query`, optional `projectId`, `limit`.

Call `CodeKnowledgeService.search`. Convert chunks to bounded DTOs with repository-relative paths, symbols, line ranges, excerpts, and stable `code:*` evidence IDs.

### `nexus_get_source`

Input: optional `projectId`, required repository-relative `filePath`, optional `startLine` and `endLine`.

Call `CodeKnowledgeService.source` after project authorization. Preserve its root-escape protection, additionally enforce a maximum line count and response size, and never expose the configured repository root.

### `nexus_development_plan`

Input: `query`, optional `projectId`, `documentId`, `version`, `limit`.

Call `DevelopmentPlanService.plan`. Preserve resolved scope, validated citations, evidence coverage, conflict report, retrieval warnings, and diagnostics after public-safe projection.

### `nexus_wiki_page`

Input: optional `projectId`, required `version` and `featureId`.

Validate project/version/feature identifiers through existing policies, then call `WikiRepository.getPage`. Return the published page with bounded evidence and no backing file paths.

### `nexus_version_diff`

Input: optional `projectId`, required `fromVersion` and `toVersion`.

Call `VersionComparisonService.compare`. Preserve the independent requirement/code/test/Wiki availability states and warnings; `NOT_AVAILABLE` must never become an empty successful diff.

## 5. Bounding, redaction, and error mapping

Introduce one `McpResponsePolicy` used by every tool:

- clamp requested list limits to `1..20`;
- cap source reads at 200 lines;
- cap individual excerpts at 2,000 characters;
- cap evidence lists at 40 entries;
- cap serialized tool results at 120,000 characters;
- set `truncated=true` whenever any cap is applied;
- allow only normalized repository-relative code paths;
- remove absolute paths, vectors, Qdrant point IDs, provider URLs, credentials, and raw exception details.

Validation/authentication errors fail the tool call. Dependency failures follow existing RAG rules: return `DEGRADED` with stable warnings when useful evidence remains; fail only when no core evidence is usable.

## 6. Observability

Wrap each tool call in `McpToolInvocationService`:

- timer tags: tool, status, warning code;
- counter tags: tool, actor class/role, status;
- structured log fields: tool, actor, project, version, duration, status, warning codes;
- never log query text, API key, evidence text, full paths, or exception messages in public recent-event data.

Tag values are from bounded enums or normalized configured identifiers to avoid unbounded metric cardinality.

## 7. stdio bridge and client compatibility

Codex and Cursor use Streamable HTTP directly and are the release gates.

For stdio-only clients, provide a small launcher under `scripts/` that delegates to a pinned, documented MCP remote bridge. URL and API key come only from environment variables. The launcher validates required variables, avoids shell tracing, never writes configuration, and replaces itself with the bridge process. The bridge is compatibility tooling, not a second NEXUS server or a release-gate transport.

`docs/mcp-quickstart.md` contains:

- Codex Streamable HTTP configuration;
- Cursor Streamable HTTP configuration;
- Claude Code configuration example;
- stdio bridge command;
- API-key environment handling;
- 401/403/degraded/truncated troubleshooting;
- reverse-proxy requirements for TLS, forwarded headers, and streaming.

## 8. Container delivery

- Add a multi-stage `Dockerfile`: Maven/JDK 21 build stage and JRE 21 runtime stage, running as a non-root user.
- Add `.dockerignore` that excludes Git metadata, `data/`, repositories, logs, archives, credentials, local environment files, Qdrant storage, and build output.
- Extend `compose.yml` with the NEXUS service, health check, dependency wiring, environment placeholders, and explicit read-only repository/runtime volumes where practical.
- The image contains only the application and startup necessities. No business document, indexed repository, vector storage, or key is copied.
- Single-instance file-backed Wiki/version storage remains explicit. Database, shared locking, SSO, rate limiting, and multi-replica deployment remain 0.9 scope.

## 9. Testing strategy

- Unit tests for shared auth extraction, constant-time credential matching behavior, permissions, defaults, and project denial.
- One contract test per MCP tool for schema, resolved scope, evidence IDs, bounds, redaction, warnings, and failure mapping.
- MockMvc/integration tests for MCP initialize, tool discovery, missing/invalid key, forbidden project, and successful calls.
- Regression tests proving existing REST auth, controllers, development-plan SSE, Wiki, and version comparison remain compatible.
- Container smoke test verifies image build, Compose startup, `/actuator/health`, `/mcp` initialization, and absence of embedded local data/secrets.
- MCP Inspector verifies protocol initialization, tool list, input schemas, and representative calls.
- Manual release gate configures both Codex and Cursor against the same Streamable HTTP endpoint and executes at least one evidence-bearing tool call in each.

## 10. Rollback

MCP is additive and can be disabled with `app.mcp.enabled=false`. Reverting the MCP provider, dependency, scripts, and container files leaves REST/SSE/domain services intact. The auth extraction is kept behavior-compatible and covered by interceptor regression tests, so it can remain even if MCP is disabled.
