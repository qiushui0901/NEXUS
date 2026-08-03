# Retrieval and Version Knowledge Contracts

> Executable backend contracts for unified evidence retrieval, reviewable version-knowledge drafts, version manifests, and multi-source comparison.

## 1. Scope / Trigger

Apply this specification when changing any of the following:

- `com.example.requirementrag.retrieval.pipeline.*`
- `DevelopmentPlanService` or `DevelopmentPlanStreamService` retrieval orchestration
- `VersionKnowledgeBuildPipeline` or `KnowledgeBuildController`
- `com.example.requirementrag.versioning.*` or `VersionController`
- `com.example.requirementrag.conflict.*`, `KnowledgeConflictController`, or conflict reporting in RAG responses
- `tools/build-requirement-snapshots.py` or `data/requirement-snapshots/**`
- `GitDiffService` or Git-based incremental indexing
- `WikiRepository` version-index access
- `com.example.requirementrag.mcp.*`, `/mcp`, or agent client configuration
- `app.rag.wiki.*` or `app.rag.versioning.*` storage configuration
- Wiki draft evidence, version comparison, review, or publication behavior

The NEXUS platform version (for example `0.3.0-SNAPSHOT`) and a product requirement version (for example `2026.07`) are separate identifiers and must never be inferred from one another.

## 2. Signatures and APIs

### Unified retrieval

```java
RagOutcome<RetrievalBundle> RetrievalPipeline.execute(RetrievalRequest request)
```

Supported profiles:

- `DEVELOPMENT_PLAN`: requirement and code evidence
- `REQUIREMENT_REVIEW`: requirement evidence only
- `WIKI_BUILD`: requirement and code evidence for draft enrichment

### Version knowledge build API

```http
POST /api/knowledge/build
Content-Type: application/json
```

```json
{
  "projectId": "example-project",
  "version": "2026.07",
  "baseVersion": "5.0",
  "documentId": "requirements",
  "baseCodeCommit": "optional commit",
  "codeCommit": "optional commit"
}
```

The endpoint requires `Permission.WRITE` and project access.

### Knowledge draft lifecycle APIs

```http
GET  /api/knowledge/drafts?projectId=...&version=...
GET  /api/knowledge/drafts/{buildId}?projectId=...&version=...
POST /api/knowledge/drafts/{buildId}/transition?projectId=...&version=...
POST /api/knowledge/drafts/{buildId}/publish?projectId=...&version=...
POST /api/knowledge/drafts/{buildId}/rollback?projectId=...&version=...
```

All endpoints validate the project and enforce project access. Read operations require
`PUBLIC_READ`; transitions, publication, and rollback require `WRITE`. The actor is
always taken from the authenticated `UserContext`, never from request data.

### Version manifest and comparison APIs

```http
PUT /api/versions/manifests
GET /api/versions/manifests?projectId=...
GET /api/versions/manifests/{version}?projectId=...
GET /api/versions/compare?projectId=...&fromVersion=...&toVersion=...
```

### Version comparison browser

```http
GET /versions                  # redirects to /versions.html
GET /wiki?projectId=...&version=...&featureId=...
```

### Knowledge conflict analysis API

```http
POST /api/knowledge/conflicts/analyze
Content-Type: application/json
```

The endpoint requires `Permission.OPERATE`, validates the project through `ProjectRegistry`, and enforces project access through `ProjectAccessGuard`. The request contains a target project/version plus structured claims. The service must not infer semantic equivalence between unrelated free-text passages.

The native browser page consumes `/api/wiki/projects`, `/api/wiki/versions`,
and `/api/versions/compare`. It must not introduce a second frontend build chain.

Saving requires `Permission.WRITE`. Listing, reading, and comparing require `Permission.PUBLIC_READ`. Every endpoint must validate the project through `ProjectRegistry` and enforce project access through `ProjectAccessGuard`.

## 3. Contracts

### Retrieval outcome

- Use the existing `RagOutcome`, `RagOutcomeStatus`, `RagWarning`, and `RagStageDiagnostic` types. Do not create a parallel status model.
- Route through `QueryRouter` when no explicit project is supplied.
- Requirement evidence is deduplicated by `parentId`, falling back to `filename + parentOrder`.
- Code evidence is deduplicated by chunk ID, falling back to `filePath + symbolName + startLine`.
- Apply the configured/default limit after deduplication.
- `DevelopmentPlanService` and `DevelopmentPlanStreamService` must delegate retrieval orchestration to `RetrievalPipeline`; they only own generation and output formatting.

### Generated-answer evidence citations

- Build one request-scoped `EvidenceRegistry` from the returned `RetrievalBundle`; only registry IDs are valid citations for that generation request.
- Requirement and code IDs use separate namespaces. IDs must be deterministic for stable chunk identities, collision-safe, bounded, and must never expose credentials or local absolute paths.
- Prompt evidence blocks must carry their registry ID. Model-provided IDs are untrusted input and must be trimmed, deduplicated, bounded, and checked against the registry before they reach an API response.
- A claim with only valid IDs is `SUPPORTED`; a claim with both accepted and rejected IDs is `PARTIAL`; a claim with no accepted ID is `UNSUPPORTED`.
- Missing or invalid citations produce stable `RagWarning` codes when retrieval evidence existed. A normal zero-hit response remains `NO_RESULTS` and must not fabricate a missing-citation failure.
- Aggregate quality reports total, supported, partial, and unsupported claims plus a bounded coverage rate. Partial support contributes half weight; quality status is `VERIFIED`, `REVIEW_REQUIRED`, or `INSUFFICIENT_EVIDENCE`.
- The legacy synchronous development-plan fields remain compatible and citation metadata is additive. SSE plan events carry validated `evidenceIds` and `supportStatus`; terminal payloads carry the evidence registry and citation quality.
- Browser rendering must use text binding rather than raw HTML. Requirement citations show only bounded excerpts; code citations may call the existing protected source endpoint.

### Requirement parent-chunk comparison

- `RequirementChunkDiff` is the shared comparison algorithm for formal version comparison and version-knowledge draft construction. Do not duplicate the matching algorithm.
- Read requirement-version payloads through `QdrantHybridStore.scrollVersion`; never request, serialize, or persist vectors.
- Match parent chunks by stable `parentId` first. If it is absent, fall back to normalized `filename + parentOrder`.
- Determine content changes by stored `contentHash`; if absent, compute a deterministic SHA-256 hash from normalized parent text.
- Emit only `ADDED`, `MODIFIED`, and `REMOVED` changes with bounded excerpts.
- Different functions must not be merged only because their names are similar. Generated `featureId` values must be stable and unique within a draft.

### Draft build and storage

- Write only below `${WIKI_DRAFT_PATH:data/wiki-drafts}/<project>/<version>/<buildId>/`.
- Generate `build.json` and `wiki-source.json` through a staging directory and atomically publish the completed draft directory.
- Do not write to `data/wiki-sources` or `data/wiki` from the build pipeline.
- Generated pages are `DRAFT`; feature review status is `PENDING_REVIEW`.
- Missing code or tests must be counted and exposed; absence must not be presented as verified evidence.
- Test suggestions in a draft are proposals, not test execution results.

### Draft lifecycle, publication, and rollback

