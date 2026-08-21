# Requirement Semantic Graph: Overall Development Plan

**Status:** Proposed consolidation plan for the controlled-rollout implementation  
**Owner:** NEXUS Retrieval / Knowledge Platform  
**Scope:** Requirement-document ingestion, semantic graph construction, evidence governance, review, publication, retrieval, evaluation, and operations  
**Last updated:** 2026-08-21  
**Primary reference:** `docs/requirement-semantic-graph-development-plan.md`

## 1. Executive Summary

This document defines the end-to-end development plan for turning the current requirement semantic graph prototype into a production-safe requirement intelligence capability.

The system must not treat an LLM-generated graph as an unqualified source of truth. It must maintain a version-scoped, evidence-backed, reviewable projection of requirement documents. The graph should improve requirement retrieval and impact analysis while preserving the existing requirement retrieval path as the compatibility baseline.

The target solution combines:

- structure-aware requirement document windows;
- constrained entity and relation extraction;
- cross-window entity resolution;
- exact evidence spans and source-content hashes;
- explicit uncertainty and conflict modeling;
- claim-level review and immutable publication gates;
- resumable and budget-controlled asynchronous builds;
- text, vector, graph, and evidence-aware retrieval;
- LightRAG-inspired local/global/mix query routing without copying its governance assumptions;
- evaluation, observability, privacy controls, and controlled rollout.

The central product principle is:

> The graph is a governed projection of the requirement corpus, not a replacement for the requirement corpus.

## 2. Current State

The repository already contains a controlled-rollout foundation for requirement graphs, including:

- schema-versioned graph snapshots;
- bounded overlapping windows;
- persisted window results and resume support;
- evidence, uncertainty, and conflict records;
- claim-level review and audit records;
- publication blocking for unverified claims and unresolved evidence;
- local/global/optional hybrid graph retrieval;
- neighborhood and path APIs;
- project privacy policy configuration;
- synthetic quality-gate tests;
- build and retrieval metrics;
- an evidence-first review page at `/requirement-graph.html`.

The main implementation areas are:

- `src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphBuildService.java`
- `src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphExtractionService.java`
- `src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphHybridSearchService.java`
- `src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphSearchService.java`
- `src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphModels.java`
- `src/main/java/com/example/requirementrag/requirement/graph/SQLiteRequirementGraphStore.java`
- `src/main/java/com/example/requirementrag/web/RequirementGraphController.java`
- `src/main/resources/application.yml`
- `src/main/resources/static/requirement-graph.html`

The current implementation should be considered a foundation rather than the final architecture. The remaining work is primarily about consistency, persistence, retrieval quality, operational hardening, and production rollout.

## 3. Problem Statement

Requirement documents contain more than isolated facts. They contain:

- business entities;
- workflows and state transitions;
- rules and constraints;
- actors and permissions;
- exceptions and alternative paths;
- acceptance criteria;
- cross-section references;
- version-specific changes;
- implicit dependencies between modules and business objects.

Flat vector retrieval is effective for direct semantic similarity but is weak for questions such as:

- Which modules are affected by a change to cancellation rules?
- What states can an order enter after a payment failure?
- Which acceptance criteria verify a given business rule?
- Did version 1.2 change the relationship between inventory reservation and order cancellation?
- Which claims are supported by evidence and which are only model inferences?

A semantic graph can improve these queries, but it also introduces new failure modes:

- unsupported relationships;
- entity duplication;
- incorrect cross-section merges;
- evidence drift;
- stale snapshots;
- silent partial extraction;
- invalid graph publication;
- retrieval of unverified or outdated claims.

The development plan therefore treats extraction, governance, and retrieval as one cross-layer system.

## 4. Goals

### 4.1 Product goals

1. Build a semantic representation of each requirement document version.
2. Preserve exact source evidence for every published entity and relation.
3. Allow reviewers to verify, reject, merge, split, and patch claims.
4. Prevent unsupported, unresolved, or stale claims from appearing as confirmed facts.
5. Answer both local fact questions and global impact questions.
6. Support requirement-version comparison and change impact analysis.
7. Keep the existing Qdrant requirement retrieval path available as a fallback.
8. Make graph construction recoverable under model, storage, and process failures.

### 4.2 Engineering goals

1. Keep all data isolated by business project, document, requirement version, and source revision.
2. Make schema, ontology, prompt, and model versions explicit.
3. Make build and query costs bounded and observable.
4. Make updates incremental whenever only part of a requirement document changes.
5. Make the output contract stable for the UI, APIs, and downstream RAG orchestration.
6. Make the system safe for multi-user review and controlled production rollout.

## 5. Non-goals

This project does not aim to:

- replace the primary dense+sparse Qdrant requirement retrieval path;
- create a general-purpose enterprise knowledge graph for arbitrary data;
- publish model output automatically as product policy;
- infer unsupported business rules from world knowledge;
- solve source-document authoring or approval workflows;
- provide unrestricted graph mutation without audit records;
- make LightRAG the runtime dependency of the Java service;
- migrate all existing knowledge features to the requirement graph in the first release.

