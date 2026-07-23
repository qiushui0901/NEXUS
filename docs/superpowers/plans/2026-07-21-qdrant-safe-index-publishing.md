# Qdrant Safe Index Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Protect the current RAG data with snapshots, publish AST code indexes through versioned Qdrant collections and an atomic alias, prevent concurrent rebuilds, and block publication when integrity checks fail.

**Architecture:** A project-scoped coordinator owns mutual exclusion and run state. `CodeIndexPublisher` builds a new physical collection, reuses semantic annotation cache from the active alias, writes a complete AST index, runs `CodeIndexIntegrityAuditor`, snapshots the old collection, atomically switches the alias, snapshots the new collection, and retains the latest two published versions. A focused Qdrant administration client owns snapshot, alias, schema, count, and cleanup protocol calls.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring `RestClient`, JUnit 5, AssertJ, Mockito, Qdrant HTTP API, Maven.

## Global Constraints

- Preserve all pre-existing uncommitted work; stage only files named by the current task.
- Use `code_chunks_active` as the default stable alias and retain exactly two successfully published physical versions.
- Never switch the alias when scanning, annotation, writing, auditing, or the pre-switch snapshot fails.
- Keep failed build collections for diagnosis; do not count them as published versions.
- Use AST class/method chunks as the only publishable code-index format.
- Write a failing test and observe the expected failure before every production behavior change.
- Keep `/api/code/index` and `/api/code/v2/index` routes compatible; both must share the same project lock.
- The implementation must not delete `code_chunks_v2_temp` automatically.

---

### Task 1: Create and verify baseline Qdrant snapshots

**Files:**
- Create: `RAG_CHANGELOG.md`
- Runtime state: Qdrant collections `code_chunks`, `requirement_chunks`, `requirement_chunks_v2`

**Interfaces:**
- Consumes: Qdrant `POST /collections/{collection}/snapshots` and `GET /collections/{collection}/snapshots`.
- Produces: one visible snapshot per formal collection and an operation record containing snapshot names.

- [ ] **Step 1: Record current counts before mutation**

Run:

```bash
for c in code_chunks requirement_chunks requirement_chunks_v2; do
  curl -fsS "http://localhost:6333/collections/$c"
done
```

Expected: all three responses have `status=ok`; `code_chunks` has 12,760 points and both requirement collections have 17 points unless new data was intentionally added after this plan was written.

- [ ] **Step 2: Create snapshots**

Run:

```bash
for c in code_chunks requirement_chunks requirement_chunks_v2; do
  curl -fsS -X POST "http://localhost:6333/collections/$c/snapshots?wait=true"
done
```

Expected: every response has `status=ok` and returns a non-empty snapshot `name`.

- [ ] **Step 3: Verify snapshots are visible**

Run:

```bash
for c in code_chunks requirement_chunks requirement_chunks_v2; do
  curl -fsS "http://localhost:6333/collections/$c/snapshots"
done
```

Expected: each result array contains at least one snapshot with a positive size.

- [ ] **Step 4: Append the operation record**

Add a `v1.1.0` entry to `RAG_CHANGELOG.md` with the timestamp, collections, point counts, and exact snapshot names returned by Qdrant. Do not record credentials or local tokens.

- [ ] **Step 5: Commit only the operation record**

```bash
git add RAG_CHANGELOG.md
git commit -m "ops: snapshot current RAG collections"
```

Expected: the commit contains only `RAG_CHANGELOG.md`; Qdrant runtime files remain untracked or unstaged.

---

### Task 2: Add version naming, configuration, and project-scoped locking

**Files:**
- Create: `src/main/java/com/example/requirementrag/code/CodeCollectionNames.java`
- Create: `src/main/java/com/example/requirementrag/code/CodeIndexCoordinator.java`
- Create: `src/main/java/com/example/requirementrag/code/CodeIndexAlreadyRunningException.java`
- Create: `src/main/java/com/example/requirementrag/model/CodeIndexRunStatus.java`
- Modify: `src/main/java/com/example/requirementrag/config/RagProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/example/requirementrag/web/ApiExceptionHandler.java`
- Test: `src/test/java/com/example/requirementrag/code/CodeCollectionNamesTest.java`
- Test: `src/test/java/com/example/requirementrag/code/CodeIndexCoordinatorTest.java`
- Test: `src/test/java/com/example/requirementrag/web/ApiExceptionHandlerTest.java`

