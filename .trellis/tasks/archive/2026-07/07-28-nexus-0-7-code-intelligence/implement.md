# Implementation Plan

## Phase 1 — language-neutral contracts

1. Bump NEXUS to `0.7.0-SNAPSHOT`; add locked Tree-sitter and SQLite JDBC dependencies.
2. Add `CodeLanguage`, scanner/result/symbol/call/diagnostic contracts and extension registry.
3. Add `language` to `CodeChunk` with constructor and Qdrant payload backward compatibility.
4. Add Tree-sitter adapters and fixtures for Java, Go, Python and TypeScript; capability-gate Kotlin.
5. Replace repository walking and Git file reads with `MultiLanguageCodeScanner`.

## Phase 2 — graph persistence and resolution

6. Add validated graph properties and initialize SQLite below the configured data root.
7. Add schema creation, indexes, transaction helpers and project/commit/file replacement operations.
8. Add deterministic symbol IDs and conservative relation resolution tiers.
9. Integrate full indexing so Qdrant chunks and graph snapshots reflect the same parsed commit.
10. Integrate incremental added/modified/deleted/renamed handling without Java-only filters.

## Phase 3 — graph and impact services

11. Implement bounded inbound/outbound traversal with depth, result and cycle limits.
12. Implement symbol impact analysis with certain/inferred/unresolved separation.
13. Implement commit-range impact using `GitDiffService`, target snapshot symbols and file-level degradation.
14. Add regression suggestions from affected entry points and test symbols without claiming execution.

## Phase 4 — REST and MCP

15. Add validated REST request/response contracts and protected endpoints.
16. Add `nexus_code_graph` and `nexus_impact_analysis` as thin MCP adapters.
17. Extend `nexus_search_code` projection with language.
18. Add metrics, safe warnings, response bounding and relative-path validation.

## Phase 5 — compatibility and documentation

19. Update README, changelog, MCP quickstart and roadmap status notes.
20. Document supported languages, confidence meanings, graph rebuild and `NOT_AVAILABLE` behavior.
21. Add migration/startup behavior for an absent or old SQLite graph without touching business data.

## Verification

22. Run focused scanner fixtures and native parser load tests on JDK 21.
23. Run graph transaction, resolution, traversal, incremental delete/rename and rollback tests.
24. Run REST/MCP authorization, validation, truncation, degradation and schema tests.
25. Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify
git diff --check
```

26. Start the service, use MCP Inspector to discover/call both new tools, then run one Codex and one Cursor call.

## Review gates

- No `.java` hard-coded filtering remains in full or incremental indexing.
- No graph edge crosses project or commit scope.
- Dynamic/ambiguous calls never count as certain impact.
- Existing CodeChunk payloads remain readable and existing tool contracts remain additive.
- Graph data contains no absolute paths, source bodies, vectors, credentials or Qdrant internals.
- Every catch logs safely, returns an explicit diagnostic, or rethrows.

## Rollback

Revert the scanner/service/tool wiring and restore `JavaCodeScanner` injection. The additive language payload and unused SQLite database do not require destructive cleanup.