## 6. Design Principles

### 6.1 Evidence before confidence

A high model confidence score cannot compensate for missing or invalid evidence. The system must prioritize:

```text
evidence validity > claim status > source version match > model confidence
```

### 6.2 Snapshot isolation

Every graph result must be scoped to:

```text
businessProjectId
+ documentId
+ requirementVersion
+ sourceRevision
+ schemaVersion
+ ontologyVersion
+ extractionModel
+ extractionPromptVersion
```

### 6.3 Draft first, publish explicitly

A build creates a draft or review-required snapshot. It does not silently replace the published graph.

### 6.4 Uncertainty is data

If the model cannot determine whether a relation is valid, that uncertainty must be stored and returned. It must not be collapsed into a normal relation.

### 6.5 Text remains the source of truth

The graph is a structured projection. Every answer that presents a requirement fact should be able to return the supporting text and source location.

### 6.6 Retrieval mode should follow intent

Local fact queries, global impact queries, direct text queries, and audit queries should not all use the same retrieval path.

### 6.7 Compatibility over replacement

The graph feature is introduced behind flags. When graph retrieval is unavailable, stale, incomplete, or below the required quality level, the system must fall back to the existing requirement retrieval path.

## 7. LightRAG-Inspired Direction

LightRAG is a useful reference for retrieval architecture, not a complete governance model for requirements. Its current official implementation provides multiple retrieval modes, including local, global, hybrid, naive, and mix. The official README describes `mix` as combining local graph retrieval, global graph retrieval, and naive text-chunk retrieval. It also documents separate LLM roles for extraction, query generation, keywords, and VLM processing. See the official HKUDS/LightRAG repository and paper `arXiv:2410.05779` for the reference design.

### 7.1 Capabilities to borrow

1. **Explicit query modes**
   - `NAIVE`: direct requirement chunk retrieval.
   - `LOCAL`: entity-first local graph retrieval.
   - `GLOBAL`: relation/theme-first global retrieval.
   - `HYBRID`: local plus global graph retrieval.
   - `MIX`: text chunks plus local and global graph retrieval.

2. **Query keyword planning**
   - extract entity keywords;
   - extract relation or theme keywords;
   - route to local/global/mix;
   - enforce a per-query graph and evidence budget.

3. **Role-specific model configuration**
   - extraction model;
   - query planner/keyword model;
   - evidence verification model;
   - answer-generation model;
   - optional multimodal model.

4. **Graph plus chunk retrieval**
   - return entities;
   - return relations;
   - return source chunks;
   - merge and rerank all candidates.

5. **Incremental processing and cache keys**
   - reuse extraction results for unchanged content;
   - invalidate results when model, prompt, ontology, or schema changes;
   - rebuild only affected graph portions after a document update.

6. **Reranking and evaluation integration**
   - rerank mixed graph and text candidates;
   - measure context precision and recall;
   - return retrieved context separately from the generated answer.

### 7.2 Capabilities not to copy blindly

1. Do not treat the generated graph as automatically publishable truth.
2. Do not hide unresolved evidence behind a normal answer.
3. Do not merge claims across requirement versions without explicit version rules.
4. Do not make `MIX` the default for every requirement query.
5. Do not remove reviewer, audit, conflict, and publication states.
6. Do not use graph confidence as a replacement for evidence validation.

## 8. Target Architecture

```text
Requirement Document / Version
            |
            v
Structure-aware Parser and Window Planner
            |
            +--------------------+
            |                    |
            v                    v
Raw Requirement Chunks     Window/Extraction Cache
            |                    |
            +---------+----------+
                      v
              Constrained Extractor
                      |
       +--------------+---------------+
       |              |               |
       v              v               v
   Entities       Relations       Evidence Spans
       |              |               |
       +--------------+---------------+
                      v
          Normalization and Resolution
                      |
          +-----------+-----------+
          |                       |
          v                       v
   Uncertainty Detection   Conflict Detection
          |                       |
          +-----------+-----------+
                      v
                 Draft Snapshot
                      |
                      v
             Review and Publication
                      |
       +--------------+---------------+
       |              |               |
       v              v               v
  Text Index      Graph Store      Vector Index
       |              |               |
       +--------------+---------------+
                      v
             Query Planner and Router
                      |
       +--------------+---------------+
       |              |               |
       v              v               v
    NAIVE       LOCAL/GLOBAL       MIX
       |              |               |
       +--------------+---------------+
                      v
        Evidence-aware Candidate Fusion
                      |
                      v
           Answer Context / API Response
```

### 8.1 Component responsibilities