**Interfaces:**
- Produces: `CodeCollectionNames.activeAlias(String)`, `CodeCollectionNames.newVersion(String, Instant, String)`, `CodeIndexCoordinator.runExclusive(...)`, `CodeIndexCoordinator.tryRunBackground(...)`, and HTTP 409 mapping.
- Consumes: `RagProperties.Code.activeAlias`, `retainedVersions`, and `snapshotBeforePublish`.

- [ ] **Step 1: Write failing collection-name tests**

Create tests asserting:

```java
assertThat(names.activeAlias("code_chunks")).isEqualTo("code_chunks_active");
assertThat(names.activeAlias("code_chunks", "custom_active")).isEqualTo("custom_active");
assertThat(names.newVersion("code_chunks", Instant.parse("2026-07-21T07:30:00Z"), "ab12"))
        .isEqualTo("code_chunks_20260721_153000_ab12");
```

Inject `ZoneId.of("Asia/Shanghai")` into the test constructor so version names are deterministic.

- [ ] **Step 2: Run the naming test and verify RED**

```bash
mvn -q -Dtest=CodeCollectionNamesTest test
```

Expected: compilation fails because `CodeCollectionNames` does not exist.

- [ ] **Step 3: Implement collection naming and code configuration**

Add these fields to `RagProperties.Code` after `collection`:

```java
String activeAlias,
int retainedVersions,
boolean snapshotBeforePublish,
```

Add resolvers:

```java
public String resolvedActiveAlias() {
    return activeAlias == null || activeAlias.isBlank() ? collection + "_active" : activeAlias.trim();
}

public int resolvedRetainedVersions() {
    return retainedVersions < 2 ? 2 : retainedVersions;
}
```

Configure:

```yaml
active-alias: ${CODE_QDRANT_ACTIVE_ALIAS:code_chunks_active}
retained-versions: ${CODE_QDRANT_RETAINED_VERSIONS:2}
snapshot-before-publish: ${CODE_QDRANT_SNAPSHOT_BEFORE_PUBLISH:true}
```

`CodeCollectionNames` must validate Qdrant-compatible non-blank names and format new versions with `yyyyMMdd_HHmmss` plus a lowercase alphanumeric suffix.

- [ ] **Step 4: Verify naming GREEN**

```bash
mvn -q -Dtest=CodeCollectionNamesTest test
```

Expected: PASS.

- [ ] **Step 5: Write failing coordinator tests**

Cover the real concurrency contract:

```java
var firstEntered = new CountDownLatch(1);
var releaseFirst = new CountDownLatch(1);
var first = executor.submit(() -> coordinator.runExclusive("game", "manual", status -> {
    firstEntered.countDown();
    releaseFirst.await();
    return "done";
}));
assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
assertThatThrownBy(() -> coordinator.runExclusive("game", "manual", status -> "second"))
        .isInstanceOf(CodeIndexAlreadyRunningException.class);
releaseFirst.countDown();
assertThat(first.get()).isEqualTo("done");
```

Also prove different projects can run concurrently, exceptions release the lock, and `tryRunBackground` returns `false` instead of throwing when the project is busy.

- [ ] **Step 6: Run coordinator tests and verify RED**

```bash
mvn -q -Dtest=CodeIndexCoordinatorTest test
```

Expected: compilation fails because coordinator types do not exist.

- [ ] **Step 7: Implement coordinator and immutable run status**

Use a `ConcurrentHashMap<String, ActiveRun>` and atomic `putIfAbsent`, not a check-then-put sequence. The public API is:

```java
public <T> T runExclusive(String projectId, String trigger,
                          CheckedIndexOperation<T> operation) throws Exception;
public boolean tryRunBackground(String projectId, String trigger,
                                CheckedIndexOperation<?> operation);
public Optional<CodeIndexRunStatus> current(String projectId);
public List<CodeIndexRunStatus> currentRuns();
```

