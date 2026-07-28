# NEXUS 0.5 Technical Design

## 1. Unified requirement review retrieval

`DoubtReviewService` constructs a review query and calls `RetrievalPipeline.execute` using `REQUIREMENT_REVIEW`. The shared pipeline owns project routing, collection resolution, document/version filtering, diagnostics, and failure classification. Review-specific BGE and LLM reranking remains downstream of the returned requirement evidence so the consumer-specific ranking policy does not leak into generic orchestration. Full-version context is loaded through a pipeline option/returned evidence contract rather than direct routing logic in the review service.

## 2. File-backed draft lifecycle

Add a draft repository/service around the existing `data/wiki-drafts/{project}/{version}/{buildId}` layout. Each draft contains immutable build artifacts plus `review.json`. Metadata contains current status, revision, created/updated actor/time, transition history, publication record, and rollback information. Updates use temp-file + atomic move and per-draft locking. Legal transitions are centralized and validated.

## 3. Publication and rollback

Publishing requires `APPROVED`. The reviewed `wiki-source.json` is copied atomically to the configured formal source filename, preserving the prior source as a publication snapshot. `WikiGenerationService.generate` performs the existing staging-directory atomic Wiki swap. Metadata changes to `PUBLISHED` only after generation succeeds. Rollback restores the previous source snapshot and regenerates the Wiki atomically, then appends an audit event.

## 4. API and access control

Extend `/api/knowledge` with draft list/detail/transition/publish/rollback endpoints. Controllers validate project existence, call `ProjectAccessGuard`, and use `PUBLIC_READ`, `WRITE`, or `OPERATE` permissions according to operation risk. Actor identity comes from `UserContext` attached by the interceptor.

## 5. Configuration/security

Remove business-specific defaults from prompts/config. Keep local developer convenience in an explicit local profile. Default/shared configuration enables auth. A startup validator rejects enabled auth with no usable users/API keys and logs a warning when explicitly disabled.

## 6. Compatibility and verification

Keep current build and review response contracts. New draft endpoints and metadata are additive. Add unit/controller tests around transitions, path safety, publication atomicity, rollback, routing, version isolation, and auth validation.