| Component | Responsibility |
|---|---|
| Requirement source adapter | Load requirement chunks and version metadata from the existing source of truth. |
| Window planner | Create bounded, structure-aware, stable-ID windows. |
| Extraction service | Produce constrained entity, relation, evidence, uncertainty, and conflict candidates. |
| Resolver | Normalize names, merge aliases, resolve cross-window references, and validate ontology constraints. |
| Evidence resolver | Map evidence spans back to source content and mark missing or stale evidence. |
| Graph store | Persist snapshots, windows, claims, evidence, review, and audit data. |
| Query planner | Select retrieval mode and query budget from user intent. |
| Retrieval services | Execute text, vector, graph, and evidence retrieval. |
| Quality gate | Enforce extraction, publication, and evaluation thresholds. |
| Review UI/API | Allow authorized users to inspect and modify claim state. |
| Observability | Record build, extraction, retrieval, cost, and quality metrics. |

## 9. Domain Model

### 9.1 Entity types

The initial ontology should remain intentionally small:

```text
MODULE
FEATURE
BUSINESS_OBJECT
ACTOR
STATE
RULE
ACCEPTANCE_CRITERION
EXTERNAL_SYSTEM
VALUE_OR_PARAMETER
EVENT
EXCEPTION
INTERFACE
DATA_ENTITY
CONFIGURATION
VERSION
```

New types require an ontology version change and a migration/compatibility decision.

### 9.2 Relation types

```text
CONTAINS
DEPENDS_ON
AFFECTS_MODULE
TRANSITIONS_TO
REQUIRES
REQUIRES_RULE
VERIFIED_BY
CONFLICTS_WITH
EXCEPTION_TO
HAS_EXCEPTION
USES
OPERATES_ON
EXPOSES_INTERFACE
INTRODUCED_IN_VERSION
CHANGES_STATE
```

Each relation must define both source and target constraints. Unknown relation combinations must be rejected by default.

### 9.3 Claim status

```text
EXTRACTED
INFERRED
VERIFIED
REJECTED
CONFLICTED
STALE
UNAVAILABLE
```

Recommended interpretation:

| Status | Meaning | Search default |
|---|---|---|
| `VERIFIED` | Reviewer confirmed the claim and its evidence. | Included |
| `EXTRACTED` | Model extracted it directly from a source window. | Excluded from published search |
| `INFERRED` | Model or resolver inferred it from multiple observations. | Excluded by default |
| `CONFLICTED` | Competing observations or statements exist. | Excluded by default |
| `REJECTED` | Reviewer rejected the claim. | Never included |
| `STALE` | Source or ontology has changed. | Excluded |
| `UNAVAILABLE` | Evidence or source cannot currently be resolved. | Excluded |

### 9.4 Evidence contract

Every new claim must reference one or more evidence records. A new evidence record must contain:

```json
{
  "evidenceId": "requirement:evidence:...",
  "snapshotId": "graph:snapshot:...",
  "filename": "requirements.md",
  "parentId": "requirements-section-12",
  "sectionPath": "Orders / Cancellation",
  "contentHash": "sha256:...",
  "quote": "Cancelling an order releases reserved inventory.",
  "startOffset": 1842,
  "endOffset": 1896,
  "resolutionStatus": "RESOLVED"
}
```

Evidence resolution states:

```text
RESOLVED
MISSING_SOURCE
OFFSET_INVALID
CONTENT_CHANGED
PERMISSION_DENIED
REDACTED
```

A published claim must not reference missing, invalid, changed, or unresolved evidence.

### 9.5 Snapshot lifecycle

```text
DRAFT
  -> BUILDING
  -> REVIEW_REQUIRED
  -> VERIFIED
  -> PUBLISHED

BUILDING -> PARTIAL_FAILED
BUILDING -> FAILED
REVIEW_REQUIRED -> REJECTED
PUBLISHED -> STALE
```

Rules:

- `PUBLISHED` requires all claims to satisfy the publication policy.
- `PARTIAL_FAILED` cannot be published unless an explicit override policy exists.
- A source revision, schema version, ontology version, or extraction policy change may mark a snapshot `STALE`.
- Publication records actor, reason, timestamp, request ID, and snapshot metadata.
- Published snapshots are immutable; corrections create a new draft or claim revision.

## 10. Functional Requirements

### 10.1 Build requirements

The build system must:

1. Validate project, document, version, collection, and privacy policy.
2. Load the exact requirement version and source revision.
3. Plan deterministic windows with stable IDs.
4. Persist the snapshot and planned windows before model extraction begins.
5. Execute extraction with retry and bounded budgets.
6. Persist each successful window result before processing the next window.
7. Reuse results only when content and extraction configuration match.
8. Resolve entities and relations across windows.
9. Preserve uncertainty and conflicts.
10. Persist exact evidence spans.
11. Support cancellation without losing the resumable snapshot ID.
12. Support resume after process restart.
13. Produce explicit partial/failure status and warnings.
14. Run publication quality gates before publishing.

### 10.2 Review requirements

Authorized reviewers must be able to:

- list unresolved claims;
- inspect supporting evidence;
- verify a claim;
- reject a claim;
- patch an entity or relation;
- merge duplicate claims;
- split an over-broad claim;
- record a reason;
- inspect prior audit events;
- compare the current draft with the published snapshot;
- publish only when all blockers are resolved.