`CodeIndexRunStatus` contains `projectId`, `runId`, `trigger`, `phase`, `sourceCollection`, `targetCollection`, `startedAt`, `finishedAt`, `published`, `expectedChunks`, `actualPoints`, `failures`, and `warnings`. An operation receives a mutable internal progress handle; callers only see immutable snapshots.

- [ ] **Step 8: Map duplicate manual requests to HTTP 409**

Add:

```java
@ExceptionHandler(CodeIndexAlreadyRunningException.class)
ProblemDetail handleCodeIndexAlreadyRunning(CodeIndexAlreadyRunningException exception) {
    ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    detail.setProperty("projectId", exception.projectId());
    detail.setProperty("runId", exception.runId());
    detail.setProperty("phase", exception.phase());
    return detail;
}
```

Write a direct handler test asserting status 409 and all three properties.

- [ ] **Step 9: Run focused and config-binding tests**

```bash
mvn -q -Dtest=CodeCollectionNamesTest,CodeIndexCoordinatorTest,ApiExceptionHandlerTest test
```

Expected: PASS.

- [ ] **Step 10: Commit the locking slice**

```bash
git add src/main/java/com/example/requirementrag/code/CodeCollectionNames.java \
  src/main/java/com/example/requirementrag/code/CodeIndexCoordinator.java \
  src/main/java/com/example/requirementrag/code/CodeIndexAlreadyRunningException.java \
  src/main/java/com/example/requirementrag/model/CodeIndexRunStatus.java \
  src/main/java/com/example/requirementrag/config/RagProperties.java \
  src/main/resources/application.yml \
  src/main/java/com/example/requirementrag/web/ApiExceptionHandler.java \
  src/test/java/com/example/requirementrag/code/CodeCollectionNamesTest.java \
  src/test/java/com/example/requirementrag/code/CodeIndexCoordinatorTest.java \
  src/test/java/com/example/requirementrag/web/ApiExceptionHandlerTest.java
git commit -m "feat: serialize project code index runs"
```

---

### Task 3: Encapsulate Qdrant snapshot, alias, schema, and retention operations

**Files:**
- Create: `src/main/java/com/example/requirementrag/code/QdrantCodeIndexAdmin.java`
- Create: `src/main/java/com/example/requirementrag/model/QdrantCollectionInfo.java`
- Create: `src/main/java/com/example/requirementrag/model/QdrantSnapshotInfo.java`
- Test: `src/test/java/com/example/requirementrag/code/QdrantCodeIndexAdminTest.java`

**Interfaces:**
- Produces: administrative methods used by the publisher and auditor.
- Consumes: the existing Qdrant `RestClient` bean and Qdrant HTTP JSON contracts.

- [ ] **Step 1: Write failing protocol tests with a local mock HTTP server**

Construct `RestClient.Builder`, bind `MockRestServiceServer`, then build the client. Test these exact requests:

```text
POST /collections/code_chunks/snapshots?wait=true
GET  /collections/code_chunks/snapshots
GET  /aliases
POST /collections/aliases
GET  /collections/code_chunks_20260721_153000_ab12
POST /collections/code_chunks_20260721_153000_ab12/points/count
DELETE /collections/code_chunks_older
```

The alias-switch test must verify a single request body containing both actions:

```json
{
  "actions": [
    {"delete_alias": {"alias_name": "code_chunks_active"}},
    {"create_alias": {"collection_name": "code_chunks_new", "alias_name": "code_chunks_active"}}
  ]
}
```

- [ ] **Step 2: Run the protocol test and verify RED**

```bash
mvn -q -Dtest=QdrantCodeIndexAdminTest test
```

Expected: compilation fails because `QdrantCodeIndexAdmin` does not exist.

- [ ] **Step 3: Implement the focused administration client**

Expose:

```java
public QdrantSnapshotInfo createSnapshot(String collection);
public List<QdrantSnapshotInfo> listSnapshots(String collection);
public Map<String, String> aliases();
public void ensureAlias(String alias, String collection);
public void switchAlias(String alias, String oldCollection, String newCollection);
public QdrantCollectionInfo collectionInfo(String collection);
public long exactProjectCount(String collection, String projectId);
public List<String> collections();
public void deleteCollection(String collection);
```

