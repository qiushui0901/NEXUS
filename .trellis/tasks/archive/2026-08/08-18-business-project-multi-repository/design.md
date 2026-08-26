# 业务项目多仓库知识模型 - Technical Design

## 1. Design Summary

Introduce a catalog layer above the existing repository-oriented indexing and GitLab synchronization
services:

```text
BusinessProject
  ├── one version-anchor Repository
  ├── one shared RequirementSource
  ├── many owned Repositories
  └── many explicitly referenced SharedRepositories
```

Existing GitLab queues, webhook identities, local clones, code collections, Qdrant payload
`projectId`, symbol graph snapshots, and sync history remain repository-scoped. The new business project
layer expands a user-selected business project into repository scopes before code retrieval and aggregation.

## 2. Domain Model

### Business project

```text
business_project
  id PK                         // immortal
  name
  version_anchor_repository_id
  requirement_collection
  requirement_document_id
  requirement_snapshot_namespace
  latest_requirement_version
  status
  created_at / updated_at
```

The version anchor is mandatory before the project can receive GitLab imports. Product access control,
requirements, Wiki, and product-level navigation use `business_project.id`.

### Repository catalog

```text
code_repository
  id PK                         // immortal-game-service, bizgame-immortal-api
  name
  kind                          // PROJECT | SHARED
  business_project_id nullable  // required for PROJECT, null for SHARED
  side
  code_collection              // base name; runtime reads <base>-live
  repository_path
  git_path
  version_source_type           // MAVEN_POM initially
  version_source_path           // pom.xml
  enabled
  created_at / updated_at
```

`PROJECT` repositories have exactly one owner. `SHARED` repositories have no owner and are linked by:

```text
business_project_shared_repository(
  business_project_id,
  repository_id,
  created_at,
  PK(business_project_id, repository_id)
)
```

The same remote GitLab identity remains globally unique. A normal repository cannot be linked to a second
business project. A shared repository is indexed once and referenced many times.

### GitLab integration compatibility

Keep `gitlab_managed_project.project_id` as the repository ID for backward compatibility. Add:

```text
business_project_id nullable
repository_kind text not null default 'PROJECT'
```

GitLab sync, job, event, webhook, and queue APIs continue using repository ID. New import requests add one
batch-level `businessProjectId`; repository rows are created with that owner. Shared-library onboarding uses
the separate shared-library management flow and `repository_kind=SHARED`.

## 3. Runtime Registries

Add:

- `BusinessProjectRegistry`: project lookup, legacy aliases, requirement scope, anchor repository.
- `RepositoryRegistry`: repository lookup, owned repositories, referenced shared repositories, collection
  and local path resolution.
- `ProjectScopeResolver`: expands a business project plus optional repository filters into one requirement
  scope and an ordered list of repository scopes.

Retain `ProjectRegistry` temporarily as a compatibility facade. Existing repository-only callers resolve
repository IDs directly. Product-facing controllers and retrieval entry points migrate to
`BusinessProjectRegistry`.

Legacy ID `immortal-game-service` resolves to business project `immortal` only in product-facing routes.
Repository APIs continue to interpret it as the main repository ID, preventing ambiguous webhook and sync
behavior.

## 4. Version Model

`immortal-game-service` is the version anchor for `immortal`.

`RepositoryVersionResolver` reads the root Maven `pom.xml` project version using an XML parser, not regex.
The current raw version `5.2.0` is exposed as `v5.2.0` and stored with:

```text
repositoryId, displayVersion, rawVersion, sourcePath, commitSha, resolvedAt
```

The product version always comes from the anchor repository. Other owned/shared repository versions and
commits are dependency baselines only.

Requirement coverage is calculated independently:

```text
productVersion = v5.2.0
latestRequirementVersion = 5.1
coverageStatus = BEHIND
```

When requirements lag, retrieval uses the latest available requirement version with stable warning
`REQUIREMENT_VERSION_BEHIND`. It never labels 5.1 evidence as v5.2.0 evidence.

## 5. Retrieval Flow