### 10.3 Search requirements

The search system must support:

- direct text retrieval;
- local entity retrieval;
- global relation/theme retrieval;
- combined graph retrieval;
- combined text plus graph retrieval;
- neighborhood expansion;
- bounded path search;
- evidence-first response assembly;
- claim-status filters;
- version filters;
- stale and unresolved warnings;
- pagination and truncation metadata.

### 10.4 Answer requirements

Any answer generated from graph context must:

1. carry the selected snapshot ID;
2. carry the requirement version;
3. include claim status;
4. include source evidence IDs;
5. expose evidence resolution warnings;
6. avoid presenting unresolved claims as confirmed facts;
7. fall back to normal requirement retrieval if graph quality is insufficient.

## 11. Build Pipeline Design

### Phase A: Source validation

Inputs:

```text
projectId
documentId
requirementVersion
collection
resumeSnapshotId
allowPartial
```

Checks:

- project access;
- source version exists;
- parent chunks are available;
- source revision is stable;
- privacy policy allows processing;
- request budget is valid;
- resume snapshot belongs to the same project/document/version/source revision.

### Phase B: Structure-aware window planning

The planner should preserve:

- headings;
- paragraph boundaries;
- list items;
- tables where possible;
- acceptance criteria blocks;
- precondition/action/postcondition groups;
- exception branches;
- references to neighboring sections.

Each window must have:

```text
windowId
snapshotId
filename
parentId
sectionPath
heading
windowIndex
startOffset
endOffset
contentHash
continuationOf
status
attemptCount
```

### Phase C: Constrained extraction

The extraction prompt must require JSON conforming to the current schema. Every entity and relation candidate must provide:

- type;
- canonical/display name;
- statement;
- confidence;
- evidence quote or span;
- uncertainty reason when applicable;
- source-local context.

The extractor must not invent evidence offsets. If exact offsets cannot be produced, the result must be marked unresolved and routed to evidence resolution.

### Phase D: Cross-window resolution

Resolution steps:

1. Normalize whitespace, casing, punctuation, and aliases.
2. Prefer same-section and same-document matches.
3. Require type compatibility for entity merges.
4. Use neighboring-window context for ambiguous names.
5. Preserve multiple observations instead of overwriting them.
6. Create a conflict set when relation statements disagree.
7. Use deterministic IDs derived from snapshot and canonical identity.
8. Store the first and last observed windows.

### Phase E: Evidence resolution

For each evidence candidate:

1. Verify the source content hash.
2. Validate offsets against the current source text.
3. Recompute the quote from the source when possible.
4. Detect changed or missing source content.
5. Store resolution status and failure reason.
6. Prevent publication when a required evidence record is unresolved.

### Phase F: Review and publication

The build result is initially one of:

```text
REVIEW_REQUIRED
PARTIAL_FAILED
FAILED
```

Publication requires:

- all required windows succeeded;
- all publishable claims are verified;
- all required evidence is resolved;
- no blocking conflicts remain;
- source revision is current;
- privacy policy allows publication;
- quality gate passes;
- actor and reason are recorded.

## 12. Query and Retrieval Design

### 12.1 Query plan

Add a query planning layer before graph retrieval:

```java
public record RequirementGraphQueryPlan(
        SearchMode mode,
        List<String> entityKeywords,
        List<String> relationKeywords,
        List<String> sectionKeywords,
        int maxHops,
        int maxEntities,
        int maxRelations,
        int maxEvidence,
        Set<ClaimStatus> allowedStatuses
) {}
```

The planner can be deterministic for the first release and model-assisted later.

### 12.2 Query mode semantics

```text
NAIVE
  Direct text/vector retrieval over requirement chunks.

LOCAL
  Entity-first retrieval followed by bounded local neighborhood expansion.

GLOBAL
  Relation/theme-first retrieval followed by cross-section graph expansion.

HYBRID
  LOCAL + GLOBAL graph retrieval.

MIX
  NAIVE + LOCAL + GLOBAL, followed by unified reranking.
```

The current implementation supports `LOCAL`, `GLOBAL`, and `HYBRID`. The next retrieval milestone is to add `NAIVE` and `MIX` as first-class modes.

### 12.3 Candidate fusion

Candidate sources:

```text
text chunks
entities
relations
neighbors
paths
evidence spans
```

Recommended initial score:

```text
finalScore =
    0.25 * textScore
  + 0.20 * entityScore
  + 0.20 * relationScore
  + 0.10 * graphDistanceScore
  + 0.10 * evidenceQualityScore
  + 0.10 * versionMatchScore
  + 0.05 * claimStatusScore
```

The weights must be configurable and evaluated against the requirement benchmark. `VERIFIED` status should be a filter or strong prior, not merely a cosmetic field.

### 12.4 Evidence-first context assembly

The answer context should contain separate sections:

```text
[CLAIMS]
[RELATIONS]
[GRAPH PATHS]
[SOURCE EVIDENCE]
[RAW REQUIREMENT CHUNKS]
[WARNINGS]
```