`exactProjectCount` must call the count endpoint with `exact=true` and a keyword filter on `projectId`. `collectionInfo` must parse collection status and require `code_dense`, `desc_dense`, and sparse vector name `sparse` without relying on `indexed_vectors_count` as a point count.

- [ ] **Step 4: Verify the administration client GREEN**

```bash
mvn -q -Dtest=QdrantCodeIndexAdminTest test
```

Expected: PASS and the mock server reports no outstanding expectations.

- [ ] **Step 5: Commit the Qdrant administration slice**

```bash
git add src/main/java/com/example/requirementrag/code/QdrantCodeIndexAdmin.java \
  src/main/java/com/example/requirementrag/model/QdrantCollectionInfo.java \
  src/main/java/com/example/requirementrag/model/QdrantSnapshotInfo.java \
  src/test/java/com/example/requirementrag/code/QdrantCodeIndexAdminTest.java
git commit -m "feat: add Qdrant index administration client"
```

---

### Task 4: Implement AST code-index integrity auditing

**Files:**
- Create: `src/main/java/com/example/requirementrag/code/CodeIndexIntegrityAuditor.java`
- Create: `src/main/java/com/example/requirementrag/model/CodeIndexAuditReport.java`
- Test: `src/test/java/com/example/requirementrag/code/CodeIndexIntegrityAuditorTest.java`

**Interfaces:**
- Consumes: AST `CodeChunk` values, scan file paths/count, target collection metadata, and exact Qdrant project count.
- Produces: `CodeIndexAuditReport.passed()`, failures, warnings, coverage ratios, expected/actual counts, and bounded examples.

- [ ] **Step 1: Write a fixture builder and failing happy-path test**

Build real `CodeChunk` records with class/method metadata. The happy path must assert:

```java
assertThat(report.passed()).isTrue();
assertThat(report.expectedChunks()).isEqualTo(2);
assertThat(report.actualPoints()).isEqualTo(2);
assertThat(report.astCoverage()).isEqualTo(1.0);
assertThat(report.descriptionCoverage()).isEqualTo(1.0);
assertThat(report.duplicateIds()).isEmpty();
```

- [ ] **Step 2: Write failing gate and warning tests**

Add one focused test for each condition:

- duplicate IDs fail and include the duplicate ID;
- actual point count mismatch fails;
- blank `symbolName`, `className`, or `filePath` fails;
- invalid `symbolType` fails;
- invalid line range fails;
- a successfully scanned file with no chunk fails;
- missing `code_dense`, `desc_dense`, or `sparse` schema fails;
- description coverage below 95% warns but does not fail when all hard gates pass;
- examples are capped at 20.

- [ ] **Step 3: Run auditor tests and verify RED**

```bash
mvn -q -Dtest=CodeIndexIntegrityAuditorTest test
```

Expected: compilation fails because auditor/report types do not exist.

- [ ] **Step 4: Implement the auditor as a pure service**

Use this API:

```java
public CodeIndexAuditReport audit(
        String projectId,
        List<String> scannedFiles,
        List<CodeChunk> chunks,
        QdrantCollectionInfo collection,
        long actualProjectPoints);
```

Do not make HTTP calls inside the auditor. Normalize file paths before coverage comparison. Count descriptions as covered only when both Chinese and English descriptions are non-blank. Keep all failure and warning ordering deterministic for stable tests and logs.

- [ ] **Step 5: Verify auditor GREEN**

```bash
mvn -q -Dtest=CodeIndexIntegrityAuditorTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the audit slice**

```bash
git add src/main/java/com/example/requirementrag/code/CodeIndexIntegrityAuditor.java \
  src/main/java/com/example/requirementrag/model/CodeIndexAuditReport.java \
  src/test/java/com/example/requirementrag/code/CodeIndexIntegrityAuditorTest.java
