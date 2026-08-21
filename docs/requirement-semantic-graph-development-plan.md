# Requirement Semantic Graph Extraction Development Plan

**Status:** Implemented in controlled rollout mode  
**Owner:** NEXUS Retrieval / Knowledge Platform  
**Scope:** Requirement-document semantic graph extraction, review, publication, and retrieval  
**Last updated:** 2026-08-20

## 1. Executive Summary

NEXUS currently provides an experimental requirement semantic graph capability. It extracts entities and relations from one requirement parent chunk at a time, stores a version-scoped SQLite snapshot, and resolves graph evidence back to Qdrant.

The current implementation is a useful prototype, but it is not yet safe to treat as a product fact layer. The most important gaps are:

- long parent blocks are truncated from the end without coverage diagnostics;
- extraction is isolated per chunk, so cross-section references and relations are lost;
- evidence identifies a parent chunk but not the exact supporting span;
- model uncertainties and conflicting statements are discarded;
- publication is snapshot-level rather than entity/relation-level review;
- graph retrieval is currently lexical `LIKE` matching followed by traversal, not semantic graph retrieval;
- a single failed model call fails the entire build without resumable chunk state;
- there is no quality benchmark or cost/privacy governance contract.

This plan hardens the feature in five stages:

1. Define the ontology, evidence, lifecycle, quality, security, and operational contracts.
2. Replace destructive truncation with structure-aware windows and resumable chunk jobs.
3. Add cross-chunk entity resolution, relation consolidation, uncertainty, conflict, and evidence-span handling.
4. Add entity/relation review APIs and audit-safe publication.
5. Add hybrid semantic retrieval, evaluation gates, and controlled rollout.

The existing requirement retrieval path remains the source of truth during all stages. The semantic graph is an auxiliary, versioned, reviewable projection and must not silently replace the Qdrant requirement retrieval path.

## Implementation status

The current implementation covers the safe foundation and review workflow described below while keeping all rollout flags disabled by default:

- schema-v2 graph snapshots with legacy constructors/read compatibility;
- bounded overlapping windows, exact evidence spans, window coverage and persisted window results;
- retry/budget/cancel/resume behavior through `/api/requirement-graphs/builds*`;
- uncertainty and relation-variant conflict persistence;
- claim-level verify/reject/patch APIs with audit records and a publication gate;
- Local/Global/optional Hybrid retrieval, bounded neighborhood/path APIs, and explicit truncation/evidence warnings;
- project privacy policy configuration, synthetic corpus quality gate, and Micrometer build/search metrics;
- evidence-first review UI at `/requirement-graph.html`.
- claim merge/split operations, explicit verify/reject aliases, neighborhood/path APIs, and bounded asynchronous job status.
- project policy binding through `REQUIREMENT_GRAPH_PRIVACY_POLICY_REQUIRED` and optional `app.rag.requirement-graph.project-policies.<projectId>` entries.

The production rollout remains opt-in: `REQUIREMENT_GRAPH_ENABLED=false`, `REQUIREMENT_GRAPH_RETRIEVAL_ENABLED=false`,
`REQUIREMENT_GRAPH_HYBRID_RETRIEVAL_ENABLED=false`, and `REQUIREMENT_GRAPH_PRIVACY_POLICY_REQUIRED=false` by default.

## 2. Goals

### 2.1 Product goals

- Build a document-level semantic graph from requirement documents while preserving section and version boundaries.
- Make every extracted fact traceable to exact source spans and stable requirement evidence IDs.
- Distinguish model-extracted, model-inferred, human-verified, rejected, stale, and unavailable facts.
- Support cross-section entity references and relations without allowing unsupported facts to become published truth.
- Make graph construction resumable, observable, bounded in cost, and safe under provider failures.
- Provide useful hybrid graph retrieval for entity, relation, neighborhood, and impact-style requirement questions.
- Preserve compatibility with existing requirement collections, snapshots, access control, and evidence contracts.

### 2.2 Engineering goals

- Keep all graph data isolated by business project, document, requirement version, source revision, and ontology/prompt version.
- Make schema changes forward-compatible and migration-safe.
- Add deterministic tests for validation, identity, evidence resolution, failure recovery, and publication authorization.
- Add a labeled evaluation corpus before enabling the feature for production users.

## 3. Non-goals

