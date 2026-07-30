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
- 2026-07-29 formal same-corpus `0.7-baseline → 0.8-rerank` comparison passed all acceptance checks;
  see the Phase 9 verification record below.

## Phase 7 — Authorized real-project calibration

- [x] Select the user-authorized `qiushui-shiguang` repository as a read-only evaluation source.
- [x] Add a sanitized synthetic requirement corpus without configs, credentials, PII or resume content.
- [x] Add an isolated `shiguang-eval` project profile with explicit safe include/exclude boundaries.
- [x] Add 12 stable-label cases across development-plan, requirement-review and code-search paths.
- [x] Make the live evaluation dataset and baseline resources environment-selectable.
- [x] Route development-plan and requirement-review evaluation through the 0.8 unified pipeline.
- [x] Add automated requirement upload, code indexing and MCP evidence-chain smoke commands.
- [x] Execute the live smoke against Qdrant/Embedding/BGE and record the generated evidence IDs.
- [x] Measure a reproducible same-corpus 0.7 baseline and 0.8 result.
- [x] Keep deterministic offline regression mandatory in CI and the external-dependency evaluation behind an explicit switch.

## Phase 7 verification record

- 2026-07-28: authorized live calibration completed against the read-only Shiguang repository and isolated
  `shiguang-eval` collections.
- Pre-fix → post-fix: document Recall@10 `0.900 → 1.000`, code Recall@10 `0.500 → 1.000`,
  MRR@10 `0.516 → 0.863`, mixed both-hit `0.500 → 1.000`, P50 `3777 → 2933 ms`, and
  P95 `8617 → 5888 ms`.
- The post-fix report contains 10/12 failed cases, all 10 classified as infrastructure failures because the
  configured BGE endpoint was unavailable. This warning remains visible as `BGE_RERANK_UNAVAILABLE`;
  retrieval hits were not relabeled as successful to hide the dependency failure.
- MCP smoke passed with requirement evidence `requirement:808b8e9c-f3a6-382a-a3d5-8916006a2348`, code
  evidence `code:ded17030-1875-3e64-b29a-9519ef5d7171`, and source evidence
  `code:887d37e343ad1b269fd0cd1f1b8b05e6` for repository-relative
  `shiguang-auth/src/main/java/com/quanshiguang/shiguang/auth/service/impl/AuthServiceImpl.java`.
- The smoke artifact is written to `target/retrieval-evaluation/mcp-smoke.json` and contains only project ID,
  evidence IDs, repository-relative source path, and warning codes—no credentials, endpoints, or source text.
- Commit `97cbf42` contains the 0.7 evaluator and generic dataset, but not the Shiguang profile, sanitized corpus,
  or Shiguang golden dataset. The committed `retrieval-baseline-v0.7.json` is a generic threshold fixture rather
  than a measurement from this repository. Therefore the 2026-07-28 calibration could not provide a reproducible
  same-corpus/same-dependency 0.7 result; this historical limitation was closed by the formal 2026-07-29 run.
- 2026-07-28 final Java 21 `./mvnw -q verify` passed: 201 tests, 0 failures, 0 errors, 0 skipped. JaCoCo line coverage was 65.25% (4,275 / 6,552), above the enforced 35% minimum.
- `python3 -m py_compile tools/shiguang-eval.py`, `bash -n scripts/run-shiguang-eval.sh`, and `git diff --check` passed. The untracked smoke runner has no standalone Python test harness; its response parsing, redacted persistence, and evidence-chain assertions were exercised by the successful live MCP smoke recorded above.

## Phase 8 — Local Transformers BGE reranker endpoint

- [x] Confirm Ollama Embedding is healthy and document that `/api/embed` is not a rerank contract.
- [x] Add a Python 3.11 Transformers service compatible with `HttpBgeReranker` request/response JSON.
- [x] Add isolated dependency/bootstrap scripts, health/contract checker, and HTTP contract unit tests.
- [x] Verify Python compilation, four HTTP contract tests, shell syntax, and `git diff --check`.
- [x] Download/load the real `BAAI/bge-reranker-v2-m3` weights and pass the live contract checker.
- [x] Re-run the relevant Java/full quality gate after the live endpoint is verified.

### Phase 8 diagnosis

- The installed Ollama model `hans-tech/bge-reranker-v2-m3:260522` advertises completion capability;
  Ollama 0.11.10 does not expose `/api/rerank`. Its `/api/embed` output is an embedding vector, not the
  per-candidate `index`/`score` array consumed by NEXUS.
- NEXUS therefore keeps its existing Java client contract and uses a separate local sequence-classification
  runtime. Transformers downloads Hugging Face weights separately instead of trying to read Ollama model blobs.
- The 2026-07-28 live evaluation remains historical evidence of the dependency failure; it was subsequently
  rerun with the verified endpoint on 2026-07-29 without changing the golden labels or success criteria.
