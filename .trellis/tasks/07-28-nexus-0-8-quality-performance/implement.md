# NEXUS 0.8 Implementation Plan

## Phase 1 — Configuration and shared primitives

- [x] Extend retrieval/cache properties with backward-compatible defaults.
- [x] Add bounded TTL cache primitive and tests.
- [x] Add retrieval executor bean and lifecycle management.

## Phase 2 — Unified retrieval pipeline

- [x] Add requirement rerank policy/service with BGE and optional LLM stages.
- [x] Run requirement search, corpus scroll and code search concurrently with per-stage timeouts.
- [x] Add retrieval result cache and fingerprinted, scope-safe key.
- [x] Remove duplicate reranking from `DoubtReviewService`.
- [x] Add three-profile, timeout, fallback, cache isolation and latency tests.

## Phase 3 — Embedding and Wiki caches

- [x] Decorate embedding calls with model-scoped TTL cache.
- [x] Cache Wiki indexes/pages and invalidate a published project/version.
- [x] Add hit/miss, expiry, capacity and invalidation tests.

## Phase 4 — Evaluation and CI gates

- [x] Expand the retrieval evaluation JSONL to at least 50 cases across six required categories.
- [x] Add dataset coverage and stable-label validation.
- [x] Add deterministic Recall@10/MRR regression gate and parallel P95 benchmark test.
- [x] Configure JaCoCo minimum coverage check.
- [x] Add dependency vulnerability scan to CI.

## Phase 5 — MCP 0.8

- [x] Add `nexus_conflict_check` through the existing invocation boundary.
- [x] Add implementation/review/impact MCP prompt templates.
- [x] Update MCP/app version metadata and contract documentation.
- [x] Add tool and prompt contract tests.

## Phase 6 — Release closeout

- [x] Run targeted tests during implementation.
- [x] Run `./mvnw verify` and smoke tests.
- [x] Update roadmap checkboxes only for verified acceptance items.
- [x] Record final test evidence and remaining external-dependency caveats.

## Verification record

- 2026-07-28: Java 21 `./mvnw -q verify` passed, 191 tests.
- JaCoCo line coverage: 61.90% (3,965 / 6,406), enforced minimum 35%.
- MCP HTTP smoke: 10 tools, 3 prompts, one authenticated representative tool call.
- Controlled three-branch latency fixture: parallel P95 below 210 ms versus 300 ms sequential baseline.
- Dependency-Check 12.2.2 configuration starts successfully; a complete local NVD sync was not run
  without `NVD_API_KEY`. CI reads the key from the repository secret.
- Live Recall@10/MRR comparison remains pending until a reproducible golden Qdrant/Embedding/BGE
  environment is available; the corresponding roadmap acceptance items remain unchecked.