- Replacing dense+sparse Qdrant requirement retrieval.
- Automatically publishing model output as confirmed product policy.
- Building a general-purpose enterprise knowledge graph for arbitrary external data.
- Inferring facts that are not supported by the requirement corpus.
- Performing code-symbol linking in this phase. Requirement-to-code linkage remains a later reviewed projection.
- Introducing repository-level ACLs. Graph access continues to inherit business-project authorization.

## 4. Current State and Confirmed Gaps

| Area | Current behavior | Risk | Target change |
|---|---|---|---|
| Input sizing | `RequirementGraphBuildService` truncates each parent text to `maxInputChars` | Tail content such as acceptance criteria and edge cases disappears silently | Structure-aware windows with overlap and coverage diagnostics |
| Extraction scope | One LLM call per parent chunk | Cross-section references and relations are not visible to the extractor | Local extraction followed by document-level resolution |
| Entity identity | `type + whitespace-stripped lowercase name` | Same-name entities may be incorrectly merged; aliases do not resolve variants | Candidate generation plus context-aware entity resolution |
| Relation identity | `source + type + target`; first statement is retained | Conditions, exceptions, and conflicting statements are lost | Relation claims with variants, conditions, conflicts, and evidence |
| Evidence | Exact `String.contains` quote check and parent-level evidence ID | No exact location; repeated phrases are ambiguous | Quote normalization, offsets, section identity, and resolution status |
| Uncertainty | Extraction returns `uncertainties`, but build does not persist them | Reviewers cannot see model doubt | Persist uncertainty records and expose them in API/UI |
| Review | Snapshot-level publish endpoint | No entity/relation correction or reviewer audit trail | Claim-level review and immutable publication audit |
| Failure handling | One failed extraction fails the whole build | Expensive rebuilds and no resume path | Per-window state, retries, partial failure status, resume |
| Search | SQLite `LIKE` candidate search plus graph traversal | Synonyms and semantically related queries are missed | Lexical + vector + graph hybrid retrieval |
| Scale | Loads up to 10,000 entities and 10,000 relations into memory | Large graphs are silently truncated | Bounded neighborhood queries and explicit pagination/truncation |
| Evaluation | Format-validation tests only | No evidence that graph quality improves user outcomes | Gold corpus, metrics, regression gate |
| Governance | Full requirement text is sent to the configured ChatClient | Data residency, retention, and model policy are unspecified | Project/model policy, redaction, audit, and budget controls |

## 5. Target Contracts

### 5.1 Ontology contract

The initial ontology should remain small and business-readable.

#### Entity types

- `MODULE`
- `FEATURE`
- `BUSINESS_OBJECT`
- `ACTOR`
- `STATE`
- `RULE`
- `ACCEPTANCE_CRITERION`
- `EXTERNAL_SYSTEM`
- `VALUE_OR_PARAMETER`

#### Relation types

- `CONTAINS`
- `DEPENDS_ON`
- `AFFECTS_MODULE`
- `TRANSITIONS_TO`
- `REQUIRES`
- `VERIFIED_BY`
- `CONFLICTS_WITH`
- `EXCEPTION_TO`
- `USES`

Every relation type must define:

- direction;
- allowed source and target entity types;
- whether it may cross sections or documents;
- whether conditions are required;
- whether model inference is allowed;
- whether publication requires manual verification.

The ontology must be versioned independently from the prompt. An ontology change must invalidate or migrate affected graph snapshots rather than silently reinterpreting old records.

### 5.2 Claim and evidence contract

Entities and relations are claims, not automatically confirmed facts.

```text
ClaimStatus:
  EXTRACTED       model output passed structural validation
  INFERRED        model output requires cross-section inference
  VERIFIED        human-reviewed and accepted
  REJECTED        human-reviewed and rejected
  CONFLICTED      contradicted by another claim
  STALE           source revision no longer matches
  UNAVAILABLE     source evidence cannot be resolved
```

Every claim must retain:

- `claimId`;
- snapshot ID;
- source document and requirement version;
- source revision and content hash;
- entity/relation payload;
- extraction model and prompt/ontology versions;
- confidence plus confidence method;
- claim status;
- uncertainty IDs;
- conflict set IDs;
- one or more evidence spans.

An evidence span should contain:

```json
{
  "evidenceId": "requirement:...",
  "filename": "requirements.md",
  "parentId": "section-12",
  "sectionPath": "Orders / Cancellation",
  "contentHash": "sha256:...",
  "quote": "Cancelling an order releases reserved inventory.",
  "startOffset": 1842,
  "endOffset": 1896,
  "resolutionStatus": "RESOLVED"
}
```