The answer model must be instructed to distinguish:

- confirmed facts;
- extracted but unverified claims;
- inferred claims;
- conflicting claims;
- unavailable evidence.

## 13. Persistence Design

### 13.1 Required tables

Existing or planned storage should cover:

```text
requirement_graph_snapshot
requirement_graph_window
requirement_graph_window_result
requirement_graph_entity
requirement_graph_relation
requirement_graph_evidence
requirement_graph_uncertainty
requirement_graph_conflict
requirement_graph_audit
requirement_graph_entity_embedding
requirement_graph_relation_embedding
requirement_graph_build_job
requirement_graph_claim_evidence
```

### 13.2 Build job persistence

The asynchronous job service must not rely only on an in-memory map. Persist:

```text
buildId
projectId
documentId
requirementVersion
collection
resumeSnapshotId
snapshotId
state
completedWindows
totalWindows
errorCode
errorMessage
cancelRequested
createdAt
startedAt
finishedAt
lastHeartbeatAt
```

Terminal jobs may be archived, but the snapshot and audit records must remain queryable.

### 13.3 Claim-evidence association

The initial implementation stores evidence IDs in JSON arrays for compatibility. The production design should add a normalized association table:

```text
claim_evidence(
    claim_id,
    evidence_id,
    role,
    required,
    created_at,
    primary key (claim_id, evidence_id)
)
```

Publication must validate the association table, not only the denormalized JSON fields.

### 13.4 Cache key

Extraction cache reuse must include:

```text
contentHash
modelId
promptVersion
ontologyVersion
schemaVersion
extractorConfigHash
```

Query cache keys must include:

```text
snapshotId
query
mode
filters
allowedStatuses
includeUnresolved
maxHops
limit
page
rerankerVersion
```

## 14. API Plan

Base path:

```text
/api/requirement-graphs
```

### 14.1 Build APIs

```text
POST   /build
POST   /builds
GET    /builds/{buildId}
POST   /builds/{buildId}/resume
POST   /builds/{buildId}/cancel
```

The synchronous `/build` endpoint remains for compatibility and controlled use. New UI and long-running integrations should use `/builds`.

### 14.2 Snapshot APIs

```text
GET    /snapshots/{snapshotId}
GET    /snapshots/{snapshotId}/windows
GET    /snapshots/{snapshotId}/claims
GET    /snapshots/{snapshotId}/audit
POST   /snapshots/{snapshotId}/publish
POST   /snapshots/{snapshotId}/reject
```

### 14.3 Claim review APIs

```text
POST   /claims/{claimId}/verify
POST   /claims/{claimId}/reject
POST   /claims/{claimId}/merge
POST   /claims/{claimId}/split
PATCH  /entities/{entityId}
PATCH  /relations/{relationId}
```

Every mutation requires:

```text
actor
reason
requestId
expectedVersion or etag
```

### 14.4 Retrieval APIs

```text
POST   /query
GET    /snapshots/{snapshotId}/entities
GET    /snapshots/{snapshotId}/relations
GET    /snapshots/{snapshotId}/neighborhood/{entityId}
GET    /snapshots/{snapshotId}/paths
```

Query request example:

```json
{
  "projectId": "orders",
  "documentId": "checkout-requirements",
  "requirementVersion": "1.2",
  "query": "What happens to inventory after order cancellation?",
  "mode": "MIX",
  "statuses": ["VERIFIED"],
  "includeUnresolved": false,
  "maxHops": 2,
  "limit": 20,
  "page": 0
}
```

Response must include:

```json
{
  "snapshot": {},
  "entities": [],
  "relations": [],
  "paths": [],
  "evidence": [],
  "sourceChunks": [],
  "warnings": [],
  "total": 0,
  "truncated": false
}
```

## 15. Security and Privacy

### 15.1 Access control

Every endpoint must authorize access using the business project associated with:

- the build request;
- the snapshot;
- the claim;
- the relation or entity;
- the audit record;
- the evidence source.

Do not authorize only from a client-supplied `projectId` when the resource ID can be used to resolve the project server-side.

### 15.2 Model transmission policy

Project policy must explicitly control:

```text
whether graph extraction is enabled;
whether external model transmission is allowed;
whether evidence quotes may leave the deployment boundary;
whether redacted documents are allowed;
whether raw source chunks may be returned to clients.
```

### 15.3 Data minimization

- Do not log full requirement text by default.
- Log content hashes and stable IDs instead of raw sensitive text.
- Redact evidence in operational logs.
- Restrict audit visibility to authorized users.
- Encrypt persistent graph storage where required by deployment policy.

## 16. Failure, Retry, and Recovery

### 16.1 Stable error codes

