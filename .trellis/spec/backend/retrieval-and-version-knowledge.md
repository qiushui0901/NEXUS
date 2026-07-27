# Retrieval and Version Knowledge Contracts

> Executable backend contracts for unified evidence retrieval, reviewable version-knowledge drafts, version manifests, and multi-source comparison.

## 1. Scope / Trigger

Apply this specification when changing any of the following:

- `com.example.requirementrag.retrieval.pipeline.*`
- `DevelopmentPlanService` or `DevelopmentPlanStreamService` retrieval orchestration
- `VersionKnowledgeBuildPipeline` or `KnowledgeBuildController`
- `com.example.requirementrag.versioning.*` or `VersionController`
- `tools/build-requirement-snapshots.py` or `data/requirement-snapshots/**`
- `GitDiffService` or Git-based incremental indexing
- `WikiRepository` version-index access
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

## 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Required build or manifest field is blank | Bean/service validation rejects the request |
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

Raw dependency exceptions, URLs, request payloads, credentials, absolute paths, and stack traces must never appear in public warnings or generated files.

## 5. Good / Base / Bad Cases

### Good

A target business version has a published Wiki index and an explicit alias to a reviewable requirement snapshot. Its base commit matches a published baseline version. The resolver returns a bound requirement version and business baseline, and comparison reports traceable, bounded requirement evidence without reading vectors.

### Base

Target and base snapshots contain identical parent keys and content hashes. Requirement comparison returns an available result with zero changes; a no-op generator run preserves the existing `generatedAt` and produces byte-identical JSON.

### Bad

A request uses `version: "../../storage"`, a Git value such as `HEAD;rm`, duplicate snapshot entry IDs, an unreviewed version alias, or generated JSON contains an `embedding` field. The request is rejected or the version remains unbound; no partial artifact is published.

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
- Browser route, static page contract, navigation, deep-link parameter consumption, and HTML escaping
- Browser empty, loading, error, unavailable-source, and missing-real-test-snapshot states
- Spring application-context binding for `WikiProperties` and `VersioningProperties`

Run the full Java 21 verification before delivery:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify
git diff --check
```

## 7. Wrong vs Correct

**Wrong:** catch a Qdrant exception and return an empty list, then label the result `NO_RESULTS` or an available comparison with no changes.

**Correct:** preserve the failed-stage diagnostic; return `DEGRADED` only when another evidence source remains, otherwise use the stable public failure contract.

**Wrong:** infer a requirement baseline from a nearby-looking business version, treat each incremental document as a complete list, infer removal from absence or正文关键词, copy Qdrant points or vectors into a snapshot, or require local Qdrant data to display a committed historical requirement diff.

**Correct:** map business versions only through reviewed snapshot aliases, replay `baseRequirementVersion` into a cumulative state, require a structured `REMOVE` event for deletion, persist bounded text/hash/source facts, compare materialized snapshots first, and keep unmapped versions explicitly `NOT_AVAILABLE`.

**Wrong:** copy Qdrant points or vectors into `wiki-source.json` or a version manifest, publish drafts directly to `data/wiki`, or treat suggested tests as passed execution results.

**Correct:** map payloads to bounded textual evidence, reject forbidden fields, keep drafts and manifests in their dedicated roots, and require real test snapshots for test-result comparison.

**Wrong:** let the browser interpolate API text directly into `innerHTML`, or turn a missing source into a zero-change card.

**Correct:** escape every API-derived value, render `NOT_AVAILABLE` with a safe warning, and keep the other comparison tabs available.