A parent-level evidence ID may remain for backward compatibility, but it must not be the only evidence representation for new snapshots.

### 5.3 Snapshot lifecycle contract

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

- `PUBLISHED` may contain only `VERIFIED` claims, except for explicitly configured low-risk claim types.
- `PARTIAL_FAILED` must list failed windows and cannot be published by default.
- A source revision or ontology version change marks the previous published graph `STALE`.
- Publication is immutable from a user perspective. Corrections create a new draft snapshot or a new claim revision.
- Every status transition records actor, timestamp, reason, and request/audit ID.

### 5.4 Retrieval contract

Graph retrieval must return:

- the selected graph snapshot;
- matched entities and relations;
- path and hop metadata;
- claim status;
- confidence and uncertainty indicators;
- resolved evidence spans;
- explicit warnings when evidence is unavailable, stale, or truncated;
- pagination and total/truncated metadata.

If evidence cannot be resolved, the response must not silently look like a fully resolved success response.

## 6. Target Architecture

```text
Requirement snapshot / Qdrant payload
        |
        v
Structure-aware window planner
        |
        v
Window extraction workers
  - schema-constrained JSON
  - timeout / retry / budget
  - exact quote validation
        |
        v
Local claim store
  - entities
  - relations
  - uncertainties
  - evidence spans
        |
        v
Document resolver
  - entity candidate matching
  - alias and context resolution
  - coreference candidates
  - relation consolidation
  - conflict detection
        |
        v
Review draft snapshot
  - claim-level review
  - evidence viewer
  - audit log
        |
        v
Published graph projection
        |
        +--> lexical retrieval
        +--> vector retrieval
        +--> graph traversal / path ranking
        +--> Qdrant evidence resolution
```

The resolver must never create a cross-window relation merely because two names are similar. Cross-window links require either:

- an explicit source quote in the participating windows;
- a deterministic document structure rule; or
- an `INFERRED` claim that is blocked from publication until verified.

## 7. Data Model Changes

### 7.1 New or extended records

#### `requirement_graph_snapshot`

Add:

- `ontology_version`
- `schema_version`
- `coverage_ratio`
- `window_count`
- `succeeded_window_count`
- `failed_window_count`
- `warning_count`
- `build_id`
- `published_by`
- `publication_reason`
- `stale_at`

#### `requirement_graph_window`

Suggested fields:

- `id`
- `snapshot_id`
- `filename`
- `parent_id`
- `section_path`
- `window_index`
- `start_offset`
- `end_offset`
- `content_hash`
- `status`
- `attempt_count`
- `last_error_code`
- `started_at`
- `completed_at`

#### `requirement_graph_entity`

Add:

- `claim_status`
- `normalized_by`
- `context_key`
- `first_seen_window_id`
- `last_seen_window_id`
- `uncertainty_ids`
- `conflict_set_ids`
- `reviewer`
- `reviewed_at`
- `review_reason`

#### `requirement_graph_relation`

Add:

- `claim_status`
- `condition`
- `scenario`
- `statement_variants`
- `uncertainty_ids`
- `conflict_set_ids`
- `review_reason`

#### `requirement_graph_evidence`

Move evidence from an implicit ID convention into a first-class table or equivalent durable record so that exact spans, normalization, and resolution state can be audited.

### 7.2 Migration requirements

- Add schema versioning before adding claim-level tables.
- Keep existing snapshots readable.
- Treat old parent-level evidence as `LEGACY_PARENT_ONLY`.
- Do not mark legacy graphs as `VERIFIED` automatically.
- Add a rebuild command that creates a new graph snapshot without deleting the old one.
- Add a migration report containing migrated rows, skipped rows, and unresolved evidence.

## 8. Extraction Pipeline

### Phase A: Structure-aware windowing

1. Read materialized requirement entries and source metadata.
2. Split on headings, paragraphs, list items, tables, and acceptance-criteria boundaries.
3. Keep windows below the configured model budget.
4. Apply controlled overlap so references near boundaries remain visible.
5. Record exact offsets and a normalized content hash.
6. Emit a coverage report before extraction starts.

A window must never be silently truncated. If a single logical table or list exceeds the budget, split it with explicit continuation metadata.

### Phase B: Constrained local extraction

Each worker receives:

- document metadata;
- section path and heading;
- window text;
- allowed entity/relation types;
- output schema;
- evidence quote rules;
- a statement that unsupported facts must be returned as uncertainty, not invented.

