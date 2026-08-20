# 业务项目多仓库知识模型 - Implementation Plan

## Phase 1: Catalog Foundation

- [x] Add business project, repository, shared-reference, alias, and migration-journal domain records.
- [x] Add SQLite catalog store with idempotent schema initialization, constraints, and transactional writes.
- [x] Add `BusinessProjectRegistry`, `RepositoryRegistry`, and compatibility projection from existing static
      `ProjectConfig`.
- [x] Add Maven root-POM version resolver and coverage comparison for `v5.2.0` versus requirement `5.1`.
- [ ] Test ownership uniqueness, shared references, legacy aliases, malformed POM, missing version, and
      last-known-version degradation.

## Phase 2: GitLab Repository Ownership

- [ ] Add `business_project_id` and `repository_kind` compatibility columns to GitLab managed repositories.
- [x] Change account import request/API/service to require one batch-level existing business project.
- [x] Register imported repositories in the repository catalog without creating business projects.
- [x] Preserve repository IDs for queue, webhook, clone, job, and sync APIs.
- [ ] Add separate shared-library onboarding and prevent a normal remote repository from belonging to two
      projects.
- [x] Update GitLab wizard to select an existing target project before repository selection/configuration.
- [ ] Test duplicate remote identities, incomplete target projects, batch ownership, shared-library import,
      and existing webhook compatibility.

## Phase 3: Scope, Retrieval, and Source Access

- [x] Add project scope resolution for owned repositories plus explicit shared references.
- [x] Extend retrieval requests with optional repository IDs while keeping old calls compatible.
- [x] Run code retrieval per repository live alias concurrently, merge/rerank globally, and bound total
      candidates/latency.
- [ ] Add repository metadata to code chunks, public hits, evidence IDs, diagnostics, and citations.
- [ ] Enforce business-project authorization on repository source and graph access.
- [x] Emit `REQUIREMENT_VERSION_BEHIND` while using the latest available requirement version.
- [ ] Test default all-repository search, repository filters, shared libraries, duplicate file/symbol names,
      partial repository failure, and unauthorized repository access.

## Phase 4: Correct Statistics and Project UI

- [x] Centralize live alias resolution and structured count status.
- [x] Fix `ProjectController`, `RuntimeStatusController`, and `KnowledgeManagementController` to use live
      aliases and distinguish unavailable from true zero.
- [x] Add business project summary/detail APIs with aggregate counts and partial/degraded status.
- [ ] Build business project detail UI with version, requirement coverage, owned repositories, and referenced
      shared libraries.
- [ ] Add separate shared-library management view.
- [x] Keep repository sync detail and timeline repository-scoped.
- [x] Test the current `bizgame_immortal_api_code-live` 1263-point regression and responsive frontend states.

## Phase 5: Version and Wiki Schema v3

- [x] Extend version manifests with product version, requirement baseline, and repository baseline list.
- [x] Add schema-v2 read compatibility and schema-v3 validation.
- [ ] Generate Wiki evidence with repository identity and multiple commits.
- [ ] Update staleness checks to compare only repositories referenced by each page.
- [ ] Preserve product version when shared-library versions change.
- [ ] Test main-repository version changes, requirement lag, secondary repository changes, shared-library
      changes, and old Wiki readability.

## Phase 6: Explicit Immortal Migration

- [x] Add preview API returning all source/target mappings and preflight failures without writes.
- [x] Add transactional/idempotent catalog migration plus filesystem staging/backup for Wiki metadata.
- [ ] Migrate:
      `immortal-game-service` knowledge/Wiki -> business project `immortal`;
      `immortal-game-service` -> anchor repository;
      `bizgame-immortal-api` -> owned repository.
- [x] Reuse all existing Qdrant aliases, point payloads, local repositories, GitLab jobs, and secrets.
- [ ] Add legacy business-project route alias and rollback command/API.
- [ ] Verify repeat apply, interrupted apply, rollback, and no embedding-provider calls.

## Phase 7: Documentation and Quality Gate

- [x] Update GitLab onboarding, project management, retrieval, Wiki, and migration documentation.
- [x] Update Trellis specs with business-project/repository/shared-library invariants.
- [x] Update `CHANGELOG.md` in the shipping version section.
- [x] Run focused catalog, GitLab, retrieval, statistics, Wiki, migration, and browser tests.
- [x] Run all standalone JS syntax checks and `git diff --check`.
- [x] Run `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify`.
- [x] Run the MCP HTTP test outside the sandbox if the full suite only fails on port binding.

## Rollback Points

- Catalog phase: feature flag off returns to compatibility projection.
- GitLab phase: nullable ownership columns preserve old repository rows.
- Retrieval phase: old single-repository scope remains available behind compatibility adapter.
- Wiki phase: schema-v2 files remain readable and are never overwritten before schema-v3 output validates.
- Migration phase: catalog journal and filesystem backup restore pre-migration routing without touching
  Qdrant or GitLab history.

## Review Hardening

- [x] Preserve multi-repository code evidence through `EvidenceRegistry`.
- [x] Resolve path project IDs, business aliases, and repository ownership before global authorization.
- [x] Invalidate project retrieval caches when repository scope changes.
- [x] Preserve schema-v3 product/repository fields during requirement enrichment.
- [x] Reject and roll back conflicting migration targets.
- [x] Return safe repository DTOs without server filesystem paths.
- [x] Project knowledge management as one shared requirement base plus every repository code base.
- [x] Resolve monitor and code-index status through business project ownership.