git commit -m "feat: audit AST code index integrity"
```

---

### Task 5: Publish versioned indexes and atomically switch the active alias

**Files:**
- Create: `src/main/java/com/example/requirementrag/code/CodeIndexPublisher.java`
- Create: `src/main/java/com/example/requirementrag/model/CodeIndexPublishResult.java`
- Modify: `src/main/java/com/example/requirementrag/code/CodeQdrantStore.java`
- Modify: `src/main/java/com/example/requirementrag/code/CodeKnowledgeService.java`
- Modify: `src/main/java/com/example/requirementrag/config/ProjectRegistry.java`
- Test: `src/test/java/com/example/requirementrag/code/CodeIndexPublisherTest.java`
- Test: `src/test/java/com/example/requirementrag/code/CodeKnowledgeServicePublishingTest.java`

**Interfaces:**
- Consumes: coordinator, AST scanner, annotator, active alias, `CodeQdrantStore`, admin client, and auditor.
- Produces: a published collection, atomic alias switch, snapshots, retained versions, and structured result.

- [ ] **Step 1: Write failing publisher ordering tests**

Use Mockito `InOrder` to prove the successful path executes:

```text
ensureAlias → createVersionCollection/write → audit → old snapshot → switchAlias → new snapshot → retention cleanup
```

Add focused tests proving:

- audit failure never invokes `switchAlias`;
- old snapshot failure never invokes `switchAlias`;
- post-switch snapshot failure sets `PUBLISHED_WITH_SNAPSHOT_ERROR` and never deletes an old version;
- retention keeps the alias target and one immediately previous successful version;
- failed-build collection is not deleted;
- annotation cache is read from the active collection, not the empty target collection.

- [ ] **Step 2: Run publisher tests and verify RED**

```bash
mvn -q -Dtest=CodeIndexPublisherTest,CodeKnowledgeServicePublishingTest test
```

Expected: compilation fails because publisher/result types do not exist.

- [ ] **Step 3: Add explicit physical-collection operations to the store**

Expose narrowly scoped methods rather than leaking the client:

```java
public void prepareEmptyCollection(String collection);
public void writeCompleteProject(String collection, String projectId, List<CodeChunk> chunks);
public Map<String, AnnotationEntry> fetchAnnotationCache(String collection, String projectId);
public List<CodeChunk> sampleWithVectors(String collection, String projectId, int limit);
```

`prepareEmptyCollection` must fail if the target already exists; it must never delete or recreate an existing collection. Keep the current schema creation in one shared private method.

- [ ] **Step 4: Implement the publisher orchestration**

`CodeIndexPublisher.publishAst(...)` must:

```java
String base = resolvedBaseCollection(projectId);
String alias = resolvedActiveAlias(projectId);
admin.ensureAlias(alias, base);
String current = admin.aliases().get(alias);
String target = names.newVersion(base, clock.instant(), suffixSupplier.get());
Map<String, AnnotationEntry> cache = force ? Map.of() : store.fetchAnnotationCache(current, projectId);
List<CodeChunk> annotated = annotator.annotateWithCache(scan.chunks(), cache);
store.prepareEmptyCollection(target);
store.writeCompleteProject(target, projectId, annotated);
CodeIndexAuditReport audit = auditor.audit(projectId, scan.filePaths(), annotated,
        admin.collectionInfo(target), admin.exactProjectCount(target, projectId));