Worker behavior:

- bounded timeout;
- bounded retry count;
- exponential backoff for retryable provider errors;
- idempotency key based on snapshot/window/model/prompt/ontology versions;
- no retry for schema-invalid or evidence-invalid output without repair prompt;
- per-window result persisted before moving to the next window.

### Phase C: Validation and normalization

Validate:

- schema shape;
- enum values;
- local ID uniqueness;
- relation endpoint existence;
- confidence range;
- evidence quote containment;
- quote-to-window offsets;
- maximum claims per window;
- forbidden inference patterns.

Normalize only deterministic formatting, such as whitespace and Unicode normalization. Do not treat formatting normalization as semantic entity resolution.

### Phase D: Document-level resolution

Build candidate links using:

- exact normalized names;
- aliases;
- entity type compatibility;
- section/module context;
- parent-child structure;
- lexical similarity;
- vector similarity;
- co-occurrence and relation compatibility.

Resolution outcomes:

- `AUTO_MERGED` for high-confidence deterministic matches;
- `CANDIDATE_MERGE` for ambiguous matches requiring review;
- `SEPARATE` when context indicates distinct entities;
- `UNRESOLVED` when evidence is insufficient.

### Phase E: Conflict and uncertainty detection

Detect:

- opposite state transitions;
- mutually exclusive rules;
- different values for the same parameter and scenario;
- contradictory relation statements;
- source revisions that remove prior evidence.

Conflicts must be represented explicitly. Do not overwrite one claim with the highest-confidence claim.

### Phase F: Review and publication

Review UI/API must support:

- claim list with filters by status, confidence, uncertainty, and conflict;
- evidence span preview;
- accept, reject, edit, merge, split, and defer actions;
- reviewer and reason capture;
- snapshot diff against the previous published version;
- publish gate that rejects unresolved mandatory claims.

## 9. API Plan

### 9.1 Build and job status

```http
POST /api/requirement-graphs/build
GET  /api/requirement-graphs/builds/{buildId}
POST /api/requirement-graphs/builds/{buildId}/resume
POST /api/requirement-graphs/builds/{buildId}/cancel
```

The build response should return a job/build ID rather than block on all model calls.

### 9.2 Snapshot and claim review

```http
GET   /api/requirement-graphs/snapshots
GET   /api/requirement-graphs/snapshots/{snapshotId}
GET   /api/requirement-graphs/snapshots/{snapshotId}/claims
PATCH /api/requirement-graphs/entities/{entityId}
PATCH /api/requirement-graphs/relations/{relationId}
POST  /api/requirement-graphs/claims/{claimId}/verify
POST  /api/requirement-graphs/claims/{claimId}/reject
POST  /api/requirement-graphs/claims/{claimId}/merge
POST  /api/requirement-graphs/claims/{claimId}/split
POST  /api/requirement-graphs/snapshots/{snapshotId}/publish
```

### 9.3 Retrieval

```http
POST /api/requirement-graphs/search
GET  /api/requirement-graphs/{snapshotId}/neighborhood/{entityId}
GET  /api/requirement-graphs/{snapshotId}/paths
```

Search request additions:

```json
{
  "projectId": "immortal",
  "documentId": "requirements",
  "requirementVersion": "5.1",
  "query": "what happens to inventory after order cancellation",
  "mode": "HYBRID",
  "statuses": ["PUBLISHED", "VERIFIED"],
  "maxHops": 2,
  "limit": 20,
  "includeUnresolved": false
}
```

## 10. Error Handling and Operational Controls

### 10.1 Error categories

Use stable error codes rather than exposing provider exceptions:

- `GRAPH_INPUT_EMPTY`
- `GRAPH_WINDOW_TOO_LARGE`
- `GRAPH_MODEL_TIMEOUT`
- `GRAPH_MODEL_RATE_LIMITED`
- `GRAPH_MODEL_UNAVAILABLE`
- `GRAPH_SCHEMA_INVALID`
- `GRAPH_EVIDENCE_INVALID`
- `GRAPH_WINDOW_FAILED`
- `GRAPH_PARTIAL_FAILURE`
- `GRAPH_EVIDENCE_UNAVAILABLE`
- `GRAPH_SNAPSHOT_STALE`
- `GRAPH_PUBLICATION_BLOCKED`

### 10.2 Budgets

Per build, enforce:

- maximum source characters;
- maximum windows;
- maximum model calls;
- maximum retry calls;
- maximum wall-clock time;
- maximum estimated token cost;
- maximum concurrent workers per provider/model.

Expose budget usage in build status and audit logs.

### 10.3 Failure semantics

- Retry provider timeouts, 429, and transient 5xx errors.
- Do not retry malformed model output indefinitely.
- Persist failed window error codes and sanitized diagnostics.
- Allow resume from the first incomplete window.
- Keep the previous published snapshot readable during a new build.
- Never delete a published graph as part of a failed rebuild.

## 11. Security and Privacy

Before enabling extraction for a business project, require a policy containing:

- allowed model/provider;
- data residency and retention classification;
- whether external transmission is allowed;
- redaction rules;
- maximum document sensitivity;
- whether prompts and responses may be stored;
- who may build, review, and publish.

Logs must contain IDs, status, duration, and sanitized error codes, but never raw requirement text, evidence quotes, API keys, or full model responses.

The existing business-project authorization boundary must apply to:

- build;
- snapshot listing;
- claim review;
- evidence resolution;
- graph search;
- publication;
- export and evaluation data.

## 12. Implementation Phases

### Phase 0: Contract and evaluation foundation

**Deliverables**

- ontology and relation compatibility matrix;
- claim/evidence/status contract;
- schema versioning design;
- 20–50 document/window gold evaluation cases;
- baseline metrics for current implementation;
- model/privacy/cost policy configuration.

**Exit criteria**

- reviewable contract approved;
- baseline false-positive and false-negative examples documented;
- no production rollout yet.

### Phase 1: Safe windowing and evidence spans

**Deliverables**

- structure-aware window planner;
- window persistence and coverage diagnostics;
- exact evidence offsets and resolution status;
- backward-compatible legacy evidence adapter;
- regression tests for long sections, tables, lists, and repeated quotes.

**Exit criteria**

- no silent truncation;
- every accepted claim has at least one resolvable span or an explicit unavailable status;
- old snapshots remain readable.

### Phase 2: Resumable extraction and document resolution

**Deliverables**

- asynchronous or bounded worker execution;
- retries, budgets, idempotency, and resume;
- cross-window entity candidate resolution;
- uncertainty and conflict persistence;
- partial-failure status and build diagnostics.

**Exit criteria**

- one window failure does not destroy previous published data;
- resume does not repeat successful windows;
- unresolved cross-window links remain non-publishable.

### Phase 3: Claim-level review and audit-safe publication

**Deliverables**

- entity/relation review APIs;
- evidence viewer payloads;
- audit log and actor propagation;
- merge/split/edit/reject operations;
- snapshot diff and publication gate.

**Exit criteria**

- a reviewer can correct a wrong entity or relation without rebuilding the whole document;
- every published claim has reviewer, timestamp, reason, and evidence status;
- publication of unresolved mandatory claims is rejected.

### Phase 4: Hybrid graph retrieval

**Deliverables**

- entity/relation embeddings;
- lexical + vector candidate retrieval;
- bounded neighborhood/path queries;
- query result warnings, pagination, and truncation metadata;
- retrieval evaluation against the baseline requirement pipeline.

**Exit criteria**

- graph retrieval improves at least one agreed business query class without reducing evidence correctness;
- no silent 10,000-row in-memory truncation;
- evidence resolution failures are visible to callers.

### Phase 5: Controlled rollout

**Deliverables**

- feature flag per business project;
- shadow-build and shadow-query mode;
- dashboards for latency, cost, failure, coverage, review throughput, and quality;
- rollback procedure;
- operator and reviewer documentation.

**Exit criteria**

- shadow period completed;
- cost and error budgets met;
- rollback tested;
- production enablement approved per business project.

## 13. Test Plan

### 13.1 Unit tests

- window boundary and overlap calculation;
- Unicode and whitespace normalization;
- quote-to-offset resolution;
- evidence hash mismatch;
- schema and enum validation;
- relation endpoint validation;
- entity candidate scoring;
- conflict detection;
- status transition rules;
- publication gate;
- retry classification and budget accounting.

### 13.2 Integration tests

- build from a materialized requirement snapshot;
- fallback to Qdrant with explicit warning;
- resumable build after a failed window;
- partial failure preserving the previous published graph;
- exact evidence retrieval;
- stale snapshot detection;
- authorization on every graph endpoint;
- reviewer identity propagation;
- schema migration and legacy snapshot readability.

### 13.3 Evaluation tests

Use a fixed, versioned corpus containing:

