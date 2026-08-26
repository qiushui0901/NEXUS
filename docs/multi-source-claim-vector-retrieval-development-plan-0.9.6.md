# Nexus 0.9.6 Multi-Source Claim Vector Retrieval Development Plan

> **Version:** 0.9.6
>
> **Status:** Proposed
>
> **Baseline:** `f7037c6`
>
> **Target:** Add a rebuildable Qdrant projection for published multi-source Claims and integrate it into deterministic multi-source retrieval without changing SQLite's role as the source of truth.

---

## 1. Executive Summary

Nexus already stores multi-source knowledge in SQLite and exposes it through `UnifiedKnowledgeClaim`. The current retrieval path is deterministic and field-based. This works for exact business terms but loses recall when a user describes the same fact with different wording.

Version 0.9.6 adds a dedicated Qdrant index for eligible Claims:

```text
SQLite published Claims
  -> canonical typed retrieval text
  -> dense + sparse embeddings
  -> versioned Qdrant physical collection
  -> verified atomic alias switch
  -> hybrid recall
  -> SQLite hydration and governance
  -> deterministic fusion with existing candidates
```

The design follows one hard boundary:

> SQLite remains authoritative for facts, versions, status, evidence, relations, and audit. Qdrant is a disposable retrieval projection that must be reproducible from SQLite.

The first release indexes one point per eligible Claim. It does not independently index all Evidence rows, replace structured filters, or allow vector similarity to determine authority or truth.

---

## 2. Goals and Non-Goals

### 2.1 Goals

1. Improve semantic recall for Requirement, Parameter, Test Case, and Doubt Claims.
2. Keep project, business-version, publication-status, and authority isolation exact.
3. Publish complete index generations atomically through a dedicated Qdrant alias.
4. Hydrate vector hits from SQLite before scoring, conflict analysis, or response assembly.
5. Fuse vector candidates with existing deterministic adapters without duplicate Claim results.
6. Support shadow evaluation, controlled rollout, rollback, and complete generation diagnostics.
7. Validate the design against the current approximately 201,000-Claim corpus.

### 2.2 Non-Goals

1. Qdrant does not replace `MultiSourceKnowledgeStore`.
2. Evidence is not indexed as an independent point per row in 0.9.6.
3. Vector scores do not decide Claim status, authority, conflict resolution, or publication state.
4. The existing `requirement_chunks` and `code_chunks` collections are not merged into the new collection.
5. Cross-unit numeric conversion, graph traversal, and relationship inference remain structured operations.
6. 0.9.6 does not migrate SQLite to PostgreSQL.
7. The query path must not write Claims, Evidence, relations, or publication state.

---

## 3. Current Baseline

### 3.1 Reusable Components

| Component | Current responsibility | 0.9.6 use |
| --- | --- | --- |
| `MultiSourceKnowledgeStore` | Claim, Evidence, relation, version, and publication persistence | Authoritative projection input and hit hydration |
| `KnowledgeClaimRecord` | Persistent canonical Claim record | Source record for projection |
| `UnifiedKnowledgeClaim` | Retrieval-facing normalized Claim | Final hydrated candidate contract |
| `MultiSourceCandidateAdapter` | Adds derived sources to multi-source retrieval | Extension pattern for vector candidates |
| `MultiSourceSearchService` | Intent routing, filtering, scoring, conflicts, pagination | Candidate fusion and final governance |
| `QdrantHybridStore` | Dense/sparse points, physical collections, aliases, rollback | Reuse publishing primitives after making collection schema explicit |
| `EmbeddingBatcher` | Bounded dense embedding calls | Claim projection embedding |

### 3.2 Current Gaps

1. `findClaimsByProjectVersion(...)` can load Claims for a version, but no immutable projection manifest identifies the exact input set, model, schema, and text-composer version.
2. The existing Qdrant publisher accepts `ChunkRecord`, whose payload is requirement-chunk oriented. Claim indexing needs an explicit Claim point contract.
3. `knowledge_active_version` represents a published document version. A Claim projection can span many published source documents, so one `documentVersionId` is not a sufficient generation identity.
4. `MultiSourceSearchService` uses deterministic lexical scoring and does not consume vector scores.
5. Existing Evidence cardinality is much larger than Claim cardinality. Indexing every Evidence row would create duplicate-heavy Top-K results and unnecessary embedding cost.

