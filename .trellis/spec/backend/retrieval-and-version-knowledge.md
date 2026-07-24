# Retrieval and Version Knowledge Contracts

> Executable backend contracts for unified evidence retrieval and reviewable version-knowledge drafts.

## 1. Scope / Trigger

Apply this specification when changing any of the following:

- `com.example.requirementrag.retrieval.pipeline.*`
- `DevelopmentPlanService` or `DevelopmentPlanStreamService` retrieval orchestration
- `VersionKnowledgeBuildPipeline` or `KnowledgeBuildController`
- `app.rag.wiki.*` storage configuration
- Wiki draft evidence, version comparison, review, or publication behavior

The platform version (for example `0.2.0-SNAPSHOT`) and a product requirement version (for example `5.1`) are separate identifiers and must never be inferred from one another.

## 2. Signatures

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
  "projectId": "immortal-game-service",
  "version": "5.1",
  "baseVersion": "5.0.2",
  "documentId": "fengshen",
  "baseCodeCommit": "optional commit",
  "codeCommit": "optional commit"
}
```

The endpoint requires `Permission.WRITE` and project access.

## 3. Contracts

### Retrieval outcome

- Use the existing `RagOutcome`, `RagOutcomeStatus`, `RagWarning`, and `RagStageDiagnostic` types. Do not create a parallel status model.
- Route through `QueryRouter` when no explicit project is supplied.
- Requirement evidence is deduplicated by `parentId`, falling back to `filename + parentOrder`.
- Code evidence is deduplicated by chunk ID, falling back to `filePath + symbolName + startLine`.
- Apply the configured/default limit after deduplication.
- `DevelopmentPlanService` and `DevelopmentPlanStreamService` must delegate retrieval orchestration to `RetrievalPipeline`; they only own generation and output formatting.

### Draft build and storage

- Read requirement-version payloads through `QdrantHybridStore.scrollVersion`; never request, serialize, or persist vectors.
- Compare target and base chunks with a source-aware key: normalized `filename + contentHash`, using a deterministic content hash only when the stored hash is absent.
- Write only below `${WIKI_DRAFT_PATH:data/wiki-drafts}/<project>/<version>/<buildId>/`.
- Generate `build.json` and `wiki-source.json` through a staging directory and atomically publish the completed draft directory.
- Do not write to `data/wiki-sources` or `data/wiki` from the build pipeline.
- Generated pages are `DRAFT`; feature review status is `PENDING_REVIEW`.
- Missing code or tests must be counted and exposed; absence must not be presented as verified evidence.
- `grow-fund` is reserved for growth fund / 成长基金. `grow-discount` is reserved for growth discount / 成长特价. Never merge these features automatically.

### Forbidden persisted fields

Draft JSON must not contain fields for vectors, embeddings, Qdrant points, snapshots, storage internals, API keys, passwords, secrets, tokens, authorization, or credentials. Evidence may contain only bounded text excerpts and traceable requirement/code metadata.

Environment keys:

```text
WIKI_ROOT_PATH=data/wiki
WIKI_SOURCE_PATH=data/wiki-sources
WIKI_DRAFT_PATH=data/wiki-drafts
```

## 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Required build field is blank | Bean validation rejects the request |
| Identifier contains path separators or traversal | Reject before any filesystem write |
| Unknown project | Use the existing project-registry error contract |
| Retrieval succeeds with evidence | `SUCCESS` |
| Retrieval succeeds with zero evidence | `NO_RESULTS` |
| One retrieval source fails but another has evidence | `DEGRADED` with a safe warning |
| A core retrieval source fails and no evidence remains | Throw `RagUnavailableException` |
| Qdrant version payload read fails | Return/throw stable public text `需求版本数据读取失败`; log internal cause only |
| Draft serialization contains a forbidden field | Abort the build and publish no draft directory |
| No version delta exists | Return `NO_CHANGES`; do not fabricate features |

Raw dependency exceptions, URLs, request payloads, credentials, and stack traces must never appear in public warnings or draft files.

## 5. Good / Base / Bad Cases

### Good

Target `5.1` contains a changed requirement file compared with `5.0.2`. The builder creates one reviewable draft feature with bounded requirement evidence, candidate code evidence, explicit missing-test status, and no vectors.

### Base

Target and base versions contain identical `filename + contentHash` entries. The result is `NO_CHANGES` with zero generated features.

### Bad

A request uses `version: "../../storage"`, or generated JSON contains an `embedding` field. The request/build is rejected and neither the formal Wiki nor a partial draft is written.

## 6. Tests Required

Changes to these contracts require assertions for:

- retrieval success, deduplication, and limit application
- normal zero-hit `NO_RESULTS`
- one-sided retrieval failure with `DEGRADED`
- no-evidence core failure with `RagUnavailableException`
- profile source selection, including requirement-only review
- base-version delta exclusion using filename and content hash
- stable, distinct `grow-fund` and `grow-discount` feature IDs
- path traversal rejection
- no writes to formal Wiki/source roots
- forbidden-field absence in serialized drafts
- Controller validation, project access, and `WRITE` permission
- Spring application-context binding for all `WikiProperties` fields

Run the full Java 21 verification before delivery:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify
git diff --check
```

## 7. Wrong vs Correct

**Wrong:** catch a Qdrant exception and return an empty list, then label the result `NO_RESULTS`.

**Correct:** preserve the failed stage diagnostic; return `DEGRADED` only when another evidence source remains, otherwise throw `RagUnavailableException` with a safe warning.

**Wrong:** copy Qdrant points or vectors into `wiki-source.json`, or publish drafts directly to `data/wiki`.

**Correct:** map payloads to bounded textual evidence, reject forbidden fields, write only to `data/wiki-drafts`, and require a separate human-reviewed publication step.