```text
GRAPH_INPUT_EMPTY
GRAPH_PRIVACY_POLICY_BLOCKED
GRAPH_WINDOW_TOO_LARGE
GRAPH_MODEL_TIMEOUT
GRAPH_MODEL_RATE_LIMITED
GRAPH_MODEL_UNAVAILABLE
GRAPH_SCHEMA_INVALID
GRAPH_EVIDENCE_INVALID
GRAPH_EVIDENCE_MISSING
GRAPH_WINDOW_FAILED
GRAPH_PARTIAL_FAILURE
GRAPH_BUILD_CANCELLED
GRAPH_SNAPSHOT_STALE
GRAPH_PUBLICATION_BLOCKED
GRAPH_RESULT_TRUNCATED
```

### 16.2 Retry policy

Retry only transient failures:

```text
MODEL_TIMEOUT
MODEL_RATE_LIMITED
MODEL_UNAVAILABLE
TEMPORARY_STORAGE_FAILURE
```

Do not retry indefinitely for:

```text
invalid schema
invalid evidence
ontology violation
privacy block
permission denial
source version mismatch
```

### 16.3 Cancellation policy

Cancellation must:

1. persist `cancelRequested`;
2. preserve the current `snapshotId`;
3. mark active windows as interrupted or pending resume;
4. avoid deleting successful window results;
5. expose a resumable terminal state;
6. prevent a late worker completion from overwriting the cancelled state.

### 16.4 Process restart policy

On startup:

- load persisted `QUEUED` and `RUNNING` jobs;
- mark jobs with stale heartbeats as recoverable;
- resume only when the source revision and configuration still match;
- otherwise mark the job failed with a clear reason;
- never silently discard persisted window results.

## 17. Observability

### 17.1 Build metrics

```text
requirement_graph_build_started_total
requirement_graph_build_completed_total
requirement_graph_build_duration_seconds
requirement_graph_build_failed_total
requirement_graph_build_partial_total
requirement_graph_window_total
requirement_graph_window_succeeded_total
requirement_graph_window_failed_total
requirement_graph_window_retry_total
requirement_graph_window_resume_reuse_total
requirement_graph_model_calls_total
requirement_graph_model_tokens_total
requirement_graph_model_cost_total
```

### 17.2 Quality metrics

```text
requirement_graph_entity_precision
requirement_graph_entity_recall
requirement_graph_relation_precision
requirement_graph_relation_recall
requirement_graph_evidence_span_validity
requirement_graph_evidence_resolution_rate
requirement_graph_conflict_rate
requirement_graph_unsupported_published_claim_rate
requirement_graph_stale_claim_hit_rate
```

### 17.3 Retrieval metrics

```text
requirement_graph_query_total
requirement_graph_query_duration_seconds
requirement_graph_query_mode_total
requirement_graph_query_truncated_total
requirement_graph_query_evidence_missing_total
requirement_graph_query_fallback_total
requirement_graph_recall_at_k
requirement_graph_evidence_recall_at_k
requirement_graph_version_accuracy
```

Do not include raw requirement text in metric labels.

## 18. Testing and Evaluation

### 18.1 Unit tests

Cover:

- window boundary and overlap behavior;
- stable window IDs;
- source hash calculation;
- entity canonicalization;
- alias matching;
- relation ontology validation;
- evidence offset validation;
- evidence content mismatch;
- uncertainty and conflict aggregation;
- claim status transitions;
- publication blockers;
- cache-key invalidation;
- query mode selection;
- candidate score fusion;
- path hop and result bounds.

### 18.2 Integration tests

Cover:

- full build from requirement snapshot;
- partial failure and resume;
- cancellation after snapshot creation;
- process restart and job recovery;
- missing evidence publication block;
- cross-project access denial;
- stale snapshot fallback;
- hybrid and mix retrieval;
- embedding failure metadata update;
- audit record creation;
- legacy schema-v1 read compatibility.

### 18.3 Evaluation corpus

Create at least 20 stable, versioned synthetic and curated requirement cases covering:

- direct entity facts;
- aliases;
- cross-section references;
- state transitions;
- exceptions;
- acceptance criteria;
- contradictory requirements;
- version changes;
- unsupported model hallucinations;
- missing and changed evidence;
- long documents;
- multilingual terms if supported.

### 18.4 Initial quality thresholds

```text
Entity precision                 >= 0.85
Relation precision               >= 0.80
Evidence span validity           >= 0.98
Unsupported published claim rate = 0
Published unresolved evidence   = 0
Resume duplicate call rate      <= 0.01
Retryable recovery rate         >= 0.95
Version accuracy                >= 0.98
```

Thresholds should be reviewed after the first labeled corpus is available. A threshold change requires a documented decision.

## 19. Implementation Roadmap

### Phase 0: Contract and baseline

**Objective:** Freeze contracts before further feature growth.

Deliverables:

- schema and ontology versioning rules;
- claim/evidence lifecycle documentation;
- baseline benchmark corpus;
- retrieval mode contract;
- error-code contract;
- build-job persistence design;
- security and privacy decision record.

Exit criteria:

- contracts reviewed by product, retrieval, and platform owners;
- baseline metrics recorded;
- no undocumented status or relation type remains in the public API.

### Phase 1: Build correctness and recovery

