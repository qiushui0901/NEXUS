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

## Business Project and Repository Scope

- Product-facing `projectId` identifies a business project. Repository IDs identify independently indexed
  code repositories and remain the scope for Git, Webhook, source, graph, and sync operations.
- Every product-facing projection must resolve through the business-project catalog, including knowledge
  management, monitor status, code-index status, Wiki, retrieval, and authorization. A controller must not
  fall back to `ProjectRegistry.require(businessProjectId)`.
- Knowledge management projects one shared requirement base plus one code base per enabled owned/referenced
  repository. Code-base IDs include both business project and repository identity; rebuild and retrieval
  actions route to the repository ID while responses keep the business project ID.
- One business project owns one shared requirement scope and one version-anchor repository. Product version
  comes from the anchor repository build metadata; requirement version may lag and must emit
  `REQUIREMENT_VERSION_BEHIND` without being relabeled as the product version.
- Default code retrieval expands a business project to all enabled owned repositories plus explicitly
  referenced shared repositories. Optional repository filters may only narrow this resolved scope.
- Code evidence and deduplication keys retain repository ID so identical paths and symbols in different
  repositories never collide.
- `RetrievalBundle.allowedRepositoryIds` is the code-evidence whitelist. `EvidenceRegistry` validates
  `CodeChunk.projectId` against this repository set, never against the business project ID.
- Repository scope changes are cache-invalidating events. Adding/removing a shared repository or
  registering/unregistering an owned repository must invalidate every retrieval cache entry for that
  business project; request `repositoryIds` alone is not a catalog revision.
- Code counts use each repository's publication mode: legacy direct collections remain readable, while
  safe-published repositories use `<base>-live`. Missing/unavailable and true zero are distinct states.
- Version manifest schema v3 stores `productVersion` and `repositoryBaselines`; schema v1/v2 single-commit
  manifests remain readable.
- Any manifest copy/enrichment path must preserve schema-v3 `productVersion` and `repositoryBaselines`.

## 2. Signatures and APIs

### Unified retrieval

```java
RagOutcome<RetrievalBundle> RetrievalPipeline.execute(RetrievalRequest request)
```

Supported profiles:

- `DEVELOPMENT_PLAN`: requirement and code evidence
- `REQUIREMENT_REVIEW`: requirement evidence only
- `CODE_RETRIEVAL`: code evidence only (knowledge management code retrieval tests)
- `WIKI_BUILD`: requirement and code evidence for draft enrichment

Requirement ingestion uses structure-aware chunking: Markdown headings become parent boundaries and
`sectionPath/heading/requirementId/module/acceptanceCriteria` metadata is stored in the Qdrant payload
and surfaced in knowledge-management retrieval hits. Long-document truncation is explicit via
`TextPreprocessor.cleanWithDiagnostics` and returned through `IngestResponse.truncatedSources`.
`RequirementIngestionService.ingestIncremental` provides a source-hash diff entry point for
version-level imports; unchanged sources are skipped.

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

## Scenario: Enterprise real-RAG release evaluation

### 1. Scope / Trigger

- Trigger: changing retrieval evaluation datasets, matching, metrics, reports, thresholds, or the live
  `RetrievalEvaluationIT` release gate.
- The default CI suite validates contracts and metric logic without starting Qdrant, Embedding, BGE, or
  a real repository. Live evaluation is opt-in and runs only when `RUN_RETRIEVAL_EVAL=true`.

### 2. Dataset and provenance contracts

- Enterprise JSONL uses `schemaVersion=2`. Every case has a supported `queryType`, a 40-character
  lowercase Git `sourceCommit`, and an `APPROVED` review with non-blank reviewer and timestamp.
- Every Gold label has a unique stable evidence ID. Requirement IDs use
  `requirement:<project>:<version>:<filename>:<parent|*>:<child|*>`; code IDs use
  `code:<project>:<40-char-commit>:<repository-relative-path>:<symbol>`.
- Evidence IDs must not use absolute paths, transient vector point IDs, or runtime-generated values.
- The frozen dataset SHA-256 is asserted by a unit test. Any content change requires human review and an
  explicit SHA update in the same change.

### 3. Metrics and quality gate

- Recall, MRR, nDCG, no-result accuracy, and degradation rate use one observation per unique case so
  repetitions do not inflate quality denominators. Repetitions remain part of latency statistics.
- Multi-Gold nDCG matches each Gold at most once and evaluates the strictest available document identity.
- The live quality gate reads a versioned threshold resource, evaluates all configured minimum and maximum
  bounds, and reports all violations in one failure.
- Any infrastructure failure is a hard gate failure independent of retrieval quality. The report must be
  written before the gate throws so operators can distinguish dependency/configuration failure from quality
  regression.
- Live evaluation must bind a frozen dataset, repository commit, requirement corpus, index/model versions,
  and threshold file. It must not silently build or mutate production indexes.

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
- `DEVELOPMENT_PLAN`, `REQUIREMENT_REVIEW`, and `WIKI_BUILD` use the same requirement rerank boundary;
  `CODE_RETRIEVAL` is code-only and skips requirement rerank. The default order is BGE followed by
  optional LLM reranking; a stage failure preserves the best available prior ordering and adds a
  stable warning.
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
- JSON and Markdown reports expose File/Section/Child Recall@1/@3/@5/@10 separately. A layer contributes to a denominator only when its required structured label exists.
- Keep the existing flat @10 fields and add typed per-layer cutoff summaries. When @10 is perfect but a
  lower cutoff still misses, emit a ranking-sensitivity warning; small-corpus decisions must not rely on
  @10 alone.