---

## 4. Target Architecture

```text
                         Write / publish path

  MultiSourceKnowledgeStore
      published Claims + representative Evidence IDs
                    |
                    v
  KnowledgeClaimProjectionService
      eligibility -> canonical text -> fingerprint -> batching
                    |
                    v
  KnowledgeClaimVectorStore
      physical collection: knowledge_claims_live-<generation>
      validation: count + sample read + scope/fingerprint checks
                    |
                    v
      alias: knowledge_claims_live


                         Query path

  projectId + businessVersion + query + intent
                    |
         +----------+-----------+
         |                      |
         v                      v
  existing structured     Qdrant hybrid recall
  candidate adapters      from knowledge_claims_live
         |                      |
         |                 claimId + vector score
         |                      |
         |                 SQLite hydration
         |                      |
         +----------+-----------+
                    v
       deduplicate by claimId / governed fact identity
                    |
       deterministic fusion + source policy + conflicts
                    |
               paged response
```

The Qdrant query must always apply exact payload filters for:

```text
projectId == requested business project
businessVersion == requested business version
projectionStatus == PUBLISHED
sourceType in sources allowed by the classified intent
```

No result may cross a project or business-version boundary, even when its vector score is higher.

---

## 5. Projection Contract

### 5.1 Eligible Claims

The default 0.9.6 projection includes:

| Source | Default | Rule |
| --- | --- | --- |
| `REQUIREMENT` | Included | Published or verified, with retrievable evidence |
| `PARAMETER_TABLE` | Included selectively | Embed name, purpose, type, scope, and business description; do not create value-only points |
| `TEST_CASE` | Included | Embed title, preconditions, steps, and expected behavior |
| `DOUBT` | Included | Retained for DOUBT/CONSISTENCY intents and excluded by normal normative routing |
| `TEST_RESULT` | Excluded initially | High churn; enable only after a separate quality evaluation |
| `CODE` | Excluded | Existing code collection remains authoritative for code retrieval |
| `REQUIREMENT_SEMANTIC` | Excluded | The semantic annotation lifecycle remains independent in 0.9.6 |

Eligibility is evaluated from SQLite at build time and repeated after hydration at query time. At minimum:

```text
projectId is a canonical business project ID
businessVersion matches the requested projection scope
status is retrievable under MultiSourceKnowledgeGate
source type is enabled for the projection
canonical retrieval text is non-blank
at least one stable Claim ID exists
```

### 5.2 One Point per Claim

Each Qdrant point represents one canonical Claim, not one Evidence row. The point ID is deterministic:

```text
SHA-256(projectId | businessVersion | claimId | projectionSchemaVersion)
```

Representative Evidence IDs are payload references. Full Evidence is loaded from SQLite after a hit. The first implementation should retain at most three stable Evidence IDs per Claim, sorted by role and ID.

### 5.3 Typed Retrieval Text

The text composer must be deterministic and versioned. Suggested formats:

```text
[Requirement]
Subject: Guild war reward
Predicate: Distribution condition
Value: Rewards are distributed after settlement to eligible members
Module: Guild war
Fact key: guild_war.reward.distribution
```

```text
[Test Case]
Title: Guild war reward settlement
Preconditions: The player belongs to a guild
Action: Complete a guild war and settle the ranking
Expected result: Rewards are distributed according to ranking
```

```text
[Parameter]
Name: guild_war_reward_limit
Purpose: Limits guild war reward claims
Value type: Integer
Scope: Version 5.1
Description: Maximum reward claim count for a settlement cycle
```

Raw IDs, timestamps, status labels, isolated numeric values, and full duplicated Evidence excerpts must not dominate embedding text.

### 5.4 Qdrant Payload

Introduce a dedicated immutable model such as `KnowledgeClaimVectorPoint` rather than overloading requirement-specific `ChunkRecord` semantics:

```json
{
  "projectId": "immortal",
  "businessVersion": "5.1",
  "claimId": "claim-...",
  "documentVersionId": "dv-...",
  "sourceType": "TEST_CASE",
  "authority": "SECONDARY",
  "knowledgeStatus": "PUBLISHED",
  "factKey": "guild_war.reward.distribution",
  "subject": "Guild war reward",
  "predicate": "Distribution condition",
  "valueType": "STRING",
  "unit": null,
  "evidenceIds": ["evidence-1"],
  "projectionGenerationId": "kgp-...",
  "projectionSchemaVersion": "knowledge-claim-vector-v1",
  "embeddingModel": "...",
  "textHash": "sha256:..."
}
```

Do not rely on payload values as the source of truth after retrieval. Fields that affect governance are re-read from SQLite during hydration.

---

## 6. Generation and Publication Model

### 6.1 Projection Manifest

Add a SQLite manifest for the aggregate Claim projection. It must not reuse `knowledge_active_version`, because one projection contains Claims from multiple document versions.

Suggested tables:

```sql
create table knowledge_claim_vector_generation (
  generation_id text primary key,
  project_id text not null,
  business_version text not null,
  input_fingerprint text not null,
  projection_schema_version text not null,
  text_composer_version text not null,
  embedding_model text not null,
  embedding_dimension integer not null,
  physical_collection text,
  status text not null,
  expected_point_count integer not null,
  indexed_point_count integer not null,
  warnings_json text not null,
  started_at text not null,
  finished_at text,
  published_at text,
  unique(project_id, business_version, input_fingerprint,
         projection_schema_version, embedding_model)
);

create table knowledge_claim_vector_generation_input (
  generation_id text not null,
  claim_id text not null,
  document_version_id text not null,
  text_hash text not null,
  primary key(generation_id, claim_id),
  foreign key(generation_id) references knowledge_claim_vector_generation(generation_id)
);
```

Recommended statuses:

```text
BUILDING / VERIFYING / SUCCESS / FAILED / ACTIVE / RETIRED
```

If status and active identity are modeled separately, preserve immutable run history and enforce one active generation per `projectId + businessVersion`.

### 6.2 Input Fingerprint

The generation fingerprint must be stable regardless of SQLite row order:

```text
SHA-256(
  sorted(claimId | documentVersionId | updatedAt | canonicalTextHash)
  + projectionSchemaVersion
  + textComposerVersion
  + embeddingModel
  + embeddingDimension
)
```

Any input, composer, schema, or embedding change creates a new generation. An identical fingerprint may skip rebuilding only when the previous generation is fully verified and its physical collection still exists.

### 6.3 Safe Publication

Use a dedicated alias, defaulting to:

```text
knowledge_claims_live
```

Publication sequence:

1. Resolve the product-facing `projectId` through the business-project catalog.
2. Read one consistent SQLite snapshot of eligible Claims and representative Evidence IDs.
3. Persist a `BUILDING` generation and its immutable input set.
4. Compose text and embed in bounded batches.
5. Write all points to `knowledge_claims_live-<generationId>`.
6. Verify exact point count, random point readability, payload schema, project/version scope, and vector dimensions.
7. Atomically switch `knowledge_claims_live` to the new physical collection.
8. In the same publication workflow, mark the generation active and record the physical collection.
9. Retain at least the previous successful collection for rollback.

If any step before alias switching fails, the alias remains unchanged. If persistence after the alias switch fails, reconciliation must compare the alias target with the generation manifest and repair one side deterministically.

### 6.4 Rebuild and Rollback APIs

Add administrative endpoints behind existing WRITE access control:

```text
POST /api/knowledge-claim-vector/builds
GET  /api/knowledge-claim-vector/builds/latest
GET  /api/knowledge-claim-vector/builds/{generationId}
POST /api/knowledge-claim-vector/builds/{generationId}/rollback
```

Build request:

```json
{
  "projectId": "immortal",
  "businessVersion": "5.1",
  "force": false
}
```

Responses must expose stable error/warning codes and never include Qdrant URLs, SQL text, filesystem paths, provider messages, or secrets.

---

## 7. Query and Fusion Design

### 7.1 Query Adapter

Add `KnowledgeClaimVectorCandidateAdapter implements MultiSourceCandidateAdapter` or an equivalent dedicated candidate provider. It should:

1. Respect the intent's allowed source types before issuing Qdrant search.
2. Apply exact `projectId`, `businessVersion`, status, and source filters.
3. Run dense+sparse hybrid retrieval with an over-fetch limit.
4. Return Claim IDs, generation ID, and normalized vector score.
5. Batch hydrate all Claim IDs from `MultiSourceKnowledgeStore`.
6. Drop missing, stale, non-retrievable, wrong-project, and wrong-version Claims.
7. Map surviving rows to `UnifiedKnowledgeClaim`.
8. Emit stable warnings for unavailable, stale, truncated, or mismatched projections.

The existing `CandidateLoad` contract carries Claims, warnings, and build IDs. For 0.9.6, either generalize `buildIds` to explicit candidate generation metadata or add a separate projection-generation field. Do not overload semantic build IDs with Claim-index generation IDs.

### 7.2 Candidate Fusion

Vector and structured retrieval can return the same Claim. Deduplicate by `claimId` first. If legacy adapters generate different IDs for the same governed fact, use the existing fact identity only as a secondary merge key and retain provenance from both paths.

Initial deterministic fusion:

```text
finalScore =
  0.55 * normalizedVectorScore
  + 0.25 * lexicalFieldScore
  + 0.10 * sourcePolicyWeight
  + 0.10 * exactFactOrSubjectBoost
  - existingConflictPenalty
```

These weights are starting values, not release truth. Tune them against the fixed evaluation dataset. Source policy may order trusted candidates but must never convert vector similarity into authority.

Pagination occurs only after fusion, deduplication, governance filtering, and stable tie-breaking:

```text
score desc -> sourceType -> factKey -> claimId
```

### 7.3 Failure Semantics

Qdrant failure must degrade to the current structured retrieval path when configured to do so. Stable warnings:

```text
KNOWLEDGE_CLAIM_VECTOR_DISABLED
KNOWLEDGE_CLAIM_VECTOR_UNAVAILABLE
KNOWLEDGE_CLAIM_VECTOR_GENERATION_MISSING
KNOWLEDGE_CLAIM_VECTOR_GENERATION_STALE
KNOWLEDGE_CLAIM_VECTOR_TRUNCATED
KNOWLEDGE_CLAIM_VECTOR_HYDRATION_INCOMPLETE
KNOWLEDGE_CLAIM_VECTOR_SCOPE_MISMATCH
```

For evaluation and audit, a response is vector-evaluable only when it records the exact active projection generation consumed by that request. Display status fetched separately from the search response must not be used as the evaluation identity.

---

## 8. Configuration and Rollout

Add configuration under `app.rag.multi-source.claim-vector`:

```yaml
app:
  rag:
    multi-source:
      claim-vector:
        enabled: false
        build-enabled: false
        candidate-retrieval-enabled: false
        shadow-query-enabled: false
        alias: knowledge_claims_live
        projection-schema-version: knowledge-claim-vector-v1
        text-composer-version: knowledge-claim-text-v1
        candidate-limit: 200
        over-fetch-factor: 3
        batch-size: 32
        representative-evidence-limit: 3
        retain-physical-collections: 2
```

All switches default to `false`. Rollout stages:

1. **Build-only:** create and verify generations; query path unchanged.
2. **Shadow query:** execute vector retrieval, record metrics, but do not alter responses.
3. **Internal fusion:** enable for selected business projects and internal users.
4. **Limited production:** enable per project after quality gates pass.
5. **Default-on consideration:** only after longitudinal evaluation shows no leakage or material regression.

Project selection must use canonical business-project IDs, not repository IDs or aliases persisted as independent scopes.

---

## 9. Observability and Operations

### 9.1 Metrics

At minimum:

```text
nexus.knowledge.claim.vector.build.started
nexus.knowledge.claim.vector.build.completed
nexus.knowledge.claim.vector.build.failed
nexus.knowledge.claim.vector.build.latency
nexus.knowledge.claim.vector.points
nexus.knowledge.claim.vector.embedding.calls
nexus.knowledge.claim.vector.embedding.tokens
nexus.knowledge.claim.vector.query.latency
nexus.knowledge.claim.vector.query.hits
nexus.knowledge.claim.vector.hydration.dropped
nexus.knowledge.claim.vector.generation.mismatch
nexus.knowledge.claim.vector.fallback
```

Tag only bounded dimensions such as status, source type, and rollout mode. Do not tag raw project IDs, queries, Claim IDs, or exception messages.

### 9.2 Structured Logs

