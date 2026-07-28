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
```

Tools delegate to existing domain services. They must not create a parallel retrieval, evidence, Wiki, or version-comparison implementation.

### 3. Contracts

- Every result uses the outer fields `resolved`, `data`, `evidence`, `quality`, `warnings`, and `truncated`.
- `resolved` always contains the effective `projectId` and nullable `version`/`documentId`.
- Evidence is request-scoped, bounded, and projected without internal chunk IDs, local absolute paths, credentials, vectors, or storage internals.
- Lists are capped at 20 results, source reads at 200 lines, excerpts at 2,000 characters, evidence at 40 entries, and the serialized response at 120,000 characters by default.
- The `X-API-Key` header authenticates both REST and MCP through `ApiKeyAuthenticationService`; tool execution authorizes permissions and project scope through `ProjectAuthorizationService`.
- `nexus_development_plan` requires `OPERATE`; the other five tools require `PUBLIC_READ`.
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

### 5. Good / Base / Bad Cases

- Good: Codex or Cursor sends an environment-derived key, requests `pom.xml` lines 1–2, and receives a bounded result plus a repository-relative evidence reference.
- Base: a permitted client omits `projectId`; the configured default project is resolved and returned explicitly.
- Bad: a client requests `/etc/passwd`, `../secret`, `file:///tmp/secret`, or a repository symlink that points outside the root; the request is rejected and no content is returned.

### 6. Tests Required

- Unit tests assert shared authentication, permission and project authorization, caps, redaction, path validation, and total-response truncation.
- HTTP integration tests assert 401, initialize, six-tool discovery, JSON schemas, and a representative `tools/call`.
- Source tests assert both normal repository reads and symlink escape rejection.
- Release smoke tests use MCP Inspector plus current Codex and Cursor clients to call at least one evidence-bearing tool.
- Full `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify` remains green.

### 7. Wrong vs Correct

#### Wrong

Return domain objects directly, trust a normalized lexical path, duplicate authentication in the tool body, or embed an API key in `.codex/config.toml` / `.cursor/mcp.json`.

#### Correct

Project domain data into the bounded MCP envelope, validate the target's real path stays below the real repository root, reuse shared authentication/authorization services, and reference only `NEXUS_API_KEY` from checked-in client configuration.

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