- Quality metrics are computed from one deterministic result per unique case id. Repetitions remain execution samples for latency, dependency health, degradation, and stability diagnostics.
- Keep the existing execution-level summary fields until all comparison consumers migrate; the v2 formal conclusion must use the unique-case layered summary.
- The `document-v2-v2` frozen corpus contains eighteen documents and at least thirty-six parent chunks:
  six gold documents plus two independent semantic hard negatives for each gold theme. It retains
  twenty-four unique document HIT cases and spans shared terms, wrong workflow stages, synonyms and near
  duplicates.
- Manifest schema 2 labels each file as `gold` or `hard-negative`; every hard negative has a
  `hardNegativeFor` reference to a declared gold filename. The corpus test loads all eighteen files when
  proving that every structured anchor is unique.
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

## Scenario: 0.8.2 structured document retrieval evaluation

### 1. Scope / Trigger

Apply this scenario when changing `RetrievalEvaluationCase`, dataset validation, matcher rank semantics,
evaluation JSON/Markdown summaries, setup fixture selection, or the versioned `document-v2` corpus.
It prevents file-level matches and parent-text leakage from being reported as precise evidence retrieval.

### 2. Signatures

```java
record GoldDocument(
        String filename,
        Integer parentOrder,
        Integer childOrder,
        List<String> mustContain)

record RecallByCutoff(
        int cases,
        int hitsAt1, double recallAt1,
        int hitsAt3, double recallAt3,
        int hitsAt5, double recallAt5,
        int hitsAt10, double recallAt10)
```

```text
RETRIEVAL_EVAL_MODE=0.8.2-document-v2
RETRIEVAL_EVAL_SETUP_FIXTURE=<single-markdown-file-or-directory>
RETRIEVAL_EVAL_SETUP_PROJECT_ID=<registered-project>
RETRIEVAL_EVAL_SETUP_DOCUMENT_ID=<document-id>
RETRIEVAL_EVAL_SETUP_VERSION=<version>
RETRIEVAL_EVAL_SETUP_SKIP_CODE=true|false
scripts/run-document-v2-eval.sh
```

The report keeps the legacy execution-level `summary` and flat @10 fields, and adds typed
`fileRecallByCutoff`, `sectionRecallByCutoff`, and `childRecallByCutoff` summaries for @1/@3/@5/@10,
plus strict document MRR@10 and no-result accuracy.

### 3. Contracts

- `parentOrder` and `childOrder` are zero-based, optional structured labels. `childOrder` requires
  `parentOrder`; old v1 labels without either field remain valid file-level labels.
- File rank matches `filename`. Section rank matches `filename + parentOrder`. Child rank matches
  `filename + parentOrder + childOrder` and every `mustContain` fragment in that candidate's own
  `childText`.
- Never read `parentText` or another child to satisfy a child anchor. Never fall back to a looser rank
  when a stricter structured label exists but misses.
- Strict document MRR uses child rank when a child label exists, otherwise section rank, otherwise file
  rank.
- Quality metrics select one deterministic execution per case id, preferring the smallest repetition.
  Latency, BGE accounting and stability continue to use every execution.
- `document-v2-v1` is the historical six-document calibration corpus. `document-v2-v2` contains eighteen
  independent documents: six gold files plus twelve query-theme hard negatives. Renamed or mechanically
  duplicated fixtures do not count, and scores from the two corpus versions must not be mixed.
- A fixture path may name one Markdown file or a directory. Directory ingestion uses sorted `.md` files.
  Setup records deterministic per-file hashes; document-only evaluation may explicitly skip code indexing.
- The frozen corpus manifest records dataset hash, file hash, byte count, parent count and stable anchors.
  Any corpus edit requires updating the manifest and structured-position contract test.
- The versioned runner must freeze `RETRIEVAL_BRANCH_TIMEOUT_MS=30000` and
  `BGE_RERANK_READ_TIMEOUT_MS=120000` for the local CPU calibration. It checks Qdrant, Ollama and the
  BGE health/rerank contract before setup, rebuilds only the isolated v2 requirement collection, and
  defaults to zero warmups and one repetition.
- A single-repetition run is classified as calibration. Its quality metrics and failure attribution are
  usable, but its P50/P95 are descriptive only and must not become a stable performance gate.
- `DOCUMENT_PARENT_AGGREGATION_LOSS` is a valid child-level failure when the structured gold child remains
  in BGE output but final parent aggregation chooses a sibling as the parent representative. Do not
  relabel it as a child hit because file and section still match.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| `parentOrder < 0` or `childOrder < 0` | Dataset load fails |
| `childOrder` exists without `parentOrder` | Dataset load fails |
| Correct file, wrong parent | File hit; Section and Child miss |
| Correct parent, wrong child | File and Section hit; Child miss |
| Anchor exists only in `parentText` or a sibling child | Child miss |
| Duplicate case executions | One quality denominator item; all executions remain latency samples |
| Fixture is neither a Markdown file nor a directory with Markdown files | Setup fails before indexing |
| Source `.md` set differs from the schema-2 manifest | Corpus contract test fails before live calibration |
| `hardNegativeFor` is missing or does not reference a declared gold file | Corpus contract test fails |
| Any v2-v2 corpus file produces fewer than two parent chunks | Corpus contract test fails |
| Dataset case version and corpus manifest version differ | Calibration is invalid; do not publish or merge scores |
| Required live dependency is unavailable | No formal score is recorded |
| Retrieval branch exceeds 30 seconds | Case records a branch timeout; calibration is infrastructure-contaminated |
| BGE response exceeds 120 seconds | Case records BGE degradation; calibration is infrastructure-contaminated |
| BGE succeeds but parent aggregation drops the gold child | Child miss with `DOCUMENT_PARENT_AGGREGATION_LOSS` |
| A layer has no structured gold cases | Every cutoff renders `N/A`; hit counters remain zero |
| A gold rank is greater than a requested cutoff | Miss at that cutoff, even when it hits at a larger cutoff |
| @10 is perfect while @1, @3, or @5 still misses | Preserve the score and emit `top10MasksLowerCutoff=true` plus the Markdown warning |