if (!audit.passed()) return CodeIndexPublishResult.rejected(target, audit);
QdrantSnapshotInfo oldSnapshot = admin.createSnapshot(current);
admin.switchAlias(alias, current, target);
QdrantSnapshotInfo newSnapshot = admin.createSnapshot(target);
cleanupPublishedVersions(base, alias, retainedVersions);
return CodeIndexPublishResult.published(target, audit, oldSnapshot, newSnapshot);
```

If the scan result currently exposes only a file count, extend it to include the normalized successful file list required by auditing without changing existing callers' count semantics.

- [ ] **Step 5: Route full AST indexing through the coordinator and publisher**

Keep route compatibility:

- `/api/code/v2/index` always calls the safe AST publisher;
- `/api/code/index` calls the safe AST publisher when the configured active collection contains AST data, matching the current deployed behavior;
- both paths acquire the same `projectId` lock;
- search, graph, status, health checks, and cache reads resolve the active Alias rather than appending `_v2`;
- legacy physical collection access remains available only through explicit administrative rollback, not normal retrieval.

- [ ] **Step 6: Verify focused publisher tests GREEN**

```bash
mvn -q -Dtest=CodeIndexPublisherTest,CodeKnowledgeServicePublishingTest test
```

Expected: PASS.

- [ ] **Step 7: Commit the atomic publishing slice**

```bash
git add src/main/java/com/example/requirementrag/code/CodeIndexPublisher.java \
  src/main/java/com/example/requirementrag/model/CodeIndexPublishResult.java \
  src/main/java/com/example/requirementrag/code/CodeQdrantStore.java \
  src/main/java/com/example/requirementrag/code/CodeKnowledgeService.java \
  src/main/java/com/example/requirementrag/config/ProjectRegistry.java \
  src/test/java/com/example/requirementrag/code/CodeIndexPublisherTest.java \
  src/test/java/com/example/requirementrag/code/CodeKnowledgeServicePublishingTest.java
git commit -m "feat: publish versioned code indexes atomically"
```

---

### Task 6: Integrate health checks, monitoring, and API responses

**Files:**
- Modify: `src/main/java/com/example/requirementrag/knowledge/DataHealthChecker.java`
- Modify: `src/main/java/com/example/requirementrag/web/CodeController.java`
- Modify: `src/main/java/com/example/requirementrag/web/MonitorController.java`
- Modify: `src/main/java/com/example/requirementrag/model/MonitorSnapshot.java`
- Modify: `src/main/resources/static/monitor.html`
- Test: `src/test/java/com/example/requirementrag/knowledge/DataHealthCheckerTest.java`
- Test: `src/test/java/com/example/requirementrag/web/CodeControllerPublishingTest.java`
- Test: `src/test/java/com/example/requirementrag/web/MonitorWorkbenchPageTest.java`

**Interfaces:**
- Consumes: coordinator run states and publish results.
- Produces: no duplicate automatic rebuilds, HTTP 409 for duplicate manual runs, and visible audit/publish state.

- [ ] **Step 1: Write failing health-check concurrency tests**

Prove `checkOnStartup()` does not launch another index when `tryRunBackground` returns `false`, records `SKIPPED_ALREADY_RUNNING`, and still reports requirement health independently.

- [ ] **Step 2: Write failing controller/monitor tests**

Assert:

- `/api/code/index` and `/api/code/v2/index` delegate to the same safe publisher path;
- `GET /api/code/index/status` exposes the active run and last audit;
- `POST /api/code/index/rollback` accepts `projectId` and a retained physical `collection`, requires `WRITE`, verifies the collection is a published retained version with a visible snapshot, and atomically switches the alias;
- monitor HTML renders active alias, physical target, audit pass/fail, point counts, warnings, and snapshot names;
- no UI action performs a destructive collection delete.

- [ ] **Step 3: Run focused tests and verify RED**

```bash
mvn -q -Dtest=DataHealthCheckerTest,CodeControllerPublishingTest,MonitorWorkbenchPageTest test
```

Expected: tests fail because health checks bypass the coordinator and monitor contracts lack publish state.

- [ ] **Step 4: Integrate background locking and monitoring**

Replace direct virtual-thread calls with `coordinator.tryRunBackground(projectId, "data-health", ...)`. Extend monitor responses with immutable run/audit data. Keep the existing health history, adding a reason field rather than replacing current fields. Add `GET /api/code/index/status` and the protected `POST /api/code/index/rollback` to `CodeController`; rollback rejects unknown, failed-build, snapshot-less, and current-target collections with HTTP 400.

- [ ] **Step 5: Verify focused tests GREEN**

```bash
mvn -q -Dtest=DataHealthCheckerTest,CodeControllerPublishingTest,MonitorWorkbenchPageTest test
```

Expected: PASS.

- [ ] **Step 6: Commit integration**

```bash
git add src/main/java/com/example/requirementrag/knowledge/DataHealthChecker.java \
  src/main/java/com/example/requirementrag/web/CodeController.java \
  src/main/java/com/example/requirementrag/web/MonitorController.java \
  src/main/java/com/example/requirementrag/model/MonitorSnapshot.java \
  src/main/resources/static/monitor.html \
  src/test/java/com/example/requirementrag/knowledge/DataHealthCheckerTest.java \
  src/test/java/com/example/requirementrag/web/CodeControllerPublishingTest.java \
  src/test/java/com/example/requirementrag/web/MonitorWorkbenchPageTest.java