Logs may contain generation ID, canonical project ID, business version, counts, stage, duration, and stable warning code. Do not log canonical retrieval text, Evidence excerpts, provider payloads, embeddings, or secrets.

### 9.3 Operational Checks

Provide a health/status view with:

- active generation and physical collection;
- manifest fingerprint and model/schema versions;
- expected versus indexed point count;
- alias target consistency;
- last successful build and last failed run;
- per-source point counts;
- rollback targets.

---

## 10. Implementation Plan

### Phase A: Contracts and Manifest

**Deliverables**

- `KnowledgeClaimVectorProperties` with fail-safe defaults and validation.
- Projection generation and input records.
- SQLite schema/migrations and store APIs.
- `KnowledgeClaimVectorTextComposer` with stable fixtures.
- `KnowledgeClaimVectorPoint` payload contract.

**Tests**

- Idempotent migrations.
- Fingerprint stability under input reordering.
- Fingerprint changes for text/model/schema/input changes.
- Eligibility and representative-Evidence selection.
- Deterministic text snapshots for every enabled source type.

### Phase B: Build and Atomic Publication

**Deliverables**

- `KnowledgeClaimVectorBuildService`.
- Claim-specific Qdrant store/publisher, reusing alias primitives without chunk-specific payload assumptions.
- Build status and rollback controller.
- Reconciliation for manifest/alias disagreement.

**Tests**

- Failed write, verification, and alias-switch scenarios preserve the prior live index.
- Exact count, payload, scope, vector-dimension, and sample-read validation.
- Concurrent same-scope builds serialize or reject deterministically.
- Different scopes cannot publish mixed project/version points.
- Rollback restores the prior generation.

### Phase C: Shadow Retrieval and Hydration

**Deliverables**

- Hybrid vector query with exact payload filters.
- Batch `findClaimsByIds(...)` hydration API in `MultiSourceKnowledgeStore`.
- Candidate adapter with stable warnings and generation identity.
- Shadow metrics and sampled comparison report.

**Tests**

- Cross-project and cross-version leakage is zero.
- Deleted/stale/unpublished Claims are removed during hydration.
- Missing Qdrant hits degrade without failing the complete request.
- DOUBT and normal normative source gates remain unchanged.

### Phase D: Deterministic Fusion

**Deliverables**

- Score normalization and fusion in a dedicated component, not inline branching in `MultiSourceSearchService`.
- `claimId` deduplication and stable tie-breaking.
- Response diagnostics containing the actual consumed Claim projection generation.
- Evaluation export bound to the response snapshot.

**Tests**

- Same Claim returned by vector and structured paths appears once.
- Pagination is stable across repeated requests against one generation.
- Conflict analysis consumes the full governed candidate set.
- Qdrant degradation reproduces the structured-only result contract plus a warning.

### Phase E: Real-Data Evaluation and Rollout

**Deliverables**

- Build the approximately 201,000-Claim `immortal` corpus.
- Freeze a project/version/generation/model evaluation manifest.
- Add source-stratified golden queries and shadow comparison reports.
- Perform rollback and full rebuild drills.
- Publish an operator runbook and rollout decision record.

---

## 11. Quality Gates

0.9.6 is not complete until all mandatory gates pass.

### 11.1 Correctness

| Gate | Required result |
| --- | --- |
| Cross-project leakage | `0` |
| Cross-version leakage | `0` |
| Unpublished/stale Claim returned after hydration | `0` |
| Duplicate Claim occupancy after fusion | `0` duplicate `claimId` entries |
| Alias/manifest disagreement after successful build | `0` |
| Failed publication changes live alias | `0` |
| Rollback drill | Prior generation restored and queryable |

### 11.2 Retrieval Quality

Measure separately by Requirement, Parameter, Test Case, and Doubt source:

- Recall@10 and Recall@20;
- MRR@10 or NDCG@10;
- source coverage;
- duplicate occupancy before and after fusion;
- wrong-source and wrong-version rates;
- structured-only versus fused regression count.

Release criteria:

1. Fused Recall@10 must materially improve over structured-only retrieval on paraphrase queries.
2. Exact-term and numeric queries must not materially regress.
3. No source category may be silently eliminated by one dominant high-volume source.
4. All evaluation rows must record project, business version, projection generation, embedding model, schema version, query intent, and retrieval mode.