- A completed build initializes persisted metadata in `DRAFT` with actor, timestamp, revision, and history.
- Legal states are `DRAFT`, `IN_REVIEW`, `APPROVED`, `REJECTED`, `PUBLISHED`, `SPLIT`, and `MERGED`; one centralized transition table owns all legal transitions.
- Every transition persists actor, timestamp, from/to status, and optional bounded comment through temp-file plus atomic replacement.
- Only `APPROVED` drafts may publish. Publication first snapshots the prior formal source, atomically installs the reviewed source, invokes the existing atomic Wiki generator, and marks metadata `PUBLISHED` only after generation succeeds.
- Rollback restores a retained publication snapshot and regenerates the Wiki before recording the rollback event. A failure must leave the previously published source and Wiki readable.
- File-backed lifecycle locking is process-local and suitable only for the single-instance 0.5 deployment. Multi-instance deployment requires a transactional shared store in a later release.

### Authentication and external dependency safety

- Shared/default configuration enables API-key authentication. Only the explicit local profile disables it.
- Startup fails when authentication is enabled but no usable user/API-key entry exists. Explicitly disabled authentication logs a prominent security warning.
- Qdrant and BGE `RestClient` instances each use a request factory with a 2-second connect timeout and 5-second read timeout.
- A caught dependency, parsing, fallback, or cleanup exception must be logged without request bodies, credentials, absolute paths, or other sensitive data. User-visible degradation continues through stable `RagWarning` or existing public error contracts; never silently convert a failure to an empty successful result.

### Requirement snapshot persistence

- Store one reviewable snapshot per project and requirement baseline below `${REQUIREMENT_SNAPSHOT_ROOT_PATH:data/requirement-snapshots}/<project>/<requirementVersion>.json`.
- A snapshot contains `projectId`, `documentId`, `requirementVersion`, optional `baseRequirementVersion`, reviewed business-version `aliases`, `generatedAt`, source facts, and ordered requirement entries.
- Each source fact records only a repository-relative source path, source location, SHA-256, and byte size. Each entry records a stable `entryId`, filename, parent order, text, content hash, and optional operation.
- Requirement snapshots are incremental events, not independent complete lists. Missing operation means `UPSERT`; only an explicit structured `REMOVE` operation may delete a historical entry. Never infer removal from entry absence or words in requirement text.
- Materialize a complete requirement state by recursively replaying `baseRequirementVersion`: inherit active baseline entries, overwrite matching stable IDs with `UPSERT`, and remove matching historical IDs with `REMOVE`.
- Reject missing baselines, cross-document baseline references, inheritance cycles, duplicate entry IDs, and explicit removals that do not resolve to an active historical entry.
- Snapshots are comparison facts, not retrieval indexes. They must never contain vectors, embeddings, Qdrant points, storage/snapshot/WAL data, credentials, or the original large archive.
- Generated requirement snapshots may contain private business text and therefore remain local runtime artifacts under Git ignore. Commit only the generator, schema, configuration, and synthetic test fixtures without real business content.
- `RequirementSnapshotRepository.findForBusinessVersion` may map a business version to a requirement baseline only through an explicit alias. Do not infer missing mappings from numeric proximity.
- `VersionManifestResolver` merges published Wiki indexes and formal manifests. Formal manifests override synthesized manifests; if a formal manifest lacks requirement references, an explicit snapshot alias may fill only those missing references.
- Synthetic manifests infer `baseVersion` only when the target Wiki index `baseCodeCommit` exactly matches another published index's `codeCommit`.
- `RequirementVersionDiffService` compares materialized snapshot states when both referenced snapshots exist. It falls back to payload-only Qdrant reads when either snapshot is missing, but the compatibility fallback must not infer removal from an absent entry.
- The generator must preserve `generatedAt` when regenerated content is unchanged, so a no-op run produces byte-identical JSON.

### Version manifest persistence

- Store one manifest per project and business version below `${VERSION_MANIFEST_ROOT_PATH:data/version-manifests}/<project>/<version>.json`.
- A manifest may reference requirement document/version, base and target code commit, a real test snapshot, Wiki version/build ID, lifecycle status, timestamps, and notes.
- On update, preserve `createdAt`, refresh `updatedAt`, and normalize optional values.
- Reject unsafe identifiers before path resolution. All resolved paths must remain below the configured manifest root.
- Validate optional Git commits as 7–64 hexadecimal characters; never pass unvalidated user text as an arbitrary Git or shell argument.
- Validate non-negative test counts, reject totals where passed + failed + skipped exceeds total, and reject blank or duplicate test `caseId` values.
- Write through a temporary file in the destination directory and atomically replace the target when supported.
- Manifest JSON must remain small and reviewable; it must not copy Qdrant payload collections, vectors, snapshots, storage internals, or credentials.

### Multi-source version comparison

- `VersionComparisonService` resolves both versions through `VersionManifestResolver` and returns independent requirement, code, test, and Wiki sections plus safe warnings. When formal manifests are absent, published Wiki indexes remain the version-selection source; an explicit requirement snapshot alias may make requirement comparison available, while tests remain `NOT_AVAILABLE` without real `TestSnapshot` values.
- Each source section must report `AVAILABLE` or `NOT_AVAILABLE`; missing data must not be represented as an empty successful diff.
- Requirement comparison uses the shared parent-chunk comparison over materialized cumulative snapshot states. Payload-only Qdrant reads are a compatibility fallback and may report additions or modifications, but never removal inferred only from absence.
- Code comparison uses `GitDiffService` and reports file-level added, modified, deleted, renamed changes and category counts. Do not describe this as AST or symbol-level analysis.
- `IncrementalCodeIndexService` and version comparison must reuse the same `GitDiffService` execution and parsing logic.
- Test comparison uses only real `TestSnapshot` values stored in manifests. Compare aggregate counts, run status, case additions/removals, and case status changes. Never infer execution results from suggested test points.
- Wiki comparison uses `WikiRepository.findIndex(projectId, version)` and compares page additions/removals, review status, summary, and evidence count.
- A missing non-critical source returns `NOT_AVAILABLE` and a warning while other sources continue. Invalid identifiers remain hard failures; absent manifests may fall back to published Wiki indexes for the browser comparison.
- Public warnings must use stable text and must not expose dependency exception messages, repository absolute paths, internal URLs, commands, or credentials.

### Version comparison browser contract

- The page selects a target Wiki version first, uses a matching `baseCodeCommit` to identify the base Wiki version when possible, and otherwise selects the newest different Wiki version. A standalone manifest may enrich the comparison but is not required for the timeline.
- The page must disable comparison when fewer than two Wiki versions exist or when the selected versions are equal. It must show a clear empty state rather than calling the compare endpoint.
- Requirement, code, test, and Wiki sections render independently. `NOT_AVAILABLE` is a visible degraded state and must not be rendered as a successful zero-change result.
- Missing test snapshots must display that there is no real execution snapshot. Suggested test points and page text must never be presented as executed test evidence.
- Wiki changes link to `/wiki?projectId=...&version=...&featureId=...`; the Wiki page consumes those parameters and falls back to its existing default selection when a target is absent.
- API-derived text must pass through one HTML escaping function before insertion into `innerHTML`; API keys are read from `localStorage.nexusApiKey` and sent as `X-API-Key` when present.
- Warnings and UI errors use safe public messages only. The page must not show dependency exception text, absolute paths, secrets, or vector data.

