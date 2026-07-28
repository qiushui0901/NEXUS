# NEXUS 0.5 release

## Goal

Deliver a safe, project-neutral version-knowledge workflow in which requirement review shares the common retrieval pipeline and generated Wiki drafts can be reviewed, approved, published, audited, and rolled back.

## Requirements

- Requirement doubt review MUST use `RetrievalPipeline` with `REQUIREMENT_REVIEW` instead of maintaining independent project routing and hybrid-search orchestration.
- Review retrieval MUST preserve version isolation, BGE/LLM reranking behavior where useful, safe degradation semantics, and the existing review API response shape.
- Core prompts and defaults MUST derive project, document, and version context from requests/configuration rather than assume the product name “封神” or version `5.1`.
- Knowledge builds MUST remain drafts and MUST NOT modify formal Wiki output before approval.
- Drafts MUST have persisted lifecycle metadata with legal transitions for `DRAFT`, `IN_REVIEW`, `APPROVED`, `REJECTED`, `PUBLISHED`, `SPLIT`, and `MERGED`.
- Draft APIs MUST support list/detail, review transitions with comments, publish, and rollback, with project access and permission checks.
- Publishing MUST accept only approved drafts, copy the reviewed source atomically into the configured Wiki source area, invoke the existing atomic Wiki generator, and persist actor/time/build identifiers and rollback history.
- Authentication MUST fail safe outside explicitly local development. Enabled authentication with blank credentials MUST fail startup; disabled authentication MUST be visibly warned.
- Existing REST/SSE compatibility and existing evidence-citation changes in the dirty working tree MUST be preserved.
- Retrieval evaluation coverage MUST add project/version leakage, empty-result, and degraded-dependency cases.

## Constraints

- Java 21, Spring Boot 4.1, Spring AI 2.0.
- Reuse existing JSON/file-backed storage and atomic filesystem publication; do not introduce a database in 0.5.
- Do not implement symbol-level code impact or real test execution ingestion in this release.
- Do not overwrite unrelated uncommitted changes.

## Acceptance Criteria

- [x] A configured project can build a draft, submit it for review, approve it, publish it, and read the resulting Wiki.
- [x] Unapproved or rejected drafts cannot publish or overwrite formal Wiki content.
- [x] Every lifecycle transition records actor, timestamp, status, and optional comment.
- [x] A published version can roll back to the immediately preceding published snapshot without exposing a partial Wiki.
- [x] Requirement review routes and retrieves through `RetrievalPipeline` and stays within the requested project/document/version.
- [x] No production prompt or default path assumes “封神” or `5.1`.
- [x] Authentication is explicitly local-off and non-local-on/fail-safe.
- [x] Focused regression tests and `./mvnw -B verify` pass.
- [x] `git diff --check` passes.

## Out of Scope

- Database-backed workflow engine.
- Arbitrary multi-version rollback graph beyond retained publication history.
- Symbol-level impact analysis and real CI/test-result ingestion.