**Objective:** Make construction safe under interruption and provider failure.

Deliverables:

- persistent build jobs;
- durable snapshot-to-job binding;
- cancel/resume race handling;
- process restart recovery;
- cache key expansion;
- window-level idempotency;
- embedding warning persistence;
- retry and budget metrics.

Exit criteria:

- cancellation after snapshot creation can resume;
- restart does not lose completed windows;
- duplicate model calls stay within the threshold;
- no asynchronous job depends only on an in-memory map.

### Phase 2: Evidence and claim integrity

**Objective:** Ensure every published claim has valid evidence.

Deliverables:

- normalized claim-evidence association;
- missing evidence blockers;
- exact offset and content-hash validation;
- evidence repair workflow;
- publication integrity tests;
- reviewer diff view.

Exit criteria:

- unsupported claims cannot be published;
- missing or changed source content is visible;
- every published claim resolves to at least one required evidence record.

### Phase 3: Retrieval parity and LightRAG-inspired routing

**Objective:** Improve retrieval coverage without weakening governance.

Deliverables:

- `NAIVE` retrieval;
- `MIX` retrieval;
- query planner;
- keyword and relation intent extraction;
- text/entity/relation/evidence candidate fusion;
- reranking;
- claim-status and version-aware filtering;
- fallback to existing Qdrant retrieval.

Exit criteria:

- direct fact queries do not regress against baseline;
- impact and cross-section queries improve recall;
- unresolved claims do not appear in verified-only search;
- every response includes source evidence or an explicit warning.

### Phase 4: Incremental updates and lifecycle operations

**Objective:** Avoid full rebuilds for small requirement changes.

Deliverables:

- changed-window detection;
- affected-claim calculation;
- partial graph rebuild;
- document/version deletion;
- entity merge and split persistence;
- vector rebuild;
- stale snapshot propagation.

Exit criteria:

- a localized document change does not require a full extraction;
- stale claims are not returned as current claims;
- deletion removes or invalidates all derived graph artifacts.

### Phase 5: Review workspace and controlled rollout

**Objective:** Make review and operations usable for real teams.

Deliverables:

- evidence-first review dashboard;
- batch review actions with safeguards;
- permission-aware audit view;
- cost and quality dashboards;
- project-level rollout flags;
- canary projects;
- rollback runbook.

Exit criteria:

- reviewers can complete a full claim lifecycle without direct database access;
- rollout can be disabled without affecting normal requirement retrieval;
- rollback restores the previous retrieval behavior.

### Phase 6: Advanced capabilities

**Objective:** Extend beyond the initial text-only requirement graph.

Candidate deliverables:

- structured tables and diagrams;
- multimodal evidence extraction;
- requirement-to-code linkage;
- requirement-to-test linkage;
- cross-document dependency graphs;
- conversation-aware query planning;
- offline model deployment;
- domain-specific ontology packs.

These capabilities require separate design review and must not delay the production-safety phases.

## 20. File and Module Impact Map

### Core graph domain

```text
src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphModels.java
src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphOntology.java
src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphEvidence.java
src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphWindow.java
```

### Build and extraction

```text
src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphBuildService.java
src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphBuildJobService.java
src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphWindowPlanner.java
src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphExtractionService.java
```

### Persistence and migration

```text
src/main/java/com/example/requirementrag/requirement/graph/SQLiteRequirementGraphStore.java
src/main/resources/application.yml
```

### Retrieval

```text
src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphSearchService.java
src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphHybridSearchService.java
```

### API and UI

```text
src/main/java/com/example/requirementrag/web/RequirementGraphController.java
src/main/java/com/example/requirementrag/web/ApiExceptionHandler.java
src/main/resources/static/requirement-graph.html
src/main/resources/static/assets/requirement-graph.js
src/main/resources/static/assets/requirement-graph.css
```

### Tests

```text
src/test/java/com/example/requirementrag/requirement/graph/
src/test/java/com/example/requirementrag/web/RequirementGraphPageTest.java
src/test/resources/requirement-graph/
```

Any code change in these areas must update tests and the current `CHANGELOG.md` version section according to repository policy.

## 21. Rollout Strategy

### Stage 1: Disabled by default

```text
REQUIREMENT_GRAPH_ENABLED=false
REQUIREMENT_GRAPH_RETRIEVAL_ENABLED=false
REQUIREMENT_GRAPH_HYBRID_RETRIEVAL_ENABLED=false
```

Use synthetic and internal test projects only.

### Stage 2: Build-only canary

Enable graph construction for selected projects, but do not use graph results in user-facing retrieval. Measure:

- extraction quality;
- cost;
- evidence resolution;
- window failure rate;
- reviewer workload.

### Stage 3: Shadow retrieval

Run graph retrieval in parallel with the existing requirement retrieval path. Compare:

- top-k evidence overlap;
- answer context precision;
- latency;
- version correctness;
- unresolved warning rate.

### Stage 4: Explicit opt-in retrieval

Allow selected users or projects to choose graph retrieval modes. Keep fallback enabled.