- 2026-07-29: Python 3.11 environment installed PyTorch 2.11.0 and Transformers 4.57.6; the 2.27 GB
  `BAAI/bge-reranker-v2-m3` weights loaded successfully on CPU. `/health` returned `UP`, and the live checker
  confirmed the NEXUS array contract plus matching-passage-first behavior (`contract: PASS`).
- 2026-07-29 final verification: four Python HTTP contract tests, Python compilation, and shell syntax checks
  passed. Added `HttpBgeRerankerTest` and `DefaultRequirementRerankerTest` (three Java test methods total) to
  cover the HTTP request/response contract, authorization behavior, ranking/top-K handling, and stable
  `BGE_RERANK_UNAVAILABLE` degradation. The focused Java test set passed on Java 21.
- The full Java 21 `./mvnw -q verify` quality gate passed in an environment permitted to bind an embedded
  Tomcat loopback port. JaCoCo line coverage was 65.38% (4,284 / 6,552), above the 35% gate. The earlier
  sandbox-only Tomcat bind failure was an execution-environment restriction, not a product-code failure.
  The live CPU model contract check and `git diff --check` also passed.

## Phase 9 — Reproducible 0.7 → 0.8 evaluation closure

- [x] Freeze the 54-case Shiguang golden dataset, profile distribution and SHA-256.
- [x] Freeze repository commit, sanitized corpus, isolated Qdrant collections, top-k values, timeouts and cache settings.
- [x] Run baseline and rerank variants in independent JVMs with one warm-up and three measured repetitions.
- [x] Exercise the Python/Transformers `BAAI/bge-reranker-v2-m3` endpoint and distinguish calls, successes,
  degradations and no-candidate skips.
- [x] Produce `comparison.json`, `comparison.md`, `manifest.json` and both variant reports.
- [x] Require zero infrastructure failures, healthy BGE, non-regressing Recall@10/MRR@10 and at least 30%
  controlled parallel P95 reduction.
- [x] Run the full Java, Python, shell and diff quality gates after the formal comparison.

### Phase 9 verification record

- Formal comparison generated on 2026-07-29 with classification `formal`; all acceptance checks passed.
- Dataset: 54 cases, SHA-256 `1ff996579588bfc5b859b5a483427c255325265b211e452af5eaff6471a61b18`;
  profiles: `DEVELOPMENT_PLAN=30`, `REQUIREMENT_REVIEW=12`, `WIKI_BUILD=12`.
- Corpus: read-only Shiguang commit `d29f32589c5bd7c190a23eb3a84f27f0069f312f`, project
  `shiguang-eval`, version `shiguang-eval-v1`, isolated collections `requirements_shiguang_eval` and
  `code_shiguang_eval`.
- Fixed evaluation contract: final top-k 10; stage top-k `50/50/40/20/10`; LLM rerank disabled; caches disabled;
  warm-up 1; repetitions 3; BGE timeouts 2s/10s; Qdrant timeouts 2s/5s.
- BGE health: 144 calls, 144 successes, 0 degradations and 18 separately counted no-candidate skips.
  Infrastructure failure cases were 0 for both variants.
- Quality: document Recall@10 `0.354167 → 0.354167`, code Recall@10 `0.738095 → 0.738095`,
  MRR@10 `0.425617 → 0.425617`; no metric regressed. Controlled parallel recall P95 improved
  `315 ms → 112 ms`, a 64.44% reduction versus the required 30%.
- Reproducibility artifacts: `target/retrieval-evaluation/comparison.json`, `comparison.md`, `manifest.json`,
  `0.7-baseline/report.json`, and `0.8-rerank/report.json`. The manifest records no secrets and fingerprints the
  actual reranker virtualenv: Python 3.11.15, PyTorch 2.11.0 and Transformers 4.57.6. Its source fingerprint
  includes the Java BGE client, Shiguang profile, Python service, dependency declaration and startup/check scripts.
- The rare Qdrant request-body I/O failure observed in the prior run is covered by a single bounded retry for
  idempotent read-only `/points/query` calls. HTTP errors, non-I/O serialization failures, writes, deletes and
  upserts are not retried; regression tests cover recovery, retry exhaustion and HTTP 400 behavior.
- Final Java 21 `./mvnw -q verify` passed: 268 tests, 0 failures, 0 errors, 0 skipped. JaCoCo line coverage was
  68.29% (4,582 / 6,710), above the enforced 35% minimum.
- Python gate passed: 16 tests, including strict failure when PyTorch/Transformers versions are unavailable,
  preservation of the virtualenv Python symlink path, and coverage of all executed reranker source artifacts.
  Python compilation, `bash -n` for the evaluation/reranker
  scripts, and `git diff --check` also passed. The initial sandbox-only HTTP test failures were loopback-bind
  restrictions; the same tests passed in the authorized environment.