### Knowledge conflict contract

- `KnowledgeClaim` is the only comparable fact unit. It contains a stable `factKey`, bounded `value`, project, business version, source type, authority, evidence, and optional supporting evidence IDs.
- Requirement, code, and test claims are `PRIMARY`. Wiki claims are always normalized to `DERIVED`; a caller cannot promote Wiki authority.
- Only claims with the same normalized `factKey` are value-compared. Equal normalized values are aligned; different values are classified as requirement-code, requirement-test, code-test, Wiki-primary, or same-source conflicts.
- A claim whose project or version differs from the requested scope produces a blocking contamination conflict and is excluded from same-fact comparison.
- A Wiki claim must reference at least one primary evidence ID present in the same report. Missing support or disagreement with a primary claim is blocking.
- Conflict IDs are deterministic for the same type, fact key, values, and evidence IDs. Duplicate claims are merged before comparison.
- `KnowledgeConflictReport.status` is `BLOCKED` when a blocking conflict exists, `REVIEW_REQUIRED` when non-blocking conflicts or normalization warnings exist, otherwise `CLEAR`.
- Conflict analysis never mutates or automatically arbitrates requirement documents, code, test snapshots, or Wiki content. Public messages and excerpts are bounded and must not include dependency exception text, absolute paths, credentials, vectors, or storage internals.
- `DevelopmentPlanResponse` may append `conflictReport` without removing existing fields. Retrieval integration may report deterministic project/version contamination, but it must not invent semantic requirement-code conflicts unless both sources provide the same stable `factKey`.

### Evidence-bound Wiki generation

- `tools/build-version-wiki.py` may update version source JSON and rendered Wiki artifacts only as an explicit, reviewable operation. It reads optional ignored requirement snapshots and real Git commits with bounded, controlled commands; it never reads Qdrant or vector data.
- Schema 2 uses one stable `requirement-<hash>` page per actionable requirement entry plus one `version-<version>-overview` page. The stable identity includes requirement version, filename, and content hash. Similar names must not cause automatic merging.
- A short declarative requirement remains actionable. Coordination questions and non-requirement notes may be skipped, but absence from a new incremental document must never imply deletion.
- Product rules, process steps, data/config impacts, boundaries, and acceptance criteria may only be extracted from explicit headings, numbered items, tables, or clear source sentences. Uncertain text remains bounded evidence and is marked pending review.
- `requirementSources` contains bounded document/version/location/hash metadata. The original requirement file, absolute local path, vector payload, and credentials must never be persisted.
- `codeEntries` and code evidence require a real file or symbol at the selected target commit. An unmatched page displays `尚未关联代码实现`; repository file counts or module names are not feature implementation evidence.
- Missing real test results must be displayed as `没有真实执行快照`. Acceptance criteria and test suggestions are not execution evidence, and requirement statements must not be duplicated merely to fill a test section.
- Schema 2 artifacts must match the Java generator exactly. Schema 1 artifacts remain readable through additive normalization and are not forced into a historical bulk rewrite.
- Repeated schema 2 generation atomically replaces the selected version's tool-owned pages. Generic `version-<version>-module-*` and code-structure inventory pages are not part of the business Wiki.

### Forbidden persisted fields

Draft and manifest JSON must not contain fields for vectors, embeddings, Qdrant points, snapshots, WAL or storage internals, API keys, passwords, secrets, tokens, authorization, or credentials. Evidence may contain only bounded text excerpts and traceable requirement/code metadata.

Environment keys:

```text
WIKI_ROOT_PATH=data/wiki
WIKI_SOURCE_PATH=data/wiki-sources
WIKI_DRAFT_PATH=data/wiki-drafts
VERSION_MANIFEST_ROOT_PATH=data/version-manifests
REQUIREMENT_SNAPSHOT_ROOT_PATH=data/requirement-snapshots
```

## Scenario: 0.8 bounded parallel retrieval and caches

### 1. Scope / Trigger

- Trigger: changing retrieval branch orchestration, reranking, dependency timeouts/circuit breaking,
  embedding/result/Wiki caches, or retrieval evaluation gates.

### 2. Signatures

```java
RagOutcome<RetrievalBundle> RetrievalPipeline.execute(RetrievalRequest request)
RagOutcome<List<ChunkRecord>> RequirementReranker.rerank(
        String query, String documentId, String version, List<ChunkRecord> candidates, int limit)
boolean RetrievalCircuitBreaker.allow(String stage)
void RetrievalCircuitBreaker.success(String stage)
void RetrievalCircuitBreaker.failure(String stage)
```

### 3. Contracts

- Requirement recall, version-corpus reads, and code recall start concurrently on the bounded
  `retrievalExecutor`; each active branch has its own `app.rag.retrieval.branch-timeout-ms` deadline.
- `DEVELOPMENT_PLAN`, `REQUIREMENT_REVIEW`, and `WIKI_BUILD` use the same requirement rerank boundary.
  The default order is BGE followed by optional LLM reranking; a stage failure preserves the best
  available prior ordering and adds a stable warning.
- Circuit-breaker state is isolated by retrieval stage. Failures accumulate across allowed calls until
  the configured threshold, success clears the state, and an expired open interval permits a fresh call.
- Result-cache identity includes request profile, project, document, version, query, limit, corpus flag,
  and retrieval configuration fingerprint. Cache only `SUCCESS` and `NO_RESULTS`; never cache degraded
  or failed dependency outcomes.
- Embedding-cache identity includes model implementation plus input text. Cached arrays are cloned on
  read/write. Wiki index/page cache entries are bounded and TTL-based; successful atomic publication
  invalidates the affected project/version.
- Code hybrid retrieval indexes full structured text for sparse matching: repository-relative path, symbol
  type/name, identifier-split terms, generic code-role terms, and source. Dense embedding uses the same
  metadata plus a bounded source prefix; the original source payload remains unchanged.
- Code search may retrieve a bounded internal candidate pool larger than the caller limit, then apply a
  deterministic, domain-generic rerank. Original RRF rank remains the primary signal; symbol-term and
  generic service/controller/test intent may only refine it. The returned list must still honor the exact
  caller limit, preserve original order when no intent signal exists, and must never encode evaluation
  project names, golden labels, or case-specific symbols.
- Config keys are `APP_RAG_RETRIEVAL_BRANCH_TIMEOUT_MS`, `APP_RAG_RETRIEVAL_PARALLELISM`,
  `APP_RAG_RETRIEVAL_CIRCUIT_BREAKER_FAILURE_THRESHOLD`,
  `APP_RAG_RETRIEVAL_CIRCUIT_BREAKER_OPEN_MS`, `APP_RAG_RETRIEVAL_RESULT_CACHE_TTL_SECONDS`,
  `APP_RAG_RETRIEVAL_RESULT_CACHE_MAX_ENTRIES`, `APP_RAG_RETRIEVAL_EMBEDDING_CACHE_TTL_SECONDS`,
  `APP_RAG_RETRIEVAL_EMBEDDING_CACHE_MAX_ENTRIES`, `WIKI_CACHE_TTL_SECONDS`, and
  `WIKI_CACHE_MAX_ENTRIES`. CI dependency scanning reads `NVD_API_KEY` from the environment; never
  place the key directly in `pom.xml` or workflow arguments.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| One branch times out | Return its stable timeout warning; preserve successful sibling branches |
