# Retrieval and Version Knowledge Contracts

> Executable backend contracts for unified evidence retrieval, reviewable version-knowledge drafts, version manifests, and multi-source comparison.

## 1. Scope / Trigger

Apply this specification when changing any of the following:

- `com.example.requirementrag.retrieval.pipeline.*`
- `DevelopmentPlanService` or `DevelopmentPlanStreamService` retrieval orchestration
- `VersionKnowledgeBuildPipeline` or `KnowledgeBuildController`
- `com.example.requirementrag.versioning.*` or `VersionController`
- `GitDiffService` or Git-based incremental indexing
- `WikiRepository` version-index access
- `app.rag.wiki.*` or `app.rag.versioning.*` storage configuration
- Wiki draft evidence, version comparison, review, or publication behavior

The NEXUS platform version (for example `0.3.0-SNAPSHOT`) and a product requirement version (for example `5.1`) are separate identifiers and must never be inferred from one another.

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
  "version": "5.1",
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

- `VersionComparisonService` requires both manifests and returns independent requirement, code, test, and Wiki sections plus safe warnings.
- Each source section must report `AVAILABLE` or `NOT_AVAILABLE`; missing data must not be represented as an empty successful diff.
- Requirement comparison uses the shared parent-chunk comparison and payload-only Qdrant reads.
- Code comparison uses `GitDiffService` and reports file-level added, modified, deleted, renamed changes and category counts. Do not describe this as AST or symbol-level analysis.
- `IncrementalCodeIndexService` and version comparison must reuse the same `GitDiffService` execution and parsing logic.
- Test comparison uses only real `TestSnapshot` values stored in manifests. Compare aggregate counts, run status, case additions/removals, and case status changes. Never infer execution results from suggested test points.
- Wiki comparison uses `WikiRepository.findIndex(projectId, version)` and compares page additions/removals, review status, summary, and evidence count.
- A missing non-critical source returns `NOT_AVAILABLE` and a warning while other sources continue. Manifest absence or invalid identifiers remain hard failures.
- Public warnings must use stable text and must not expose dependency exception messages, repository absolute paths, internal URLs, commands, or credentials.

### Forbidden persisted fields

Draft and manifest JSON must not contain fields for vectors, embeddings, Qdrant points, snapshots, WAL or storage internals, API keys, passwords, secrets, tokens, authorization, or credentials. Evidence may contain only bounded text excerpts and traceable requirement/code metadata.

Environment keys:

```text
WIKI_ROOT_PATH=data/wiki
WIKI_SOURCE_PATH=data/wiki-sources
WIKI_DRAFT_PATH=data/wiki-drafts
VERSION_MANIFEST_ROOT_PATH=data/version-manifests
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
| Draft or manifest serialization contains a forbidden field | Abort the write and publish no partial output |
| No requirement version delta exists | Return `NO_CHANGES`; do not fabricate features |
| A comparison source lacks references or data | Mark that section `NOT_AVAILABLE` and add a safe warning |
| Either version manifest does not exist | Return the stable manifest-not-found error |
| Git commit is not a concrete SHA | Reject before starting Git |
| Test snapshot contains duplicate `caseId` | Reject the manifest |

Raw dependency exceptions, URLs, request payloads, credentials, absolute paths, and stack traces must never appear in public warnings or generated files.

## 5. Good / Base / Bad Cases

### Good

Target version `5.1` changes one requirement parent block, references a concrete Git commit, stores a real test snapshot, and has a Wiki index. The comparison reports each source independently with traceable, bounded evidence and no vectors.

### Base

Target and base versions contain identical parent keys and content hashes. Requirement comparison returns an available result with zero changes; it does not fabricate candidate features.

### Bad

A request uses `version: "../../storage"`, a Git value such as `HEAD;rm`, duplicate test case IDs, or generated JSON contains an `embedding` field. The request is rejected and no partial draft or manifest is published.

## 6. Tests Required

Changes to these contracts require assertions for:

- retrieval success, deduplication, and limit application
- normal zero-hit `NO_RESULTS`
- one-sided retrieval failure with `DEGRADED`
- no-evidence core failure with `RagUnavailableException`
- profile source selection, including requirement-only review
- requirement comparison using `parentId`, with `filename + parentOrder` fallback and content-hash change detection
- different functions receiving distinct, stable feature IDs
- manifest save, update, list ordering, atomic replacement, path traversal rejection, Git SHA rejection, and duplicate test case rejection
- Git added, modified, deleted, renamed parsing and category counts
- test snapshot aggregate and case-level comparison, including missing-snapshot `NOT_AVAILABLE`
- Wiki page changes and missing-index warning behavior
- no writes to formal Wiki/source roots from draft build
- forbidden-field absence in serialized drafts and manifests
- Controller validation, project access, and permission requirements
- Spring application-context binding for `WikiProperties` and `VersioningProperties`

Run the full Java 21 verification before delivery:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify
git diff --check
```

## 7. Wrong vs Correct

**Wrong:** catch a Qdrant exception and return an empty list, then label the result `NO_RESULTS` or an available comparison with no changes.

**Correct:** preserve the failed-stage diagnostic; return `DEGRADED` only when another evidence source remains, otherwise use the stable public failure contract.

**Wrong:** copy Qdrant points or vectors into `wiki-source.json` or a version manifest, publish drafts directly to `data/wiki`, or treat suggested tests as passed execution results.

**Correct:** map payloads to bounded textual evidence, reject forbidden fields, keep drafts and manifests in their dedicated roots, and require real test snapshots for test-result comparison.