git commit -m "feat: expose safe code index publishing status"
```

---

### Task 7: Verify migration, rollback, and full quality gate

**Files:**
- Modify: `RAG_CHANGELOG.md`

**Interfaces:**
- Consumes: running Qdrant, Ollama embedding service, source repository, and the built application.
- Produces: a protected active alias, a successful audit report, two-version rollback, and a clean test result.

- [ ] **Step 1: Run all focused tests together**

```bash
mvn -q -Dtest=CodeCollectionNamesTest,CodeIndexCoordinatorTest,QdrantCodeIndexAdminTest,CodeIndexIntegrityAuditorTest,CodeIndexPublisherTest,CodeKnowledgeServicePublishingTest,DataHealthCheckerTest,CodeControllerPublishingTest,ApiExceptionHandlerTest,MonitorWorkbenchPageTest test
```

Expected: PASS.

- [ ] **Step 2: Run the complete test suite**

```bash
mvn test
```

Expected: `BUILD SUCCESS` with no failed tests.

- [ ] **Step 3: Build the application**

```bash
mvn -DskipTests package
```

Expected: `BUILD SUCCESS` and `target/NEXUS-0.0.1-SNAPSHOT.jar` exists.

- [ ] **Step 4: Create the initial active alias without rebuilding**

Start the verified application with `CODE_QDRANT_ACTIVE_ALIAS=code_chunks_active`. Verify:

```bash
curl -fsS http://localhost:6333/aliases
```

Expected: `code_chunks_active` points to the current `code_chunks` physical collection.

- [ ] **Step 5: Run one safe AST rebuild**

```bash
curl -fsS -X POST 'http://localhost:8080/api/code/v2/index?projectId=immortal-game-service&force=false'
```

Expected: the response includes `published=true`, a versioned target collection, a passing audit, and snapshot names. During the run, `code_chunks_active` continues pointing to the old collection.

- [ ] **Step 6: Verify live data and retention**

```bash
curl -fsS http://localhost:6333/aliases
curl -fsS http://localhost:6333/collections
curl -fsS http://localhost:8080/api/code/v2/status?projectId=immortal-game-service
```

Expected:

- alias points to the new versioned collection;
- exactly two successful versions are retained after a later second publication;
- audit shows 100% AST required-field coverage;
- Qdrant exact project count equals expected unique chunk IDs;
- old `code_chunks` remains available after the first publication;
- `code_chunks_v2_temp` is unchanged.

- [ ] **Step 7: Exercise rollback without regeneration**

Call `POST /api/code/index/rollback?projectId=immortal-game-service&collection=<previous-retained-version>` to switch the alias to the previous retained version. Execute a known code search, then call the same endpoint with the newer retained version to switch forward again. Both alias changes must be atomic and complete without embedding or annotation calls.

- [ ] **Step 8: Update the changelog with measured results**

Record version collection names, alias target, snapshots, expected and actual point counts, duplicate-ID count, AST coverage, description coverage, retained versions, rollback result, and test totals in `RAG_CHANGELOG.md`.

- [ ] **Step 9: Run diff and repository checks**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; unrelated pre-existing changes remain unstaged and are explicitly excluded from the final commit.

- [ ] **Step 10: Commit verification records**

```bash
git add RAG_CHANGELOG.md
git commit -m "chore: verify safe Qdrant index publication"
```

- [ ] **Step 11: Perform final Trellis quality review**

Run the `trellis-check` workflow against the complete task scope. Resolve any spec drift, test failure, missing data-flow assertion, or untracked task-owned file before reporting completion.