| Failure count is below threshold | Permit the next call without resetting accumulated failures |
| Failure count reaches threshold | Reject only that stage until the open interval expires |
| Reranker dependency fails | Preserve candidate order or prior rerank output and return `DEGRADED` |
| Cached outcome is degraded/failed | Do not store it |
| Project/document/version/config differs | Treat as a cache miss |
| Wiki version is republished | Invalidate that project/version before subsequent reads |
| Code query has no symbol/role signal | Preserve the original RRF candidate order |
| Code query has a generic implementation/controller/test intent | Refine only within the bounded candidate pool and still truncate to caller limit |

### 5. Good / Base / Bad Cases

- Good: three 100 ms branches finish near the slowest branch and all successful requirement profiles
  pass through the unified reranker.
- Base: LLM reranking is disabled; BGE order is returned and the same cache/isolation rules apply.
- Bad: one shared timeout wraps a sequential chain, an open code circuit blocks requirement recall, or
  a degraded response is reused after the dependency recovers.

### 6. Tests Required

- Assert all eligible branches start concurrently and controlled elapsed time improves by at least 30%
  versus their sequential fixture duration.
- Assert all three profiles invoke the shared reranker and a single timeout degrades independently.
- Assert failure accumulation across `allow` checks, success reset, stage isolation, and open behavior.
- Assert cache TTL/capacity, version/config isolation, cloned embedding arrays, Wiki invalidation, and
  exclusion of degraded outcomes.
- Assert code sparse text contains structured metadata and full source, dense text truncates only the
  source prefix, generic role intent can promote the appropriate candidate, no-signal queries preserve
  RRF order, and the public result never exceeds the requested limit.
- Keep at least 50 evaluation cases spanning normal recall, version leakage, similar-function false
  recall, zero results, dependency degradation, and cross-project pollution. CI enforces Recall@10,
  MRR, P95, and JaCoCo gates.

### 7. Wrong vs Correct

#### Wrong

Run branches sequentially, rerank only one caller, clear sub-threshold circuit failures during `allow`,
key cached retrieval by query text alone, embed unbounded source for every code chunk, or hard-code
golden labels and project-specific symbols into code ranking.

#### Correct

Launch independently bounded branches, centralize reranking in `RetrievalPipeline`, retain per-stage
circuit history until success/expiry, include every isolation dimension in cache identity, and use bounded,
deterministic, domain-generic code reranking over structured index text.

## Scenario: 0.8.2 trustworthy document retrieval evaluation

### 1. Scope / Trigger

- Trigger: changing retrieval evaluation JSONL labels, document matching, report aggregation, fixed corpus setup, or quality-gate interpretation.

### 2. Stable labels and signatures

```java
record GoldDocument(String filename, Integer parentOrder, Integer childOrder, List<String> mustContain)
Integer firstDocumentFileRank(List<GoldDocument> gold, List<ChunkRecord> candidates, int cutoff)
Integer firstDocumentSectionRank(List<GoldDocument> gold, List<ChunkRecord> candidates, int cutoff)
Integer firstDocumentChildRank(List<GoldDocument> gold, List<ChunkRecord> candidates, int cutoff)
```

Existing v1 JSONL without `parentOrder` or `childOrder` remains loadable and contributes only to file-level metrics. `childOrder` requires `parentOrder`; both orders are non-negative.

### 3. Contracts

- File hit matches only stable `filename`; it must not inspect parent or child text.
- Section hit requires `filename + parentOrder`. Evidence fragments may be checked only inside candidates from that matching parent; never accumulate text across parents.
- Child hit requires `filename + parentOrder + childOrder`; every `mustContain` fragment must occur in that exact candidate's `childText`. `parentText` must not satisfy a child label.
- The compatibility `documentRank` uses the strictest label present for the case: child, then section, then file.
- JSON and Markdown reports expose File/Section/Child Recall@10 separately. A layer contributes to a denominator only when its required structured label exists.
- Quality metrics are computed from one deterministic result per unique case id. Repetitions remain execution samples for latency, dependency health, degradation, and stability diagnostics.
- Keep the existing execution-level summary fields until all comparison consumers migrate; the v2 formal conclusion must use the unique-case layered summary.
- The v2 frozen corpus contains at least six documents, twelve distinguishable parent sections, twenty-four unique document HIT cases, and hard negatives spanning shared terms, wrong workflow stages, synonyms, near duplicates, and no-result queries.
- Corpus and dataset manifests record SHA-256 values. Changing a fixture, structured label, chunking rule, or preprocessing rule invalidates the previous manifest and requires regeneration before a formal run.
- Never add evaluation filenames, case ids, anchors, or project-specific golden labels to production retrieval/ranking code.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| `parentOrder < 0` or `childOrder < 0` | Reject the JSONL line with a stable line-numbered validation error |
| `childOrder` exists without `parentOrder` | Reject the JSONL line |
| Correct file, wrong parent | File hit; Section and Child miss |
| Correct parent, wrong child | File and Section hit; Child miss |
| Parent text contains anchor but child text does not | Child miss |
| Same case runs three times | Quality denominator +1; latency sample count +3 |
| Legacy v1 document label has no positions | File metric only; Section/Child denominators unchanged |
| Near-duplicate wrong file is returned | No file/section/child hit for the target gold label |

### 5. Tests Required

- Dataset compatibility tests for v1 and validation tests for structured v2 orders.
- Matcher regression tests for file-only, wrong-section, wrong-child, parent-text leakage, and bounded cutoff behavior.
- Report tests proving unique-case quality deduplication while execution-level latency still uses every repetition.
- Frozen-corpus contract test proving every v2 structured gold label maps to exactly one expected filename/parent/child after the production preprocessor and chunker.
- Existing comparison-tool tests must continue to parse legacy summary fields.

### 6. Wrong vs Correct

#### Wrong

Declare document quality solved because every gold label names the only indexed file, concatenate all matching `parentText` values until an anchor appears, or count three repetitions as three independent quality cases.

#### Correct

Use a multi-document hard-negative corpus, score file/section/child levels independently, validate child evidence only in the returned child, and separate unique-case quality from execution-level performance.

## Scenario: Agent-facing MCP knowledge facade

### 1. Scope / Trigger

- Trigger: adding or changing `/mcp`, an MCP tool, client configuration, API-key transport context, response projection, or MCP container wiring.

### 2. Signatures

The WebMVC Streamable HTTP endpoint is `/mcp`. It exposes exactly these read-only tool names:

```text
nexus_search_requirements
nexus_search_code
nexus_get_source
nexus_development_plan
nexus_wiki_page
nexus_version_diff
nexus_code_graph
nexus_impact_analysis
nexus_review_doubts
nexus_conflict_check
```

Tools delegate to existing domain services. They must not create a parallel retrieval, evidence, Wiki, or version-comparison implementation.

### 3. Contracts