### 5. Good / Base / Bad Cases

- Good: a result matches the frozen filename, parent and child positions, and its own `childText` contains
  the stable anchor at rank one; all three document layers hit at every cutoff.
- Base: a v1 file-only label loads and contributes only to File Recall and legacy summary compatibility.
- Bad: a sibling child is returned and counted as a child hit because the full parent contains the anchor,
  three repetitions are counted as three quality cases, or a saturated @10 is presented without lower
  cutoffs as proof of perfect ranking.

### 6. Tests Required

- Dataset tests reject negative child positions and child-without-parent while preserving v1 loading.
- Matcher tests independently assert file, section and child ranks, strict-rank behavior and parent-text
  leakage prevention.
- Report tests assert unique-case deduplication, legacy `summary` compatibility, JSON serialization for
  flat @10 and typed cutoff fields, per-cutoff hit arithmetic, and the conditional Markdown warning.
- Corpus tests run the production `TextPreprocessor` and `ParentChildChunker`, prove all structured labels
  resolve uniquely across all eighteen files, and verify the source directory set, schema-2 roles,
  `hardNegativeFor` references, dataset/per-file hashes, cleaned length and parent/child counts. They also
  require every file to produce at least two parents and every dataset case to use the manifest version.
- Runner contract tests assert the v2 mode, document-only setup, calibration scope and both frozen timeout
  values; `bash -n scripts/run-document-v2-eval.sh` remains required.
- Profile tests assert the v2 project has isolated requirement/code collections.
- Delivery requires targeted tests, Java 21 `./mvnw -B verify`, Python comparison tests and compilation,
  shell syntax, task validation and `git diff --check`.

### 7. Wrong vs Correct

**Wrong:** count a candidate as a child hit because its `parentText` contains the expected phrase, then
multiply that hit by every timing repetition.

**Correct:** require the exact filename/parent/child position and anchor in the returned `childText`, use
one execution per case for quality, and retain all repetitions only for latency and dependency stability.

**Wrong:** count a BGE-ranked gold child as a final hit after parent aggregation replaced it with a sibling,
or publish a single CPU run as a stable latency gate.

**Correct:** record `DOCUMENT_PARENT_AGGREGATION_LOSS`, keep Child Recall strict, and label the single-run
latency as calibration-only.

**Wrong:** report only File/Section Recall@10 from a six-document corpus and describe both `1.0` values
as perfect document ranking.

**Correct:** preserve the compatible @10 fields, report File/Section/Child @1/@3/@5/@10 from the same
unique cases, emit the saturation warning, and require substantially more independent hard negatives
before treating @10 as a formal gate.

**Wrong:** add files beside the fixture without updating the manifest, point a hard negative at an
undeclared gold file, or publish v2-v1 scores under a v2-v2 corpus label.

**Correct:** require exact source/manifest set equality, valid `hardNegativeFor` references, at least two
production parents per file, and identical dataset/manifest versions before live calibration.

## Scenario: 0.8.2 child-first parent representative selection

### 1. Scope / Trigger

Apply this scenario when changing child-first reranking, parent aggregation, single-parent rerank
optimization, `SparseVectorizer`, or evidence child selection. It prevents shared parent context from
making an imprecise sibling the final evidence representative while preserving BGE's semantic parent
ordering.

### 2. Signatures

```java
double SparseVectorizer.similarity(String left, String right)

List<ChunkRecord> selectParentRepresentatives(
        String query,
        List<ChunkRecord> rankedChildren,
        boolean childFirstRerank)
```

The representative selector is internal to `RetrievalPipeline`. Spring injects the shared
`SparseVectorizer`; compatibility constructors used by focused tests create the same pure vectorizer.

### 3. Contracts

- In child-first mode, group ranked children by stable parent key in first-occurrence order. This order
  is the final parent order and must not be changed by child-only scoring.
- The first BGE sibling is the default representative. Compare siblings using only `query` and each
  candidate's own `childText`; never use shared `parentText`.
- Replace the current representative only when the child-only score gains at least `0.01` absolutely
  and is strictly greater than `currentScore * 1.10`. Ties and marginal gains preserve BGE's sibling.
- The single-parent shortcut must call the same selector before collapsing candidates, because skipping
  BGE must not force the first RRF child to become the evidence representative.
- Parent-first legacy mode keeps stable parent deduplication and does not apply child-only reselection.
- Selection is local and deterministic: no second external rerank call, case id, filename, gold anchor,
  or query-specific rule is allowed.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Child-first disabled | Keep legacy first parent occurrence |
| Multiple parents | Preserve BGE first-occurrence parent order |
| Sibling has absolute gain `< 0.01` | Keep current representative |
| Sibling is not more than `10%` better | Keep current representative |
| Sibling clears both thresholds | Replace representative |
| Equal child-only scores | Keep BGE's first sibling |
| Every candidate belongs to one parent | Select the representative before singleton rerank skip |
| Null/empty ranked children | Return an empty immutable list |