1. Resolve business project and access permission.
2. Resolve latest product version from the anchor repository.
3. Resolve latest available requirement version and coverage status.
4. Expand repository scope:
   - enabled owned repositories;
   - enabled explicitly referenced shared repositories;
   - optional request repository filter.
5. Retrieve requirements once from the business project requirement collection.
6. Retrieve code concurrently per repository from each `<codeCollection>-live` alias.
7. Merge and rerank globally while preserving repository metadata.

`CodeChunk`/public code-hit contracts gain repository ID, repository name, and repository kind. Evidence IDs
include repository ID to avoid collisions between identical paths or symbols.

Source-reading and symbol-graph requests require both business project access and repository membership in
the resolved scope.

## 6. Statistics and Status

All code counters must read the same live alias used by retrieval. Introduce a structured count result:

```text
CountResult(status=AVAILABLE|UNAVAILABLE, count, warningCode)
```

Do not collapse collection-not-found, Qdrant-unavailable, and a real zero into the same number.

Business project summary:

- product version from anchor repository;
- latest requirement version and coverage status;
- owned repository count and shared-reference count;
- sum of available repository live-alias point counts;
- partial/degraded state when any repository count or sync is unavailable;
- Wiki version count under the business project ID.

Repository detail retains its own code count, branch, commit, webhook, and sync timeline.

## 7. Wiki and Version Manifests

Upgrade version manifests to schema v3:

```text
productVersion
requirementBaseline(documentId, version, content hashes)
repositoryBaselines[
  repositoryId, kind, version, commitSha, codeCollection
]
```

Wiki pages and code evidence identify repository ID. Staleness checks compare each referenced repository
against its recorded commit. Shared-library changes can make affected pages stale but do not change the
product version.

Old schema v2 manifests remain readable through an adapter that maps the single `codeCommit` to the legacy
main repository.

## 8. API and UI Contracts

Add product-facing APIs:

```http
GET/POST        /api/business-projects
GET/PATCH       /api/business-projects/{projectId}
GET             /api/business-projects/{projectId}/repositories
PUT/DELETE      /api/business-projects/{projectId}/shared-repositories/{repositoryId}
GET/POST        /api/shared-repositories
POST            /api/business-project-migrations/preview
POST            /api/business-project-migrations/apply
```

GitLab import adds batch-level `businessProjectId`. The wizard only selects existing complete business
projects. It does not create projects.

The main project screen becomes business-project-oriented. Project detail has:

- overview and requirement coverage;
- owned repositories;
- referenced shared libraries;
- aggregate retrieval/knowledge status.

GitLab repository detail stays repository-oriented. Shared libraries have a separate management view.

## 9. Immortal Migration

Migration is explicit and idempotent:

1. Preview validates source assets and shows all mappings.
2. Create business project `immortal`.
3. Register `immortal-game-service` as owned anchor repository.
4. Attach existing requirement collection/document/snapshot namespace to `immortal`.
5. Attach `bizgame-immortal-api` as another owned repository without changing its GitLab row, jobs,
   webhook secret, clone, code collection, Qdrant alias, or point payloads.
6. Copy or atomically move Wiki/version metadata into the `immortal` namespace with backup; add legacy
   route alias from `immortal-game-service`.
7. Persist a migration journal and completion marker.

Rollback removes new catalog links and restores moved filesystem metadata from backup. It never deletes
Qdrant collections or GitLab job history.

## 10. Compatibility and Rollout

- Existing single-repository static configurations are adapted as one business project with one anchor
  repository until explicitly migrated.
- Existing GitLab webhook URLs and repository sync APIs remain valid.
- Product-facing APIs accept configured legacy project aliases during the compatibility window.
- Feature rollout is guarded by a catalog/migration feature flag until Immortal migration is verified.
- No embedding model change and no mandatory re-embedding are part of this task.

## 11. Main Risks

- Confusing business project IDs with repository IDs at authorization boundaries.
- Double-counting shared libraries or duplicate aliases during aggregate retrieval.
- Writing new Wiki schema while old readers still assume one code commit.
- Partial migration across SQLite and filesystem metadata.
- Regressing repository-level GitLab queue serialization while adding project ownership.