- Every result uses the outer fields `resolved`, `data`, `evidence`, `quality`, `warnings`, and `truncated`.
- `resolved` always contains the effective `projectId` and nullable `version`/`documentId`.
- Evidence is request-scoped, bounded, and projected without internal chunk IDs, local absolute paths, credentials, vectors, or storage internals.
- Lists are capped at 20 results, source reads at 200 lines, excerpts at 2,000 characters, evidence at 40 entries, and the serialized response at 120,000 characters by default.
- The `X-API-Key` header authenticates both REST and MCP through `ApiKeyAuthenticationService`; tool execution authorizes permissions and project scope through `ProjectAuthorizationService`.
- `nexus_development_plan`, `nexus_review_doubts`, and `nexus_conflict_check` require `OPERATE`;
  the other seven tools require `PUBLIC_READ`.
- The server exposes three MCP prompts for implementation, review, and change-impact workflows.
- Published Wiki pages are exposed through the authenticated
  `nexus://wiki/{projectId}/{version}/{featureId}` resource template.
- Codex reads the key through `env_http_headers`; Cursor uses `${env:NEXUS_API_KEY}`. Never commit a real key.
- Environment keys: `MCP_ENABLED`, `MCP_MAX_RESULTS`, `MCP_MAX_SOURCE_LINES`, `MCP_MAX_EXCERPT_CHARACTERS`, `MCP_MAX_EVIDENCE`, `MCP_MAX_RESPONSE_CHARACTERS`, and `NEXUS_API_KEY` on clients.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Missing or invalid API key | Reject MCP transport initialization with HTTP 401 and stable public text |
| Role lacks tool permission | Return an MCP tool error without invoking the domain service |
| User lacks requested/default project access | Return an MCP tool error without cross-project data |
| Absolute, URI-like, or traversing source path | Reject before filesystem access |
| Repository-relative path resolves through a symlink outside the real repository root | Reject with `filePath escapes repository root` |
| Requested result/line/evidence limit exceeds the cap | Clamp output and set `truncated=true` |
| Serialized response exceeds the total cap | Return no oversized data/evidence, add `MCP_RESPONSE_TRUNCATED`, and set `truncated=true` |
| Optional dependency data is missing | Preserve existing `DEGRADED`/`NOT_AVAILABLE` status and warnings |

#### NEXUS 0.6 six-tool contract matrix

Changes to any of the original six tools must retain an executable `6 × 4` matrix in
`NexusMcpV06ContractTest`: input validation, authorization, expected-dependency degradation, and
truncation for each tool. Validation and authorization failures happen before downstream invocation.

| Tool | Permission | Stable availability warning | Required boundary checks |
|---|---|---|---|
| `nexus_search_requirements` | `PUBLIC_READ` | `NEXUS_SEARCH_REQUIREMENTS_UNAVAILABLE` | non-blank query; bounded limit |
| `nexus_search_code` | `PUBLIC_READ` | `NEXUS_SEARCH_CODE_UNAVAILABLE` | non-blank query; bounded limit |
| `nexus_get_source` | `PUBLIC_READ` | `NEXUS_GET_SOURCE_UNAVAILABLE` | safe repository-relative path; valid line range and line cap |
| `nexus_development_plan` | `OPERATE` | `NEXUS_DEVELOPMENT_PLAN_UNAVAILABLE` | non-blank requirement; bounded evidence/text |
| `nexus_wiki_page` | `PUBLIC_READ` | `NEXUS_WIKI_PAGE_UNAVAILABLE` | non-blank version and feature ID |
| `nexus_version_diff` | `PUBLIC_READ` | `NEXUS_VERSION_DIFF_UNAVAILABLE` | non-blank distinct versions |

Only known dependency/IO availability failures may produce these degraded responses. Authentication,
project authorization, invalid input, not-found behavior, and programming defects remain hard failures.
Warnings are deterministic and must not contain exception messages, absolute paths, credentials, request
bodies, or private endpoints. Tool-level list/text/range truncation is OR-combined with global serialized
response truncation, and tests assert both `truncated=true` and the resulting bounded payload.

### 5. Good / Base / Bad Cases

- Good: Codex or Cursor sends an environment-derived key, requests `pom.xml` lines 1–2, and receives a bounded result plus a repository-relative evidence reference.
- Base: a permitted client omits `projectId`; the configured default project is resolved and returned explicitly.
- Bad: a client requests `/etc/passwd`, `../secret`, `file:///tmp/secret`, or a repository symlink that points outside the root; the request is rejected and no content is returned.

### 6. Tests Required

- Unit tests assert shared authentication, permission and project authorization, caps, redaction, path validation, and total-response truncation.
- HTTP integration tests assert 401, initialize, ten-tool and three-prompt discovery,
  Wiki resource-template discovery, JSON schemas, and a representative `tools/call`.
- Source tests assert both normal repository reads and symlink escape rejection.
- Release smoke tests use MCP Inspector plus current Codex and Cursor clients to call at least one evidence-bearing tool.
- Full `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify` remains green.

### 7. Wrong vs Correct

#### Wrong

Return domain objects directly, trust a normalized lexical path, duplicate authentication in the tool body, or embed an API key in `.codex/config.toml` / `.cursor/mcp.json`.

#### Correct

Project domain data into the bounded MCP envelope, validate the target's real path stays below the real repository root, reuse shared authentication/authorization services, and reference only `NEXUS_API_KEY` from checked-in client configuration.

## Scenario: Multi-language static code intelligence

### 1. Scope / Trigger

- Trigger: changing code scanning, `CodeChunk`, static graph persistence, symbol traversal,
  commit impact, `/api/code/graph/symbols`, `/api/code/impact`,
  `nexus_code_graph`, or `nexus_impact_analysis`.

### 2. Signatures

```java
CodeScanner.ScanResult scan(RagProperties.Code config)
CodeScanner.ScanResult scanFiles(RagProperties.Code config, String commitSha, List<String> paths)
CodeIntelligenceResponse graph(String projectId, String symbol, String direction, Integer depth, Integer limit)
CodeIntelligenceResponse impactSymbol(String projectId, String symbol, Integer depth, Integer limit)
CodeIntelligenceResponse impactCommits(String projectId, String fromCommit, String toCommit,
                                       Integer depth, Integer limit)
```

```http
POST /api/code/graph/symbols
POST /api/code/impact
```

SQLite tables are `code_graph_snapshot`, `code_symbol`, and `code_relation`; every
row is scoped by `project_id + commit_sha`.

### 3. Contracts

- Java, Go, Python, and TypeScript use locked Tree-sitter grammars on JDK 21.
  Kotlin is capability-gated: a native/ABI failure disables it with a diagnostic
  and must not prevent other languages from indexing.
- `CodeChunk.language` is additive. The legacy ten-argument constructor and
  Qdrant payloads missing `language` derive it from `filePath`.
- Full and incremental indexing select files through `CodeScanner.supports`;
  active indexing code must not filter only `.java`.
- Qdrant stores searchable chunks. `${CODE_GRAPH_ROOT_PATH:data/code-graph}/code-graph.db`
  stores symbols and relations only—never source bodies, vectors, credentials,
  absolute repository paths, or Qdrant internals.