### 5. Good / Base / Bad Cases

- Good: BGE ranks parent A before parent B, then a clearly more query-relevant sibling becomes A's
  representative without moving A or B.
- Base: siblings tie or differ only marginally, so the first BGE sibling remains the representative.
- Bad: sort all children globally by sparse score, always replace on any positive delta, or keep the
  first RRF child in the single-parent shortcut without evaluating its siblings.

### 6. Tests Required

- `SparseVectorizerTest` asserts precise Chinese evidence scores above boilerplate and empty text/query
  produces zero similarity.
- `RetrievalPipelineTest` asserts significant sibling replacement without parent reordering, tie
  preservation, marginal-gain preservation, and single-parent shortcut selection.
- Run the frozen `document-v2-v2` live calibration with 24/24 successful BGE calls and zero degradation;
  compare File/Section/Child @1/@3/@5/@10 and strict MRR against the pre-optimization baseline.
- Run Java 21 `clean verify` and retain the existing evaluation, compatibility and JaCoCo gates.

### 7. Wrong vs Correct

**Wrong:** after BGE reranks enriched child-plus-parent passages, collapse each parent with unconditional
`putIfAbsent`; or replace the first BGE sibling for any tiny child-only score increase.

**Correct:** preserve BGE's parent order, keep its first sibling by default, and replace only when the
candidate's own child text clears both conservative gain thresholds.


## Scenario: Versioned requirement semantic graph hardening

### 1. Scope / Trigger
- Trigger: changing `com.example.requirementrag.requirement.graph.*`, requirement graph persistence, review APIs, or graph retrieval flags.
- The graph is an auxiliary projection. Dense+sparse Qdrant requirement retrieval remains the source of truth.

### 2. Signatures
```java
GraphSnapshot RequirementGraphBuildService.build(BuildRequest request)
SearchResponse RequirementGraphSearchService.search(SearchRequest request)
SearchResponse RequirementGraphHybridSearchService.search(SearchRequest request)
SearchResponse RequirementGraphHybridSearchService.search(SearchRequest request, QueryPlan plan)
QueryPlan RequirementGraphQueryPlanner.plan(SearchRequest request)
```

### 3. Contracts
- A graph snapshot is isolated by business project, document ID, requirement version, source revision, ontology version, and prompt version.
- Parent text is planned into bounded overlapping windows; every window stores exact start/end offsets, content hash, status, attempt count, and stable ID.
- Window output is persisted before the next model call. Retryable provider failures may retry within configured budgets; schema/evidence failures do not retry indefinitely.
- Claims use `ClaimStatus`; new production search defaults to `VERIFIED` claims and published/verified snapshots. Legacy schema-v1 snapshots remain readable through compatibility constructors.
- Evidence is first-class and includes quote, section path, absolute offsets, content hash, and `EvidenceResolutionStatus`. Unresolved evidence is visible through `GRAPH_EVIDENCE_UNAVAILABLE`.
- Claim→Evidence is normalized in `requirement_graph_claim_evidence` (snapshot_id, claim_id, evidence_id, support_type, confidence, created_at). Draft replacement rebuilds the association; evidence deletion cascades; publication gate reads the normalized table (falling back to legacy JSON `source_evidence_ids` for old snapshots) so dangling evidence cannot be published.
- Publication requires all entity/relation claims to be `VERIFIED` and every stored evidence span to be `RESOLVED`; publication records actor, reason, and audit entry.
- Build failure produces stable codes such as `GRAPH_WINDOW_FAILED`, `GRAPH_PARTIAL_FAILURE`, `GRAPH_MODEL_TIMEOUT`, `GRAPH_SCHEMA_INVALID`, and `GRAPH_PUBLICATION_BLOCKED`.
- `RequirementGraphController.search` is the unified entry: `NAIVE` returns raw Qdrant text blocks only; `LOCAL/GLOBAL` dispatch to graph neighborhood/global relation search; `HYBRID` and `MIX` dispatch to `RequirementGraphHybridSearchService`; `MIX` is never inferred by the planner.
- `MIX` fuses text blocks (Qdrant dense+sparse RRF), entities, relations, one/multi-hop paths, and evidence through configurable weights (`app.rag.requirement-graph.fusion.*`, default 0.30/0.20/0.15/0.15/0.15/0.05). The returned `SearchResponse` carries real `sourceChunks`, `paths`, `entities`, `relations`, `evidence`, and `channelScores`.
- Multi-snapshot isolation: `requirement_graph_window` uses `(snapshot_id, id)` and `requirement_graph_window_result` uses `(snapshot_id, window_id)` composite primary keys; `requirement_graph_evidence` uses `(snapshot_id, evidence_id)`, and `requirement_graph_claim_evidence` references it with a composite foreign key. Existing single-key databases are rebuilt by automatic migration on startup.
- Every SQLite business connection enables `PRAGMA foreign_keys=ON`, so cascades and referential integrity actually execute. Draft graph data is saved atomically via `saveDraftSnapshot` (snapshot → evidence → entity/relation → claim_evidence → uncertainty/conflict in one transaction, evidence before claim_evidence); deleting a snapshot cascades to windows, evidence, and claim_evidence.
- MIX text scores are linked to graph claims by parent block key (`filename|parentId|parentOrder|contentHash`) rather than comparing chunk IDs with span evidence IDs, so a high-scoring text block raises the fused score of entities/relations whose evidence comes from the same parent.
- Evidence IDs are span-level and authoritative from the build; the retrieval layer must not fabricate parent-block-level Evidence IDs or create empty-field placeholder Evidence for missing spans. Missing spans surface only through `GRAPH_EVIDENCE_UNAVAILABLE`; legacy parent-level evidence remains readable compatibly.
- `SearchResponse.explanations` explains every returned MIX candidate: matched channels, `scoreBreakdown` per channel plus `final`, related evidence IDs, and a readable reason, so ranking is debuggable.
- Text retrieval failures are explicit: `GRAPH_TEXT_NO_HITS` (normal empty), `GRAPH_TEXT_RETRIEVAL_UNAVAILABLE`, and `GRAPH_TEXT_RETRIEVAL_TIMEOUT` warnings instead of silent empty success. MIX degrades to graph results with a warning; NAIVE reports degraded status.
- `QueryPlan` drives `MIX` execution (allowed statuses, hops, per-channel caps, entity/relation keywords, section keywords). MIX uses a single unified candidate list across text/entity/relation/path/evidence channels, fuses and stably sorts (`finalScore DESC, type ASC, id ASC`), then paginates once — per-channel pagination is forbidden because it causes cross-page repeats and lost evidence.
- Published snapshots are read-only: `build`, `resume`, and every graph-data write entry reject `PUBLISHED` snapshots with `GRAPH_SNAPSHOT_IMMUTABLE`; `VERIFIED/REVIEW_REQUIRED` snapshots are not valid resume targets (a new build must be started instead).
- Snapshot identity is content/config identity (project, document, version, source revision, prompt/ontology/schema) and is decoupled from `buildId` (a single build-task identifier). Rebuilding identical input reuses the existing snapshot idempotently; existing legacy v2 IDs are adopted by scope lookup so the unique constraint is not violated.
- Deterministic relation gates reject self-loops and duplicate `(source, type, target)` relations after evidence-quote validation, before they can enter the graph.
- `maxEstimatedTokens` is enforced during window extraction: reaching the budget stops further model calls (`GRAPH_BUDGET_EXCEEDED`).
- Asynchronous jobs: a restarted QUEUED job without a snapshot may be re-queued from its persisted request; cancelling during a plain runtime exception keeps the persisted `CANCELLED` state.
- Logs and persisted audit data contain IDs, status, actor, duration, and safe error codes only; never raw model responses, credentials, or unbounded requirement text.