Numeric improvement thresholds should be set from the frozen 0.9.6 baseline report, not invented before the first real-data run.

### 11.3 Performance and Cost

Capture:

- end-to-end build duration for approximately 201,000 points;
- embedding call and token cost;
- physical collection size;
- P50/P95/P99 vector query latency;
- P50/P95/P99 complete multi-source search latency;
- SQLite hydration batch latency and dropped-hit count;
- memory high-water mark during build.

The build must stream or page Claims. It must not retain all Claim text, embeddings, and Evidence in memory simultaneously.

---

## 12. Test Matrix

| Layer | Required coverage |
| --- | --- |
| Unit | eligibility, typed text, fingerprint, point ID, score normalization, deduplication |
| SQLite | migrations, generation lifecycle, input set, active generation, rollback history |
| Qdrant contract | collection creation, payload schema, dense+sparse vectors, exact filters, alias switch |
| Service integration | build, verify, publish, reconcile, rollback, hydrate, degrade |
| Search integration | intent routing, source filtering, fusion, conflicts, pagination, warnings |
| Security | project access, WRITE control, no internal error leakage, canonical project IDs |
| Evaluation | frozen corpus/generation/model, source-stratified golden cases, repeatable reports |
| Scale | approximately 201,000 Claims, bounded memory, measured latency and cost |

Existing relevant suites to extend include:

```text
MultiSourceKnowledgeStoreTest
MultiSourceKnowledgePublishTest
MultiSourceSearchServiceTest
MultiSourceGoldenEvalTest
QdrantHybridStoreMultiSourceTest
MultiSourceKnowledgeControllerTest
```

Add dedicated tests for the projection manifest, Claim Qdrant store, build service, vector adapter, fusion component, and live Qdrant workflow.

---

## 13. Data Migration and Backfill

No destructive migration is required.

1. Add manifest tables idempotently.
2. Deploy with all Claim-vector flags disabled.
3. Build a new physical collection from currently published SQLite Claims.
4. Validate per-source counts against SQLite.
5. Enable shadow queries.
6. Compare shadow and structured-only outcomes.
7. Enable fusion for one project only after gates pass.

Removing 0.9.6 consists of disabling candidate retrieval and deleting the Claim-vector alias/physical collections. SQLite facts and existing retrieval remain intact.

---

## 14. Security and Governance

1. Build, status, and rollback APIs must use existing authentication, authorization, and project-access rules.
2. Product-facing `projectId` is always a business-project ID resolved through the catalog.
3. Payload must not contain secrets, full private documents, or unbounded Evidence text.
4. Provider and Qdrant errors map to stable external codes; raw exception text stays in controlled server logs.
5. Query evaluation records must bind the actual response generation, not a separately fetched mutable status.
6. Vector candidates remain subject to `MultiSourceKnowledgeGate`, intent source filtering, authority handling, conflict analysis, and Evidence traceability.

---

## 15. Definition of Done

Version 0.9.6 is complete when:

- [ ] Claim projection contracts and SQLite generation manifest are implemented and documented.
- [ ] Eligible Claims are streamed into a dedicated dense+sparse Qdrant collection.
- [ ] Physical collection verification and atomic alias publication are covered by failure-path tests.
- [ ] Rollback and manifest/alias reconciliation are operational.
- [ ] Vector hits are hydrated and revalidated from SQLite.
- [ ] Existing and vector candidates are deterministically fused and deduplicated.
- [ ] All feature flags default to disabled and project-level rollout is supported.
- [ ] Search responses and evaluation exports carry the exact consumed projection generation.
- [ ] Real-data evaluation on the approximately 201,000-Claim corpus passes correctness gates.
- [ ] Build/query latency, storage, memory, and embedding cost are reported.
- [ ] Changelog, operator documentation, and rollback drill evidence are included in the release.

---

## 16. Recommended Delivery Order

```text
manifest and contracts
  -> deterministic text fixtures
  -> build and physical collection validation
  -> atomic alias publication and rollback
  -> shadow retrieval and SQLite hydration
  -> deterministic fusion
  -> real-data evaluation
  -> project-scoped rollout
```

This order protects the current structured retrieval baseline while allowing every new 0.9.6 capability to be verified, disabled, rebuilt, and rolled back independently.
