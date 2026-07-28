# Technical Design — NEXUS 0.7 Code Intelligence

## Architecture

```text
repository / git show
        |
        v
LanguageRegistry -- extension -> TreeSitterLanguageAdapter
        |
        +--> CodeChunk(language, symbol...) --> CodeQdrantStore
        |
        +--> ParsedCodeFile(symbols, calls, diagnostics)
                                      |
                                      v
                         SymbolGraphResolver
                                      |
                                      v
                         SQLiteSymbolGraphStore
                                      |
                    +-----------------+------------------+
                    v                                    v
             CodeGraphService                    ImpactAnalysisService
                    |                                    |
             REST + MCP tool                      REST + MCP tool
```

Qdrant remains the semantic-search store. SQLite is a separate, bounded graph store and never stores vectors or source bodies.

## Scanner contracts

```java
interface CodeScanner {
    ScanResult scan(RagProperties.Code config) throws IOException;
    ScanResult scanFiles(RagProperties.Code config, String commitSha, List<String> paths) throws IOException;
    boolean supports(String repositoryRelativePath);
}

record ScanResult(
    String projectId,
    String commitSha,
    int files,
    List<CodeChunk> chunks,
    List<CodeSymbol> symbols,
    List<CodeCall> calls,
    List<CodeScanDiagnostic> diagnostics
) {}
```

`LanguageRegistry` owns extension mapping and adapters. `MultiLanguageCodeScanner` owns repository walking, size/binary/path policy, Git reads, per-file failure isolation, and aggregate results. Language adapters only parse a bounded UTF-8 source string.

Supported adapters:

- Java: Tree-sitter Java
- Go: Tree-sitter Go
- Python: Tree-sitter Python
- TypeScript/TSX: Tree-sitter TypeScript/TSX
- Kotlin: registered capability state; enabled only if its parser passes the same fixture contract

Tree-sitter NG core `0.26.3` is selected because it supports Java 21 and is the
declared runtime of the current Java, Go and Python grammar packages. Grammar
artifacts are locked independently (`java 0.23.5`, `go 0.25.0`,
`python 0.25.0`, `typescript 0.23.2`, `kotlin 0.3.8.1`) because their release
numbers do not move in lockstep. TypeScript and Kotlin are verified against the
locked core by fixture tests. If Kotlin native loading or ABI compatibility
proves unreliable, Kotlin remains `DISABLED` with an explicit diagnostic while
Java/Go/Python/TypeScript ship.

## Stable identities and compatibility

`CodeChunk` adds `language`; a ten-argument compatibility constructor derives language from `filePath` for existing tests and callers. Qdrant reads missing `language` as `UNKNOWN`.

Symbol identity input:

```text
projectId + commitSha + language + filePath + qualifiedName + symbolKind + startLine
```

Relations are scoped to `projectId + commitSha`. No relation can resolve across projects or commits.

## AST extraction

Adapters traverse named Tree-sitter nodes rather than regex-matching source. Each descriptor supplies:

- definition node kinds;
- name child field/fallback;
- callable body node kinds;
- call node kinds and target extraction;
- entry/test heuristics;
- qualified-name composition rules.

Source text is sliced by Tree-sitter byte offsets using UTF-8-safe conversion. Lines come from Tree-sitter points. Parse trees with errors may still yield symbols, but diagnostics make partial parsing visible.

## Resolution rules

1. Unique fully qualified match: `EXACT`.
2. Unique same-file simple-name match: `SAME_FILE`.
3. Unique project/commit simple-name match: `HEURISTIC`.
4. Zero or multiple matches: `UNRESOLVED`.

Only `EXACT` and `SAME_FILE` contribute to deterministic impact. `HEURISTIC` is returned separately as inferred impact. `UNRESOLVED` remains visible and cannot be promoted.

## SQLite schema

Database: `${CODE_GRAPH_ROOT_PATH:data/code-graph}/code-graph.db`.

```sql
code_graph_snapshot(project_id, commit_sha, indexed_at, languages, primary key(project_id, commit_sha))
code_symbol(id, project_id, commit_sha, language, kind, qualified_name, simple_name,
            file_path, start_line, end_line, entry_point, test_symbol)
code_relation(id, project_id, commit_sha, caller_symbol_id, callee_symbol_id,
              target_name, file_path, line, resolution, evidence)
```

Indexes cover project/commit, qualified/simple name, caller, callee, and file path. Writes use JDBC transactions. Full replacement stages rows in one transaction; incremental replacement deletes affected file rows and relations before inserting and resolving the new snapshot.

## Impact contracts

```java
ImpactAnalysisResponse analyzeSymbol(
    String projectId, String symbol, Direction direction, int depth, int limit);

ImpactAnalysisResponse analyzeCommits(
    String projectId, String fromCommit, String toCommit, int depth, int limit);
```

The response separates:

- `changedSymbols`
- `certainImpact`
- `inferredImpact`
- `unresolvedCalls`
- `regressionSuggestions`
- `availability`
- `warnings`

Every item carries repository-relative location, relation reason, confidence, and traversal depth.

Commit analysis uses validated SHAs through `GitDiffService`, maps changed files to symbols in the target snapshot, and then traverses inbound callers. If the target snapshot is absent, it returns file-level changes with graph availability `NOT_AVAILABLE`.

## REST and MCP

REST:

```http
POST /api/code/graph/symbols
POST /api/code/impact
```

Both require `PUBLIC_READ` and project access.

MCP:

- `nexus_code_graph(projectId, symbol, direction?, depth?, limit?)`
- `nexus_impact_analysis(projectId, symbol?, fromCommit?, toCommit?, depth?, limit?)`

Exactly one impact selector is valid: `symbol`, or both commits. Responses reuse `McpToolInvocationService` and `McpResponsePolicy`.

## Rollout and rollback

- Platform version becomes `0.7.0-SNAPSHOT`.
- Existing Qdrant collection stays readable; adding `language` is payload-additive.
- SQLite graph starts empty and is rebuilt by the next code index.
- MCP tools return `NOT_AVAILABLE` until a graph snapshot exists.
- Rollback disables new tools/scanner and restores `JavaCodeScanner`; the additive Qdrant field and SQLite file can remain unused.

## Risks

- Native parser portability: locked dependencies plus JDK 21/macOS/Linux smoke tests.
- False call resolution: conservative resolution tiers and visible unresolved edges.
- Large graph traversal: depth/result caps and indexed SQL queries.
- Incremental consistency: one transaction per project snapshot update and rollback tests.
- Kotlin grammar compatibility: capability-gated rather than silently falling back to regex.