### 4. Validation & Error Matrix
| Condition | Required result |
| --- | --- |
| Quote is not resolvable in a window | Store `UNAVAILABLE`; block publication |
| Source revision or ontology/prompt version changes | Build a new snapshot; old published snapshot remains readable |
| Retryable model timeout/rate limit/transient 5xx | Retry within `maxRetries`, `maxModelCalls`, and wall-clock budget |
| Non-retryable schema/evidence error | Mark window failed with a stable code |
| Any incomplete window with partial disabled | `GRAPH_PARTIAL_FAILURE`; no publication |
| Entity/relation is edited | Claim returns to `EXTRACTED`; actor/reason are audited |
| Published claim is edited | Reject; create a new draft/rebuild |
| Search sees unresolved claims without `includeUnresolved=true` | Exclude them |
| Graph rows exceed `maxGraphRows` | Return bounded results with `GRAPH_RESULT_TRUNCATED` |
| MIX has no text/graph hits | Return empty channels with explicit warnings, never invent claims |
| Text retrieval service timeout/unavailable | Return `GRAPH_TEXT_RETRIEVAL_TIMEOUT`/`GRAPH_TEXT_RETRIEVAL_UNAVAILABLE` warning; MIX still returns graph results, NAIVE degrades |
| Estimated tokens exceed `maxEstimatedTokens` | Stop further model calls and mark remaining windows `GRAPH_BUDGET_EXCEEDED` |
| Verified claim references absent evidence | `GRAPH_EVIDENCE_MISSING` publication blocker from normalized table |

### 5. Good / Base / Bad Cases
- Good: repeated names in different sections keep separate context keys unless deterministic evidence supports a merge; all published claims have resolved spans; MIX response re-ranks entities/relations whose evidence appears in top text chunks.
- Base: legacy parent-only evidence remains readable as `LEGACY_PARENT_ONLY` and is not treated as verified new evidence; NAIVE serves raw text blocks without graph claims.
- Bad: silently truncating the tail, merging same-name entities solely by spelling, publishing extracted claims, converting evidence lookup failures to empty successful text, or fabricating `sourceChunks`/`paths` that were never retrieved.

### 6. Tests Required
- Window tests assert boundary selection, overlap, tail coverage, Unicode text, and stable resume IDs.
- Extraction tests assert enum/schema/endpoint/confidence validation and exact quote rejection.
- Store tests assert schema migration, atomic replacement, window result resume, evidence persistence, normalized claim_evidence association, claim review, audit, publication blocking, two identical-chunk snapshots keeping independent windows/evidence, snapshot delete cascades, and FK rejection of dangling claim_evidence.
- Controller tests assert business-project authorization for build, claims, review, search, audit, publish, and that `MIX` routes through the hybrid service with `plan` attached.
- Retrieval tests assert verified-status filtering, hybrid pagination, truncation warnings, evidence degradation, NAIVE text-only behavior, MIX fused channels/paths/channelScores, text-evidence parent reordering, text-channel failure warnings, per-channel page isolation, and token-budget stop.
- Job tests assert QUEUED-without-snapshot requeue on resume and that a plain runtime exception during cancel keeps `CANCELLED`.

### 7. Wrong vs Correct
**Wrong:** truncate a parent with `substring(0, maxInputChars)` and publish the highest-confidence relation.