- Resolution tiers are `EXACT`, `SAME_FILE`, `HEURISTIC`, and `UNRESOLVED`.
  Only `EXACT` and `SAME_FILE` count as certain impact. `HEURISTIC` is inferred;
  ambiguous or dynamic calls remain visible as `UNRESOLVED`.
- Traversal caps depth to 5 and relations to 200. Graph writes replace one
  project/commit snapshot in a JDBC transaction.
- Commit impact uses validated SHAs through `GitDiffService`. When the target
  commit snapshot is absent, return `NOT_AVAILABLE` with changed files; never
  present a file-only fallback as symbol-complete.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Unsupported extension | Skip through the language registry; do not parse as another language |
| Binary or oversized source | Skip and emit `FILE_SKIPPED` |
| Parser/native failure for one file/language | Emit `PARSE_FAILED` or `LANGUAGE_DISABLED`; continue supported files |
| Missing graph snapshot | Return `NOT_AVAILABLE` and instruct the caller to index |
| Symbol absent in latest snapshot | Return `NOT_AVAILABLE`; do not guess a similarly named symbol |
| Both/neither impact selectors provided | Reject; require exactly symbol or both commits |
| Target commit graph absent | Return file-level changes plus `NOT_AVAILABLE` warning |
| Ambiguous call target | Persist and return `UNRESOLVED`; never count as certain |
| Depth/limit exceeds caps | Clamp to 5/200 and report truncation when the relation cap is reached |

### 5. Good / Base / Bad Cases

- Good: a Java caller invokes a unique same-file method; traversal returns the
  caller/callee edge as `SAME_FILE` and includes it in certain impact.
- Base: a project has completed semantic indexing but no 0.7 graph snapshot;
  search still works and graph tools return explicit `NOT_AVAILABLE`.
- Bad: two project/commit-scoped symbols share a simple name. The resolver must
  not choose one arbitrarily or cross into another project/commit.

### 6. Tests Required

- Native parser fixture tests assert symbol, call, chunk and `language` output
  for Java, Go, Python, and TypeScript on JDK 21.
- Graph-store tests assert transaction replacement, project/commit isolation,
  `SAME_FILE`, inferred and unresolved resolution, delete/rename handling, and rollback.
- REST/MCP tests assert shared auth/project permission, ten-tool discovery,
  selector validation, caps, safe paths, `NOT_AVAILABLE`, and truncation.
- Run full `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify` and
  `git diff --check`.

### 7. Wrong vs Correct

#### Wrong

Filter changed files with `.endsWith(".java")`, persist source text in SQLite,
resolve an ambiguous simple name as certain, or return an empty available graph
when the target snapshot does not exist.

#### Correct

Route extensions through the capability registry, keep graph rows structural and
project/commit-scoped, separate certainty tiers, preserve unresolved calls, and
degrade missing target snapshots to explicit file-level `NOT_AVAILABLE`.

## 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Required build or manifest field is blank | Bean/service validation rejects the request |
| Conflict request project/version is blank or a nested claim is invalid | Bean validation rejects the request; no analysis is performed |
| Claim project/version differs from the requested scope | Return a blocking contamination conflict and exclude that claim from fact comparison |
| Wiki claim lacks primary supporting evidence or disagrees with primary evidence | Return a blocking conflict; never publish it as verified knowledge |
| Identifier contains path separators or traversal | Reject before any filesystem write |
| Unknown project | Use the existing project-registry error contract |
| Retrieval succeeds with evidence | `SUCCESS` |
| Retrieval succeeds with zero evidence | `NO_RESULTS` |
| One retrieval source fails but another has evidence | `DEGRADED` with a safe warning |
| A core retrieval source fails and no evidence remains | Throw `RagUnavailableException` |
| Qdrant version payload read fails | Return/throw stable public text; log internal cause only |
| Both referenced requirement snapshots exist | Materialize both baseline chains, then compare without reading Qdrant |
| Either snapshot is missing but Qdrant payloads exist | Use the payload-only compatibility fallback, filtering absence-derived removals |
| No explicit snapshot alias or manifest requirement reference exists | Mark requirements `NOT_AVAILABLE`; do not infer a mapping |
| Snapshot schema, identity, or entry IDs are invalid | Reject the snapshot with stable public text; do not publish a partial comparison |
| Draft or manifest serialization contains a forbidden field | Abort the write and publish no partial output |
| No requirement version delta exists | Return `NO_CHANGES`; do not fabricate features |
| A comparison source lacks references or data | Mark that section `NOT_AVAILABLE` and add a safe warning |
| Browser receives fewer than two manifests | Disable compare and show an empty state; do not call `/api/versions/compare` |
| Browser receives an unavailable source | Keep other tabs usable and show the source's safe warning |
| Neither a formal manifest nor a published Wiki index exists for a selected version | Return the stable manifest-not-found error |
| Git commit is not a concrete SHA | Reject before starting Git |
| Test snapshot contains duplicate `caseId` | Reject the manifest |
| Authentication is enabled with no usable credentials | Fail application startup |
| Authentication is explicitly disabled | Start only for the selected configuration and emit a prominent security warning |
| BGE cannot connect or exceeds its read timeout | Let `ResilientBgeReranker` return its stable degraded result; do not wait indefinitely |
| Best-effort cleanup fails | Log the internal exception with a path-free message; preserve the original operation outcome |
| Model cites only IDs in the request-scoped evidence registry | Return accepted IDs and mark the claim `SUPPORTED` |
| Model mixes valid and unknown evidence IDs | Remove unknown IDs, mark the claim `PARTIAL`, and add `INVALID_EVIDENCE_REFERENCE` |
| Retrieval returned evidence but a generated claim cites none | Mark the claim `UNSUPPORTED` and add `MISSING_EVIDENCE_REFERENCE` |
| Retrieval returned no evidence | Preserve `NO_RESULTS`; do not fabricate a citation warning |
| SSE emits an unknown event type | Ignore the event, add `UNKNOWN_PLAN_EVENT_TYPE`, and do not count it as a valid model event |

Raw dependency exceptions, URLs, request payloads, credentials, absolute paths, and stack traces must never appear in public warnings or generated files.

## 5. Good / Base / Bad Cases

### Good

A target business version has a published Wiki index and an explicit alias to a reviewable requirement snapshot. Its base commit matches a published baseline version. The resolver returns a bound requirement version and business baseline, and comparison reports traceable, bounded requirement evidence without reading vectors.

### Base

Target and base snapshots contain identical parent keys and content hashes. Requirement comparison returns an available result with zero changes; a no-op generator run preserves the existing `generatedAt` and produces byte-identical JSON.

### Bad

A request uses `version: "../../storage"`, a Git value such as `HEAD;rm`, duplicate snapshot entry IDs, an unreviewed version alias, or generated JSON contains an `embedding` field. The request is rejected or the version remains unbound; no partial artifact is published.

A generated claim cites an ID from another request, project, document, or version. The server removes the ID, degrades support and aggregate quality, emits a stable warning, and never exposes the foreign evidence to the client.

## 6. Tests Required

Changes to these contracts require assertions for:

