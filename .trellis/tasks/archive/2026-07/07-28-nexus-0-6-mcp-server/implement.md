# Implementation Plan

## Phase 1 — contracts and shared security

1. Bump the project version to `0.6.0-SNAPSHOT` and add the Spring AI WebMVC MCP starter.
2. Add MCP configuration properties with bounded response defaults and explicit enablement.
3. Extract API-key authentication and project authorization from `ProjectAuthInterceptor` into transport-neutral services.
4. Refactor the interceptor and `ProjectAccessGuard` to use the shared services without changing REST status codes, permissions, default-project behavior, or request attributes.
5. Add focused auth regression tests before adding MCP behavior.

## Phase 2 — MCP facade and response safety

6. Add the shared `McpToolResponse`, resolved-scope, evidence, and safe-quality DTOs.
7. Implement `McpResponsePolicy` for list/line/character caps, repository-relative paths, evidence projection, and truncation.
8. Implement an MCP invocation wrapper for auth, project resolution, safe error mapping, timing, metrics, and redacted structured logs.
9. Register the six read-only annotated tools:
   - `nexus_search_requirements`
   - `nexus_search_code`
   - `nexus_get_source`
   - `nexus_development_plan`
   - `nexus_wiki_page`
   - `nexus_version_diff`
10. Ensure each provider delegates only to the existing domain services and uses existing `RagOutcome`, evidence registry, citation quality, and version availability semantics.

## Phase 3 — protocol and regression tests

11. Add tool-level contract tests for input validation, effective project/version/document, authorization, warning projection, evidence filtering, redaction, and truncation.
12. Add MCP HTTP integration tests for initialize, list tools, call tool, and 401 behavior; cover
    permission/project denial at the tool contract layer because MCP tool failures remain protocol responses.
13. Run focused REST/SSE tests and fix only compatibility regressions introduced by shared-auth extraction.
14. Run the full JDK 21 verification gate.

## Phase 4 — delivery and client integration

15. Add the pinned stdio bridge launcher and test missing-variable, key-redaction, and argument behavior.
16. Add the multi-stage non-root `Dockerfile`, strict `.dockerignore`, and NEXUS service in `compose.yml`.
17. Add `docs/mcp-quickstart.md` with Codex, Cursor, Claude Code, direct HTTP, stdio bridge, reverse-proxy, authentication, and troubleshooting examples.
18. Build the image and run a clean Compose smoke test covering health and MCP initialization.
19. Run MCP Inspector for initialization, six-tool discovery, schemas, and representative tool calls.
20. Configure Codex and Cursor against the Streamable HTTP endpoint and execute at least one real evidence-bearing call in each.

## Final quality gate

21. Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify
git diff --check
docker compose config
```

22. Inspect the Git diff and built image inputs to confirm no API key, business document, local repository, vector data, Qdrant storage, absolute path, runtime log, PID, archive, or local environment file is included.
23. Record MCP Inspector, Codex, Cursor, and container smoke evidence in the task notes before release commit.

## Review gates

- Every MCP result identifies the effective project and relevant version.
- Every generated claim retains only request-scoped, validated evidence IDs.
- Missing dependency data remains visibly `DEGRADED` or `NOT_AVAILABLE`.
- MCP cannot bypass project authorization or read outside a configured repository root.
- REST/SSE behavior is unchanged after auth extraction.
- Codex and Cursor both pass real-client Streamable HTTP smoke tests.
- The container image is secret-free, business-data-free, and runs as non-root.

## Rollback

Disable MCP through configuration or revert the additive provider/dependency/container changes. Shared auth services may remain because their pre-MCP behavior is locked by regression tests. No persisted data migration is introduced in 0.6.

## Verification Evidence

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify`: passed, 172 tests, 0 failures/errors/skips.
- MCP HTTP integration: missing key returned 401; initialize, six-tool discovery, and `nexus_get_source` call passed.
- MCP Inspector: `tools/list` returned all six schemas; `tools/call` returned `pom.xml` lines 1–2 with the complete bounded envelope and evidence.
- Codex CLI 0.145.0: project config parsed `env_http_headers`; a real `nexus_get_source` call returned `MCP_OK pom.xml`.
- Cursor Agent: project config loaded; a real `nexus_get_source` call returned `pom.xml`.
- Source boundary regression: a normal repository file is readable and a symlink escaping the real repository root is rejected.
- `compose.yml` parsed successfully with Ruby YAML; stdio bridge passed `sh -n`; `git diff --check` passed.
- Container build/clean Compose runtime smoke: not run because Docker is not installed on this development machine. Dockerfile, `.dockerignore`, Compose syntax, required-secret interpolation, non-root runtime, and excluded build inputs were reviewed statically.