### Stage 5: Default for approved projects

Only projects with:

- privacy policy;
- acceptable quality score;
- review capacity;
- stable source versions;
- rollback owner;

may enable graph retrieval by default.

## 22. Rollback Strategy

Rollback must be possible at each layer:

1. Disable graph retrieval while keeping graph data.
2. Route all queries back to Qdrant requirement retrieval.
3. Stop new graph builds while allowing review of existing drafts.
4. Mark affected graph snapshots stale if the schema or ontology changed.
5. Preserve audit and evidence data for investigation.
6. Do not delete graph data as part of a normal feature rollback.

## 23. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Entity over-merging | Incorrect cross-section relationships | Type constraints, context keys, alias review, merge audit |
| Entity under-merging | Fragmented graph and poor recall | Alias sets, local context, deterministic normalization |
| Unsupported relation | False business logic | Evidence requirement, ontology validation, publication gate |
| Evidence drift | Misleading citations | Content hash and offset validation |
| Long document cost | High model spend and latency | Structure-aware windows, cache, budgets, incremental rebuild |
| Provider outage | Incomplete graph | Retry, partial failure status, resumable jobs |
| Process restart | Lost jobs | Persistent build-job table and heartbeats |
| Stale snapshot | Outdated answer | Source revision binding and stale filtering |
| Graph retrieval regression | Lower answer quality | Shadow retrieval, fallback, benchmark gate |
| Privacy leakage | Compliance incident | Project policy, redaction, access checks, log minimization |
| Reviewer overload | Slow publication | Prioritized review queue and conflict clustering |
| Schema drift | Broken consumers | Versioned contracts and compatibility tests |

## 24. Open Decisions

The following decisions must be resolved before production default-on rollout:

1. Should the graph store remain SQLite for single-node deployments, or move to PostgreSQL for multi-instance operation?
2. Should claim-evidence associations be migrated from JSON arrays to a normalized table in the next schema version?
3. Which model is approved for extraction, query planning, evidence verification, and answer generation?
4. Is external model transmission allowed for every business project, or only for explicitly approved projects?
5. What is the minimum reviewer role required to publish a graph snapshot?
6. Which claim types, if any, may be published without manual verification?
7. Should `MIX` be the default mode for selected query intents, or should all mode selection remain explicit?
8. What are the retention requirements for rejected claims, raw evidence, and audit records?
9. Which requirements are sensitive enough to require evidence redaction in the UI?
10. Which downstream RAG endpoints may consume `INFERRED` claims?

## 25. Definition of Done

The overall feature is ready for production rollout only when all of the following are true:

### Contracts

- ontology, schema, evidence, claim, snapshot, API, and error contracts are versioned;
- compatibility behavior is documented;
- open decisions affecting safety are resolved.

### Build

- windows are deterministic and structure-aware;
- successful window results are persisted before continuation;
- cancellation preserves a resumable snapshot;
- process restart does not lose build state;
- transient failures retry within budget;
- partial failures are visible and actionable.

### Evidence and governance

- every publishable claim has a valid evidence association;
- missing or changed evidence blocks publication;
- claim review operations are authorized and audited;
- published snapshots are immutable and versioned;
- stale snapshots are excluded by default.

### Retrieval

- `NAIVE`, `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX` semantics are documented;
- query routing is bounded and observable;
- graph, text, and evidence candidates can be fused;
- claim status and version filters are enforced;
- retrieval falls back safely when graph quality is insufficient.

### Quality

- unit, integration, and evaluation tests pass;
- entity/relation/evidence thresholds pass;
- unsupported published claim rate is zero;
- unresolved published evidence count is zero;
- retrieval quality does not regress against the baseline.

### Operations

- build, query, cost, quality, and fallback metrics exist;
- privacy policy is enforced;
- rollout flags and rollback procedures are tested;
- reviewer and operator runbooks are available.

## 26. Recommended Next Work Items

The recommended implementation order is:

1. Persist asynchronous build jobs and durable snapshot IDs.
2. Add missing claim-evidence integrity checks and normalized associations.
3. Make ontology validation reject unspecified type combinations.
4. Add `NAIVE` and `MIX` retrieval modes.
5. Add a deterministic query planner before introducing a model-based planner.
6. Add unified text/entity/relation/evidence reranking.
7. Expand the evaluation corpus and add version-accuracy metrics.
8. Implement incremental updates and stale propagation.
9. Complete the evidence-first review workspace.
10. Run shadow retrieval and controlled project-level rollout.

## 27. References

- Existing detailed plan: `docs/requirement-semantic-graph-development-plan.md`
- Official LightRAG repository: `HKUDS/LightRAG`
- LightRAG paper: `arXiv:2410.05779`, “LightRAG: Simple and Fast Retrieval-Augmented Generation”
- Project retrieval and version knowledge guide: `.trellis/spec/backend/retrieval-and-version-knowledge.md`
- Project quality guidelines: `.trellis/spec/guides/quality-guidelines.md`