**Correct:** create overlapping offset-addressable windows, persist each result, retain uncertainty/conflict claims, require verified claims and resolved evidence at publication, and keep the previous published snapshot immutable.


## Model Topology（模型拓扑，易混淆项——请勿再搞混）

> 反复出现的坑：把 BGE 当嵌入模型。实际拓扑如下，检索/向量代码里见到 `EmbeddingModel` 一律按主嵌入理解。

- **向量化 Embedding（主嵌入）＝ OpenAI 兼容网关**：`spring.ai.embedding=openai`，
  `options.model=${OPENAI_EMBEDDING_MODEL:text-embedding-v4}`，
  base-url `http://ai-gateway.momo.com`（LiteLLM）。
  `EmbeddingConfiguration.primaryEmbeddingModel(@Qualifier("openAiEmbeddingModel"))` 是 `@Primary` 主 bean——
  所有 `EmbeddingModel` 注入点（QdrantHybridStore、KnowledgeClaimVectorBuildService、适配器）都走它。
  实测 `POST /v1/embeddings {model:text-embedding-v4}` → 200，1024 维。
- **重排器 Reranker ＝ 本地 BGE（端口 8081）**：`BGE_RERANK_URL=http://localhost:8081`，`/rerank`，
  `tools/start-bge-reranker.sh` / `.venv-bge-reranker`。`scripts/nexus.sh status` 里的
  `BGE: 未运行（可降级）` 行指的是**这个重排器**，与嵌入无关，只影响 rerank 阶段，不影响向量化。
- **Ollama 里的 bge-m3 只是可选的备选嵌入路径**（`OLLAMA_EMBEDDING_MODEL=bge-m3`），不是主 bean，
  默认 `AI_MODEL_EMBEDDING=openai` 不启用它。
- **其他模型**：GENERATION_MODEL=claude-sonnet-5、LLM_RERANK_MODEL=claude-sonnet-4.6、
  ANNOTATION_MODEL=gpt-5.6-sol、REQUIREMENT_GRAPH_EXTRACTION_MODEL=deepseek-v4-flash——均为 LLM 调用，不是嵌入。
- 排查嵌入问题时：看网关 `ai-gateway.momo.com`（`/v1/embeddings`，需 `OPENAI_API_KEY`），
  **不要**看 `:8081`（那是重排器）也**不要**看 Ollama bge-m3（那是备选）。


### Requirement graph operational additions

- Asynchronous graph jobs use `POST /api/requirement-graphs/builds`, `GET /builds/{buildId}`, `POST /builds/{buildId}/resume`, and `POST /builds/{buildId}/cancel`; the original synchronous `/build` remains compatible.
- Review callers may use explicit `/claims/{claimId}/verify`, `/reject`, `/merge`, and `/split` aliases. Every mutation must resolve the claim's snapshot before authorization and append an audit record.
- `/snapshots/{snapshotId}/neighborhood/{entityId}` and `/snapshots/{snapshotId}/paths` return bounded graph data and explicit truncation/evidence warnings.
- `REQUIREMENT_GRAPH_PRIVACY_POLICY_REQUIRED=true` requires a matching `project-policies.<businessProjectId>` entry before model calls. Project policy cannot widen the global external-transmission ban.
- Metrics are tagged only with project and safe status values; requirement text, quotes, prompts, and model responses are never metric labels or log fields.

### Entity extraction and alias governance (0.9.7)

- Entity resolution chain is rule-first: confirmed alias → member/code-symbol name → single candidate → LLM restricted selection → `NEEDS_REVIEW` with candidates. The chain must never fabricate an `entityId`; unknown LLM names are resolve-or-drop (analyzer), not hard-rejected in the validator.
- Alias rows carry `origin` (`SOURCE_EXPLICIT / RULE_NORMALIZED / LLM_PROPOSED / HUMAN_CONFIRMED`) and `status` (`CONFIRMED / PROPOSED`). Only `CONFIRMED` aliases participate in exact matching (`findConceptIdsByAlias`, `findConfirmedAliasesMentionedIn`). LLM-proposed aliases are always `LLM_PROPOSED` + `PROPOSED` until reviewed.
- LLM proposals never write knowledge facts; values from LLM must not overwrite source original values. Relations proposed by LLM are saved as `matchMethod=LLM_PROPOSED`, `status=PROPOSED`, with confidence and evidence.
- SQLite `UNIQUE` treats NULLs as distinct: an `ON CONFLICT(<unique column list>)` upsert never matches when any conflict column is NULL. Use a deterministic NOT-NULL key (e.g. derived `context_id`) as the conflict target for idempotent re-resolve.
- Membership/alias text lookup uses `instr(?, name) > 0` (query text contains the name), always with a `limit` cap; never a full unscanned scan.

### Publish granularity and entity-layer published scope (0.9.7)

- `publishDocumentVersion`/`rollbackActiveVersion` demote the previous active **only when it is a new version of the same `document_id`** (replacement). Different documents under the same business version (parallel sources: case/data/qa/prd) stay PUBLISHED — they do not mutually demote.
- `knowledge_active_version` remains a single row per `(project_id, business_version)` and is the projection anchor for the Claim-vector layer only.
- Entity-layer published reads are **manifest-agnostic**: `findPublishedClaimsByProjectVersionAll`, `findPublishedClaimsByIdsAll`, `findPublishedEvidenceIdsByClaimIdsAll`, `findPublishedClaimVersions`, `findPublishedBusinessVersions`, `findPublishedDocumentVersionIds` filter on `status='PUBLISHED'` only, so all parallel-source documents of a version are aggregated into concepts/members and entity search.
- Claim-vector projection keeps the single-active binding (`publishedDocumentFilter()` + `findPublishedClaimsByIds`), unchanged: one active document per business version is projected.
- Rule of thumb: entity layer = all PUBLISHED docs; vector projection = active manifest single doc. Do not cross-use the filters.