- retrieval success, deduplication, and limit application
- normal zero-hit `NO_RESULTS`
- one-sided retrieval failure with `DEGRADED`
- no-evidence core failure with `RagUnavailableException`
- profile source selection, including requirement-only review
- requirement comparison using `parentId`, with `filename + parentOrder` fallback and content-hash change detection
- requirement snapshot parsing, identity validation, alias lookup, incremental inheritance, explicit removal, missing-baseline/cycle rejection, duplicate-entry rejection, and forbidden-field absence
- resolver precedence, missing-reference enrichment, exact commit-chain baseline inference, and unmapped-version behavior
- synthetic temporary snapshot-chain coverage without Qdrant access; tests must pass when no local generated snapshots exist
- generator no-op reproducibility and exclusion of the original large archive
- different functions receiving distinct, stable feature IDs
- manifest save, update, list ordering, atomic replacement, path traversal rejection, Git SHA rejection, and duplicate test case rejection
- Git added, modified, deleted, renamed parsing and category counts
- test snapshot aggregate and case-level comparison, including missing-snapshot `NOT_AVAILABLE`
- Wiki page changes and missing-index warning behavior
- no writes to formal Wiki/source roots from draft build
- forbidden-field absence in serialized drafts and manifests
- Controller validation, project access, and permission requirements
- conflict normalization/deduplication, all primary source-pair classifications, same-source conflict, project/version contamination, Wiki evidence support, deterministic status, and development-plan response compatibility
- evidence ID stability/collision handling, path and excerpt sanitization, whitelist acceptance/filtering, missing-reference behavior, coverage calculation, synchronous response compatibility, SSE event validation, and citation browser contracts
- Browser route, static page contract, navigation, deep-link parameter consumption, and HTML escaping
- Browser empty, loading, error, unavailable-source, and missing-real-test-snapshot states
- Spring application-context binding for `WikiProperties` and `VersioningProperties`
- draft transition legality, audit history, approved-only publication, atomic rollback, and failed-publication preservation
- authentication startup validation for enabled-empty, enabled-valid, and explicitly disabled configurations
- BGE/Qdrant clients retain bounded connect/read timeout configuration
- production code contains no empty catch blocks or ignored exceptions without logging or propagation

Run the full Java 21 verification before delivery:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify
git diff --check
```

## 7. Wrong vs Correct

**Wrong:** catch a Qdrant exception and return an empty list, then label the result `NO_RESULTS` or an available comparison with no changes.

**Correct:** preserve the failed-stage diagnostic; return `DEGRADED` only when another evidence source remains, otherwise use the stable public failure contract.

**Wrong:** rely on `ResilientBgeReranker` while its HTTP client has unbounded transport waits, or silently discard cleanup/parsing exceptions.

**Correct:** bound BGE connection and read time, then log internal failures safely and expose only the stable degradation contract.

**Wrong:** infer a requirement baseline from a nearby-looking business version, treat each incremental document as a complete list, infer removal from absence or正文关键词, copy Qdrant points or vectors into a snapshot, or require local Qdrant data to display a committed historical requirement diff.

**Correct:** map business versions only through reviewed snapshot aliases, replay `baseRequirementVersion` into a cumulative state, require a structured `REMOVE` event for deletion, persist bounded text/hash/source facts, compare materialized snapshots first, and keep unmapped versions explicitly `NOT_AVAILABLE`.

**Wrong:** copy Qdrant points or vectors into `wiki-source.json` or a version manifest, publish drafts directly to `data/wiki`, or treat suggested tests as passed execution results.

**Correct:** map payloads to bounded textual evidence, reject forbidden fields, keep drafts and manifests in their dedicated roots, and require real test snapshots for test-result comparison.

**Wrong:** concatenate requirement, code, tests, and Wiki into one prompt and let the model silently choose whichever statement sounds most plausible.

**Correct:** preserve source-specific claims and evidence, compare only stable structured fact keys, block version/project contamination and stale or unsupported Wiki claims, and leave arbitration to an explicit review workflow.

**Wrong:** let the browser interpolate API text directly into `innerHTML`, or turn a missing source into a zero-change card.

**Correct:** escape every API-derived value, render `NOT_AVAILABLE` with a safe warning, and keep the other comparison tabs available.

**Wrong:** trust model-provided evidence IDs, pass them directly to JSON/SSE, or count an unknown SSE event as proof that generation succeeded.

**Correct:** validate every ID against the request-scoped registry before serialization, cap and deduplicate citations, ignore unknown event types with a stable warning, and require at least one accepted model event for stream completion.

## Scenario: Authorized real-project retrieval calibration

### 1. Scope / Trigger

- Trigger: a user-authorized repository is used to calibrate live requirement/code retrieval or prove an MCP evidence chain.
- The source repository stays read-only. Only sanitized requirements, relative stable labels, project filters and runners belong in NEXUS.

### 2. Signatures

```text
python3 tools/shiguang-eval.py prepare|smoke|all [--base-url URL] [--repository PATH]
scripts/run-shiguang-eval.sh
```

The live Java evaluation entry remains `RetrievalEvaluationIT`; the runner selects resources instead of adding a
second evaluator.

### 3. Contracts

| Key | Requirement |
| --- | --- |
| `SHIGUANG_REPOSITORY_PATH` | Required absolute path to the authorized Git checkout; never committed |
| `NEXUS_API_KEY` | Required by prepare/smoke; never printed or written |
| `NEXUS_BASE_URL` | Optional; defaults to `http://127.0.0.1:8080` |
| `RETRIEVAL_EVAL_DATASET_RESOURCE` | Optional classpath JSONL; defaults to the versioned general dataset |
| `RETRIEVAL_EVAL_BASELINE_RESOURCE` | Absent means the committed default baseline; blank means explicit calibration without a gate |
| `SHIGUANG_EVAL_BASELINE_RESOURCE` | Optional measured baseline forwarded by the Shiguang runner |

Every real-project profile must use independent requirement/code collections and explicit include/exclude lists.
Gold code labels are repository-relative paths plus stable symbol names; gold requirements use a sanitized,
committed filename and bounded text fragments.

A formal Python/Transformers reranker evaluation must pass the virtualenv interpreter entry itself to the
comparison tool. Do not canonicalize that path through symlink resolution: resolving `.venv/.../bin/python` to
its base interpreter can silently discard the virtualenv package environment. The manifest must fail closed when
required `torch` or `transformers` version metadata cannot be read; `unavailable`, blank, or guessed versions are
not valid formal runtime fingerprints.

The formal evaluation source fingerprint must cover the complete executed reranker chain, including the Java BGE
HTTP client, evaluation profile/configuration, frozen dataset, comparison and runner scripts, Python reranker
service, health checker, dependency declaration, and startup script. Runtime metadata must record the preserved
virtualenv interpreter path, Python/PyTorch/Transformers versions, model identifier, and `secretsRecorded=false`.
Do not include API keys, request bodies, business source text, or model cache blobs in the manifest.

### 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| API key or authorized repository path missing | Runner exits non-zero before network mutation |
| Repository is not Git or the known source anchor is absent | Runner rejects the path |
| Upload/index/MCP request fails | Runner exits non-zero with bounded HTTP context and no key |
| MCP search lacks `requirement:*` or `code:*` | Smoke fails |
| Baseline env key is blank | Report is written, baseline assertion is explicitly skipped |
| Baseline resource is named but missing | Evaluation fails |
| Any retrieval dependency warning occurs in a required branch | Case records an infrastructure failure |