- repeated entity names in different modules;
- aliases and abbreviations;
- cross-section pronouns;
- contradictory rules;
- long acceptance-criteria sections;
- tables and nested lists;
- requirement version additions/removals;
- evidence phrases repeated in multiple sections.

Required metrics:

| Metric | Initial gate |
|---|---:|
| Entity precision | >= 0.85 |
| Relation precision | >= 0.80 |
| Evidence span validity | >= 0.98 |
| Unsupported published claim rate | 0 |
| Published claim with unresolved evidence | 0 |
| Resume duplicate-call rate | <= 1% |
| Build failure recovery rate | >= 95% for retryable failures |

The initial thresholds are proposals and must be calibrated against the gold corpus before rollout.

## 14. Observability

Record metrics by business project, document, version, model, and snapshot:

- source characters and window count;
- successful/failed windows;
- retry count;
- model latency;
- input/output tokens if available;
- estimated cost;
- entities and relations per window;
- candidate merges and unresolved links;
- conflict count;
- evidence resolution rate;
- review acceptance/rejection rate;
- graph search latency and truncation rate;
- published claim count;
- stale snapshot count.

All dashboards must distinguish:

- no graph exists;
- graph build is running;
- graph build partially failed;
- graph exists but evidence is unavailable;
- graph is stale;
- graph is published and verified.

## 15. File Impact Map

The implementation should be kept separated by responsibility:

| Area | Expected files |
|---|---|
| Extraction contract | `src/main/java/com/example/requirementrag/requirement/graph/RequirementGraphModels.java` |
| Windowing | New `RequirementGraphWindowPlanner` / `RequirementGraphWindow` classes under `requirement/graph` |
| Model calls | `RequirementGraphExtractionService.java` |
| Resolution | New `RequirementGraphEntityResolver` and `RequirementGraphConflictService` |
| Persistence | `SQLiteRequirementGraphStore.java` plus schema migration support |
| Build orchestration | `RequirementGraphBuildService.java` or a separate job service |
| Retrieval | `RequirementGraphSearchService.java` and embedding adapter |
| API/auth | `RequirementGraphController.java` and audit/permission services |
| UI | Graph review page and shared API client contracts |
| Tests | `src/test/java/com/example/requirementrag/requirement/graph/**` and web/evaluation tests |
| Documentation | This plan, backend retrieval contract, operator/reviewer guide |

Do not place review state, evidence resolution, or provider retry logic in the controller.

## 16. Rollout and Backward Compatibility

1. Keep `REQUIREMENT_GRAPH_ENABLED=false` by default.
2. Add shadow build mode that stores drafts but never affects normal retrieval.
3. Enable one internal business project at a time.
4. Compare graph-assisted answers against the existing requirement retrieval baseline.
5. Publish only verified claims with resolved evidence.
6. Keep the previous published snapshot during every rebuild.
7. Disable graph retrieval independently from graph building if quality or cost degrades.
8. Roll back by switching the feature flag and retaining snapshots for diagnosis.

No graph result may alter the primary requirement answer unless the graph claim is published, evidence-resolved, version-matched, and allowed by the retrieval policy.

## 17. Open Decisions Before Implementation

The following decisions must be made before Phase 1 starts:

1. Which entity and relation types are mandatory for the first business workflow?
2. Are inferred cross-section links allowed in draft-only mode, or should they be excluded entirely?
3. Which model providers and data classifications are allowed per business project?
4. Should graph construction be asynchronous through the existing job infrastructure or a dedicated queue?
5. What is the maximum acceptable build time and cost per document/version?
6. Does the first review UI need edit/merge/split, or only verify/reject?
7. Which existing requirement evidence schema fields can be reused for offsets and section metadata?
8. What minimum evaluation score is required before enabling hybrid graph retrieval?

## 18. Definition of Done

The semantic graph feature is ready for controlled production rollout only when:

- the ontology and lifecycle contract is versioned;
- no input is silently truncated;
- extraction is resumable and budgeted;
- cross-window resolution and uncertainty are persisted;
- every published claim has exact, resolvable evidence;
- reviewers can correct claims and publication records the actor;
- graph search uses hybrid lexical/vector/graph retrieval with bounded results;
- legacy snapshots remain readable;
- authorization and privacy policies are enforced;
- the gold evaluation corpus passes the agreed quality gates;
- dashboards and rollback procedures are verified;
- the existing requirement retrieval path remains unchanged by default.