### Local Qdrant & Claim-vector projection operations (0.9.7)

- **本地 Qdrant 用仓库内二进制，不依赖 Docker**：二进制在 `tools/qdrant`（来自 `tools/qdrant.tar.gz`，qdrant 1.15.4）；启动 `tools/qdrant-start.sh`，停止 `tools/qdrant-stop.sh`，数据目录项目根 `qdrant-storage/`（历史集合保留），端口 6333/6334。机器上无 docker/qdrant 公式时不要下载（brew 无公式），直接用仓库内二进制。
- **Claim 向量构建开关**（默认全关）：`KNOWLEDGE_CLAIM_VECTOR_ENABLED/BUILD_ENABLED/CANDIDATE_RETRIEVAL_ENABLED`；嵌入走网关 `text-embedding-v4`（1024 维，`/v1/embeddings`，`encoding_format=float`），网关**批量上限 10**（实测 8 OK/16 拒）——`EmbeddingBatcher.DEFAULT_BATCH_SIZE=8` 是刻意的，勿改大。
- **build-scope（0.9.7）**：`ACTIVE_DOC`（默认，active manifest 单文档，契约不变）与 `ALL_PUBLISHED`（全部已发布文档，与实体层同态，供图/向量增强召回的向量补召回）。scope 进入代际 manifest(`build_scope`) 与输入指纹（防跨 scope 误复用）；查询侧水化按 active 代际 scope 选查询。
- **Qdrant 点 ID 必须是无符号整数或标准 UUID**（v1.15+ 拒绝任意字符串，64-hex 报 400）。`deterministicPointId` 用 SHA-256 前 16 字节 → UUID v5 式；点 ID 算法属于投影 schema——`projectionSchemaVersion` 默认已是 `knowledge-claim-vector-v2`，换算法必须升 schema，禁止只改函数。
- SQLite 是事实权威：ALL_PUBLISHED 水化用 `findPublishedClaimsByIdsAll(projectId, businessVersion, claimIds)`（按文档关联业务版本收窄），不依赖可伪造/缺失的 Qdrant payload 版本字段。
- 真实构建量级参考（immortal 5.1）：ALL_PUBLISHED ≈ 201,186 条已发布 claim，8 条/批 × 网关 ≈ 4 小时串行。验证链路：`./mvnw test -Dtest=ImmortalClaimVectorBuildIT -Dimmortal.vector=true`（@SpringBootTest，需 Qdrant 起 + .env 密钥）。

### Optional recall modes (0.9.7, GRAPH_VECTOR/HYBRID)

- `RecallMode`：`DETERMINISTIC`（默认，规则链）/ `GRAPH_VECTOR`（解析 + 局部图一跳/二跳 + 可选向量补召回）/ `HYBRID`（并集）。命名借鉴图RAG思想但**不点名实现**；任何用户可见文案/注释/文档不得出现产品名。
- 图/向量只做**召回增强**：事实权威（代码/数值表优先级、类型化引用校验、发布边界）不变。图扩展与实体层同态（`findPublishedClaimIdsByIdsAll` / `isPublishedEvidenceAll`，全部 PUBLISHED 并行文档可见），不再绑 active manifest。
- **证据包基于合并实体集**（种子 + 图 + 向量命中映射实体）：`evidence.entities` = 合并水化结果，citations/factAssessment 同态；扩展事实的 subject/value/unit/代码摘录/Evidence 必须进 LLM 输入。
- **答案引用只认真实 Evidence ID**：`evidenceTypeById` 从**全部输出事实**（代码/数值表/测试/时间轴分区）的 evidenceIds 建立注册表（同 Claim 第二及后续证据也可引用）；Claim ID 一律不得进入允许集（模型引用 Claim ID → 整段回退模板）。
- 向量补召回覆盖**全部已发布业务版本**（确定性聚合的覆盖范围），逐版本检索去重；向量链路诊断（`CLAIM_VECTOR_NO_ACTIVE_GENERATION/SEARCH_FAILED/STALE_HITS/SCOPE_MISMATCH`）透传到响应 warnings。
- `entity-answer` 响应带完整召回包（`recall` 字段）：前端开启 AI 回答时只调一次接口完成检索+回答，禁止前端再单独调 entity-search 重复召回。

### Claim-vector 投影契约 fail-close 与证据注册（0.9.7 第四轮）

- **投影契约完整性（High）**：`findActiveGeneration` 按当前配置 `projectionSchemaVersion` 过滤——旧 schema 的 ACTIVE 代际（如 v1 点 ID 产物）不可被读取/检索；adapter 检索前校验 active 代际 schema == 配置（不等 → `CLAIM_VECTOR_SCHEMA_MISMATCH`，不查 Qdrant）；命中点五字段（projectionGenerationId / projectionSchemaVersion / embeddingModel / projectId / businessVersion）**必填且匹配**，任一缺失/不符即丢弃——不得按“字段为空则跳过”。payload 构造器对缺 schema/model 直接抛错（禁止回退默认版本）。
- **向量服务故障不得伪装为空命中（Med）**：`KnowledgeClaimVectorQdrantStore.search` 上抛异常，由 adapter 转 `CLAIM_VECTOR_SEARCH_FAILED` 稳定告警透传；可区分“确实无命中”与“向量服务不可用”。
- **关系/证据状态同态（Med）**：实体层关系状态（聚合器）与图扩展统一用 `isPublishedEvidenceAll`（全部 PUBLISHED 并行文档），禁止实体层混用 active-manifest 绑定的 `isPublishedEvidence`。
- **buildScope 配置入口（Med）**：application.yml 已绑定 `build-scope: ${KNOWLEDGE_CLAIM_VECTOR_BUILD_SCOPE:ACTIVE_DOC}`——环境变量与 `/build` API 显式 buildScope 两条入口同权；配置字段与文档契约一致。
- **证据注册表从全部输出事实建立（Med）**：`evidenceTypeById` 登记代码/数值表/测试/时间轴分区所有 FactRef 的**全部** evidenceIds（同 Claim 第二及后续证据也可引用）；只登记真实 Evidence（Claim ID 不入允许集），事实本身已由聚合器按项目/版本/发布过滤。