### 5. Good / Base / Bad Cases

- Good: fixed repository commit, sanitized corpus and fixed model services produce a report that is compared with a measured, committed baseline.
- Base: first run uses a blank baseline setting, writes a calibration report, and leaves roadmap/CI acceptance unchecked.
- Bad: invent threshold numbers, ingest configs/credentials/PII, use absolute paths as Gold labels, or claim completion from a calibration-only report.

### 6. Tests Required

- Dataset loader asserts case count, project ID, all three evaluation paths and absence of resource/config code labels.
- Profile binding asserts isolated collections, environment-resolved repository path, safe includes and sensitive-path excludes.
- Settings tests assert default baseline behavior and explicit blank-baseline calibration behavior.
- Script syntax/help checks run before full Java 21 `verify`.
- Comparison-tool tests assert that missing required Python packages fail formal manifest generation, the virtualenv
  interpreter path is preserved without symlink dereferencing, and the executed reranker source set is fingerprinted.
- Live acceptance records traceable MCP IDs and the generated Recall@10/MRR/P95 report; offline tests cannot substitute for it.

### 7. Wrong vs Correct

**Wrong:** commit a guessed `0.85` baseline and enable CI, or mark the roadmap complete because the dataset parses.

**Correct:** run calibration against fixed dependencies, preserve the actual virtualenv interpreter entry, require
readable PyTorch/Transformers versions, fingerprint the complete executed reranker chain, rerun as a gate, and only
then mark the live acceptance item complete.

## Scenario: 0.8.1 child-first rerank and singleton-safe evaluation

### 1. Scope / Trigger

Apply this scenario when changing requirement rerank candidate lifecycle, BGE passage construction,
parent aggregation, code candidate ranking, retrieval-evaluation stage diagnostics, or the formal
`0.8-rerank` to `0.8.1-quality` comparison runner.

The optimization is valid only when the rerank input has exactly one candidate and child-first quality
mode is enabled. It is not a general switch for disabling BGE.

### 2. Signatures

```java
RagOutcome<List<ChunkRecord>> DefaultRequirementReranker.rerank(
        String query,
        String documentId,
        String version,
        List<ChunkRecord> candidates,
        int limit)
```

```text
RagStageDiagnostic(
  stage = "bge.rerank.singleton_skip",
  status = SUCCESS,
  latencyMs = 0,
  itemCount = 1
)
```

```text
scripts/run-shiguang-eval.sh
  -> Python /health contract
  -> Java HttpBgeRerankerLiveIT contract
  -> frozen corpus rebuild
  -> 0.8-rerank report
  -> 0.8.1-quality report
  -> comparison.json / comparison.md / manifest.json
```

The evaluation summary exposes integer counters named `bgeCalls`, `bgeSuccesses`,
`bgeDegradations`, `bgeNoCandidateSkips`, and `bgeSingletonSkips`.

### 3. Contracts

- Child candidates remain available through BGE rerank; stable parent aggregation happens after child
  rerank and before final Top-K output.
- BGE passages are bounded and include filename, parent context, and child text. Candidate count,
  passage length, batch size, and final Top-K remain configuration-bounded.
- When child-first quality mode is enabled and rerank input size is exactly one, preserve that candidate,
  do not invoke `BgeReranker`, emit `bge.rerank.singleton_skip`, and report a successful decision.
- Empty input remains `NO_RESULTS`; it is counted as `bgeNoCandidateSkips`, not a singleton skip.
- Two or more candidates must continue through the real BGE path. When child-first quality mode is
  disabled, even a singleton follows the baseline BGE path for behavior-control compatibility.
- Optional LLM rerank still runs after the BGE or singleton decision when it is enabled.
- Expected BGE failure preserves original retrieval order and uses the existing bounded warning. A
  singleton skip must never conceal a real multi-candidate BGE failure.
- Formal candidate acceptance requires every evaluated execution to be accounted for by exactly one
  BGE decision: real call, no-candidate skip, or singleton skip. Unexpected degradation remains zero.
- A singleton-only formal candidate is healthy only when the runner has independently verified both the
  Python reranker health contract and the Java-to-BGE live `/rerank` contract in the same run.
- Formal manifests record the live-contract proof, timeouts, model/runtime fingerprint, variant flags,
  source hashes, and `secretsRecorded=false`; they never record model input or business text.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Candidate input is empty | `NO_RESULTS`; increment no-candidate skip; do not call BGE |
| Child-first enabled and input size is one | Preserve candidate; `SUCCESS`; emit singleton diagnostic; do not call BGE |
| Child-first disabled and input size is one | Call BGE with limit one; record normal BGE diagnostic |
| Input size is two or more | Call BGE with bounded Top-K regardless of singleton optimization |
| BGE throws an expected availability/runtime failure | Preserve source order; return structured degraded warning; increment degradation |
| Candidate report omits or mis-types a BGE counter | Formal report-contract check fails closed |
| Singleton-only candidate lacks independent live-contract proof | Formal comparison fails BGE-health acceptance |
| Calls + no-candidate skips + singleton skips differs from total executions | Formal comparison fails decision-accounting acceptance |
| Any cross-project or cross-version result appears | Quality gate fails; never trade isolation for Recall |

### 5. Good / Base / Bad Cases

- Good: five child chunks are reranked, then aggregated to one parent; because the next invocation sees a
  single final candidate, it records `bge.rerank.singleton_skip`; the formal runner already proved the
  live BGE contract and all decisions are accounted for.
- Base: quality mode is disabled for a behavior-control run; one candidate still calls BGE, allowing the
  control report to expose the CPU cost of the legacy no-op inference.
- Bad: set BGE off globally, count zero calls as healthy without a live proof, skip a two-candidate rerank,
  or claim Recall improvement when the same-worktree control has equal Recall.

### 6. Tests Required

- `DefaultRequirementRerankerTest` asserts singleton skip only in child-first mode, preserved data,
  success diagnostic, zero BGE calls, and the baseline singleton compatibility path.
- Multi-candidate and failure tests assert BGE is invoked with bounded Top-K and fallback preserves order.
- Pipeline tests assert child-first rerank precedes stable parent aggregation and that enriched passages
  remain bounded.
- Evaluation matcher/report tests assert singleton counters, stage detection, failure attribution, and
  complete decision accounting.
- Comparison-tool tests assert real-call health, singleton-only health with live proof, failure without
  proof, failure on degradation, and failure on incomplete accounting.
- The formal runner must execute Python health, Java live contract, fixed corpus rebuild, both isolated
  variants, and comparison generation before the result may be marked `formal` and `PASS`.
- Final task verification includes Java 21 `./mvnw -B verify`, Python unit tests and compile, shell syntax,
  task-context validation, and `git diff --check`.

### 7. Wrong vs Correct

**Wrong:** "0.8.1 made zero BGE calls, therefore reranking was disabled and the latency result is invalid."

**Correct:** "0.8.1 independently proved the live Python and Java BGE contracts, retained real BGE for
multi-candidate inputs, and skipped only 144 mathematically order-invariant singleton decisions; 18 empty
and 144 singleton decisions account for all 162 executions with zero unexpected degradation."
