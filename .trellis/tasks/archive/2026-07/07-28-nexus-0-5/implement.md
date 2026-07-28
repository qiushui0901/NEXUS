# NEXUS 0.5 Implementation Plan

1. Baseline
   - Preserve and inspect existing evidence-citation changes.
   - Record focused test baseline.
2. Unified retrieval
   - Extend shared retrieval contract only as needed for review full-version context.
   - Migrate `DoubtReviewService` routing/search to `RetrievalPipeline`.
   - Remove hard-coded product/version prompt assumptions.
   - Add retrieval/review regression tests.
3. Draft lifecycle
   - Add status, audit, repository, transition service, and APIs.
   - Connect build output to initial metadata.
   - Add transition/path/access tests.
4. Publish and rollback
   - Add approved-only atomic source publication and Wiki generation.
   - Retain previous snapshots and add one-step/history rollback.
   - Add failure atomicity and rollback tests.
5. Security/configuration
   - Add explicit local auth-off profile and fail-safe shared defaults.
   - Add auth configuration validation and tests.
6. Quality gate
   - Expand retrieval evaluation fixtures/cases.
   - Run focused tests, full Maven verify, and `git diff --check`.
   - Update README, changelog, and executable specs where behavior changed.