### Gameplay-card entity and vector granularity (0.9.7)

- `GameplayCardModuleResolver` owns the gameplay-card boundary described by the external generation prompt: one gameplay/system maps to one `canonical_module`; version pages, child/optimization pages, supporting tables, QA, and test cases use the same alignment anchor when their catalog category identifies the source.
- `BusinessConceptService`, code-symbol attachment, and Claim-vector block construction reuse that resolver. Atomic SQLite Claims and Evidence are never merged or overwritten; source page/table/module/parameter names remain aliases or members for traceability.
- Known catalog records use `GAMEPLAY_CARD` concepts keyed by the normalized `canonical_module`. Synthetic records without a catalog category retain the legacy `<module>.<subject>` key to avoid unverified cross-gameplay merges.
- Claim-vector blocks group by `sourceType + canonical_module`. One gameplay/system has one `GAMEPLAY_CARD` entity with no artificial member-count limit; atomic SQLite Claims and Evidence remain individually traceable. Qdrant blocks split only when deterministic text exceeds `block-max-chars`, which is a transport/retrieval payload boundary rather than an entity or fact-count rule. All matched code symbols remain attached to the card and independently searchable in the code index.
- 前端 `revokeRetrievalRequests()` 必须吊销实体请求（递增 entity.requestId + 释放 loading + resetEntityState），与 legacy/semantic/compare 同等对待。

### 关系证据端点绑定与版本边界（0.9.7 第五轮）

- **关系证据必须属于端点 Claim（High）**：关系状态校验使用 `isPublishedEvidenceForRelation(projectId, businessVersion, evidenceId, sourceClaimId, targetClaimId)`——除项目/版本/发布状态外，还必须通过 `knowledge_claim_evidence` 绑定到 source/target Claim 之一；仅“同项目+同版本+已发布”的任意 Evidence 不得当作关系证据（禁止把并行文档中无关证据判 CONFIRMED）。聚合器与图扩展必须统一用该方法；跨来源独立关系证据需单独建模并校验。
- **实体 API 版本边界**：前端实体检索/回答必须传页面版本 `versions:[retrieval.version]`（空数组 = 聚合全部已发布版本）；版本输入不得被丢弃。
- **多版本向量召回逐版本容错**：版本循环内逐版本独立 try/catch——单版本失败按 `VECTOR_RECALL_UNAVAILABLE:版本 X` 告警透传并保留其它版本命中；不得整体清空。
- 投影契约文档（0.9.6 development plan）任何时点必须与代码同步：点 ID = UUID（非 64-hex）、默认 schema v2、build-scope 配置项。

### 版本范围、关系证据与向量代际完整性（0.9.7 第六轮）

- **显式请求版本必须生效**：`EntityQueryService.search` 用 `request.versions()`（非空时）覆盖分析器从查询文本抽取的版本范围——前端版本输入 → 实体聚合/向量补召回/回答全部按此范围执行；空则沿用分析器推导。禁止“收下参数却不生效”。
- **关系证据端点绑定 + 文档/来源一致（High）**：`isPublishedEvidenceForRelation` 必须满足——Evidence 已发布（实体层同态）**且**通过 `knowledge_claim_evidence` 绑定 source/target Claim **且** `c.document_version_id=e.document_version_id` **且** `c.source_type=e.source_type`。Claim→代码符号关系（targetClaimId 为空，如 READS_CONFIG/VERIFIES）只绑定 source Claim 即可，不得强制两端都有 Claim ID（否则把有效关系误降级 UNVERIFIED）。
- **单点损坏 payload 只跳过该点**：`KnowledgeClaimVectorQdrantStore.search` 逐点解析失败记日志并 continue（保留同批合法命中）；仅响应结构本身损坏才整次失败（上抛 → adapter CLAIM_VECTOR_SEARCH_FAILED）。
- **向量代际与运行时嵌入身份完整校验**：adapter 校验（1）查询向量维度 == 代际构建维度（`CLAIM_VECTOR_EMBEDDING_DIMENSION_MISMATCH`）；（2）运行时 EmbeddingBatcher 模型指纹（class:dim，可用时）== 代际模型（`CLAIM_VECTOR_EMBEDDING_MODEL_MISMATCH`）——远端模型被替换而客户端类型/维度不变时也能识别；两者 fail-close 不查 Qdrant。
- **可复用代际必须物理集合仍存在**：`findReusableGeneration` 命中后需 `qdrantStore.collectionExists(physicalCollection)` 确认；物理集合缺失（运维删库/重建数据目录）→ 标记该代际 FAILED 并重建（避免 UNIQUE(scope+fingerprint+schema+model) 挡住 recordBuildStart）。
