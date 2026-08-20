# GitLab Auto-Onboarding Contracts

## 1. Scope / Trigger

Apply this specification when changing dynamic project registration, GitLab credentials, managed
repository synchronization, GitLab webhooks, or automatic code indexing.

## 2. Signatures

```http
POST   /api/integrations/gitlab/projects
GET    /api/integrations/gitlab/projects
GET    /api/integrations/gitlab/projects/{projectId}
POST   /api/integrations/gitlab/projects/{projectId}/sync
POST   /api/integrations/gitlab/projects/{projectId}/retry
POST   /api/integrations/gitlab/projects/{projectId}/enable
DELETE /api/integrations/gitlab/projects/{projectId}
POST   /api/webhooks/gitlab/{projectId}
POST   /api/integrations/gitlab/connections
GET    /api/integrations/gitlab/connections
GET    /api/integrations/gitlab/connections/{connectionId}/projects
GET    /api/integrations/gitlab/connections/{connectionId}/projects/{remoteProjectId}/branches
POST   /api/integrations/gitlab/connections/{connectionId}/imports
```

```text
gitlab_managed_project(project_id PK, ..., status, last_indexed_sha, target_sha, last_error, ...)
gitlab_webhook_event(project_id, event_id, received_at, PK(project_id, event_id))
gitlab_connection(id PK, base_url, host, username, access_token_ciphertext, status, ...)
gitlab_managed_project(..., connection_id nullable, remote_project_id nullable,
                       access_token_ciphertext, ...)
```

## 3. Contracts

- Management endpoints require `Permission.ADMIN`; only `SUPER_ADMIN` satisfies it.
- The feature is absent unless `app.rag.gitlab.enabled=true`.
- `GITLAB_ENCRYPTION_KEY` is a Base64-encoded 32-byte AES key.
- PAT and webhook secrets are persisted only as AES-256-GCM ciphertext.
- New account-imported projects reference `gitlab_connection` and keep an empty legacy project PAT field.
  Existing projects with no `connection_id` continue to decrypt their project-level ciphertext.
- One NEXUS instance may keep multiple named GitLab connections. Connection responses never expose PAT
  plaintext or ciphertext.
- Account-imported projects use `(connection_id, remote_project_id)` as the stable remote identity.
  Namespace paths are mutable and may be identical across GitLab instances, so they are display/routing
  metadata only. The database must enforce uniqueness for the stable identity.
- Account discovery uses the authenticated membership project list, follows every bounded pagination page,
  and excludes unrelated public projects. A non-blank search must be sent to GitLab before bounded
  pagination so accounts above the discovery cap can narrow the result set.
- Self-hosted GitLab responses are an external schema boundary. Project list/detail responses must be read
  as text, parsed into a JSON tree, and projected field-by-field. Accept the standard list array plus
  `data`, `items`, or `projects` array envelopes; do not bind the entire response directly to
  `RemoteProject[]`, because vendor/proxy wrappers and non-standard optional field types otherwise turn a
  valid account into an HTTP 500.
- Account import is code-repository onboarding only. Import requests contain remote project ID, `projectId`,
  side, selected branch, and code collection; they do not contain or establish a requirement collection
  relationship. Connected projects use `knowledge=null` to express that requirement knowledge is absent;
  an isolated internal unlinked-requirement collection exists only for legacy non-null storage compatibility
  and must never be treated as configured knowledge.
- A GitLab repository is not a business project. Batch import requires one existing, complete
  `businessProjectId`; every accepted repository in that batch is cataloged under that business project.
  The wizard must not create a business project or let individual rows choose different owners.
- Repository IDs remain the identity for clone, queue, webhook, sync job, code collection, and source access.
  Business project IDs own requirements, product version, Wiki, permissions, and aggregate retrieval.
- Ordinary repositories have exactly one business project owner. Cross-project code is modeled as a
  separately indexed shared repository and referenced explicitly by each business project.
- The backend re-reads each remote project, verifies authenticated membership from project permissions,
  and never trusts browser-provided clone URLs or namespace paths.
- Branch choices come from the authenticated GitLab repository branches API. The UI uses a select control
  that exposes default/protected/merged state; free-text branch entry is forbidden for account imports.
  Before registration, the backend re-reads the remote branch list and rejects a branch that is missing or
  was deleted after the UI review.
- Batch import is item-independent: one rejected project must not roll back accepted projects. Every accepted
  project immediately enters the existing initial sync queue. Remote detail requests run concurrently with
  bounded API timeouts; batch acceptance must not run synchronous Git network validation.
- GitLab REST API and Git Clone/Fetch share one exact-host and private-address policy. A connection PAT may
  only be resolved for a clone URL with the same normalized host and effective port as that connection.
- Only deterministic credential errors (`GITLAB_TOKEN_INVALID`) mark a connection `INVALID`. Rate limits,
  5xx responses, timeouts, and malformed transient responses leave the previous connection status intact.
- `401` is a credential failure. `403` maps to `GITLAB_TOKEN_INVALID` only for the account `/user`
  verification call; project discovery/detail/branch `403` maps to `GITLAB_PERMISSION_DENIED` and must not
  invalidate the whole connection.
- Project creation uses a database atomic insert. A duplicate `projectId` or stable remote identity must
  never overwrite an existing row, and cleanup may only remove the row/registry entry created by that call.
- Browser wizard PAT, webhook secret, validation checks, and secret visibility state exist only for the
  active wizard session. Clear them when leaving the wizard, starting a new wizard, navigating to detail,
  or unmounting the component.
- Account project import is a gated two-step UI: selection only records remote IDs; a separate configuration
  review expands every selected project and allows editing `projectId`, side, branch, and code collection.
  The frontend must not call `/imports` unless the review step is active, branch discovery has succeeded,
  and every
  required field is non-blank; the final command explicitly confirms that initial sync starts immediately.
- Git credentials are injected only through a temporary `GIT_ASKPASS`; never place them in a URL,
  command argument, response, exception, or log.
- Clone URLs must use an exact host allowlist match. The default allowlist contains only `gitlab.com`;
  suffix matching, URL credentials, query parameters, and fragments are forbidden.
- IP literals and hosts resolving to any local, loopback, link-local, private, multicast, or IPv6 ULA
  address are rejected by default. Private GitLab requires both an allowlisted host and
  `allow-private-hosts=true`.
- Static projects have priority. A dynamic project cannot replace a static or different dynamic project.
- One worker drains each project's FIFO queue. A webhook accepted while another sync is running must not
  be dropped.
- Keep the per-project queue entry until the project is disabled or the service shuts down. Removing an
  idle queue creates a race where a stale enqueuer and a new map entry can start two workers.
- `lastIndexedSha` changes only after successful index publication. `targetSha` records the attempted target.
- Starting a latest-HEAD sync must explicitly clear stale `targetSha`. Once a target is resolved, failure
  paths preserve it.
- `retry` must enqueue a non-null persisted `targetSha`; when an early failure left it null, retry resolves
  the current remote branch HEAD instead of reusing an older successful target.
- Application startup re-enqueues `PENDING`, `CLONING`, `SYNCING`, and `INDEXING` projects, preserving a
  non-null target. It never automatically schedules `READY`, `FAILED`, or `DISABLED`.
- Sync orchestration calls `CodeKnowledgeService.index` or
  `IncrementalCodeIndexService.indexWithResult` directly. It must not acquire the code index lock itself.
- `DISABLED` is terminal for background state updates. Use a conditional database update so an in-flight
  task cannot restore `CLONING`, `INDEXING`, `READY`, or `FAILED`.
- Disabling a project persists `DISABLED` before cancelling its worker and terminalizes every queued or
  running persisted sync job as `CANCELLED` / `PROJECT_DISABLED`; no job may remain active after disable.
- Re-enabling is an in-place transition, not a second import. Only `DISABLED` may atomically transition to
  `PENDING`; restore the same dynamic project config, preserve repository/index/history, and enqueue one
  `REENABLE` latest-HEAD sync. Concurrent re-enable requests must not create duplicate jobs or records.
- Every GitLab secondary view exposes a visible return command in the page heading. Breadcrumbs may remain
  for context but are not the only way back to project/account lists.
- GitLab status polling is silent and non-reentrant. It updates existing project, account, and detail data
  without toggling first-load placeholders, changing page height, clearing one-time secrets, or surfacing
  transient poll failures as blocking page errors.

## 4. Validation & Error Matrix

- Non-HTTPS URL, non-allowlisted or unsafe host, URL credentials/query/fragment, unsafe project
  ID/branch/path, or invalid SHA -> HTTP 400.
- Missing or wrong `X-Gitlab-Token` -> HTTP 401.
- Missing/non-Push `X-Gitlab-Event`, malformed JSON, or mismatched project path -> HTTP 400.
- Different branch -> accepted request with `status=ignored`.
- Duplicate event ID -> accepted request with `status=duplicate`, no second queued task.
- Non-fast-forward target -> persisted `FAILED`, unchanged `lastIndexedSha`.
- Invalid encryption key while enabled -> application startup failure.
- `401/403` during account verification -> connection `INVALID`; `429/5xx/timeout` -> error returned while
  connection remains in its previous status.
- Project/detail/branch `403` -> `GITLAB_PERMISSION_DENIED`, item operation fails, connection remains active.
- Import branch missing from the freshly read remote branch list -> `GITLAB_BRANCH_NOT_FOUND`, no project row
  or initial sync job is created.
- Re-enable a non-`DISABLED` project -> validation error; credential, registry, or queue failure rolls the
  project back to `DISABLED`.
- Missing/blank/non-JSON project response, or an object without a supported project array envelope ->
  `GITLAB_INVALID_RESPONSE`; never return a generic servlet 500 caused by DTO deserialization.

## 5. Good/Base/Bad Cases

- Good: a fast-forward Push is queued, checked out, incrementally indexed, and reaches `READY`.
- Base: disabling the feature leaves all static project behavior unchanged.
- Bad: persisting a PAT in `.git/config`, dropping a second Push while indexing, or overwriting
  `DISABLED` from a worker is a release blocker.

## 6. Tests Required

- Cipher round-trip and invalid key length.
- URL structure, exact host allowlist, mixed public/private DNS answers, private-host opt-in, project ID,
  branch, SHA, repository-root containment, bounded Git output, and clean origin URL.
- SQLite restart persistence, migration-compatible `target_sha`, webhook deduplication, and disabled guard.
- Initial full index, fast-forward incremental index, non-fast-forward rejection, queued Push, retry, and
  disable/re-enable race. Retry must cover both a recorded failed target and an early latest-HEAD failure
  after an older READY target. Re-enable must preserve one project row and use trigger `REENABLE`.
- Startup recovery must cover all four interrupted states and prove stable/terminal states are not scheduled.
- Native token 401, malformed JSON 400, branch ignore, project mismatch, duplicate event, and ADMIN marker.
- Spring context with the feature both disabled and enabled.
- Browser contract tests assert one centralized sensitive-state reset path covers project/account navigation,
  fresh account linking, route changes, and component unmount.
- Connection/API tests cover encrypted persistence, membership pagination, 401/403/429/timeouts, invalidation,
  reauthorization, project defaults, duplicate/archived/no-default-branch states, and partial batch success.
- Regression tests cover transient verification failures, server-side project search, project rename,
  same namespace on different instances, concurrent registration, concurrent detail reads, and PAT
  host/port binding.
- API client tests cover standard project arrays, self-hosted `data/items/projects` envelopes, optional
  fields with non-standard types, project detail wrappers, and paginated branch state projection.
- Regression tests prove connected project configs have `knowledge=null`, early Bootstrap configuration
  failures release the project lock, project-level `403` does not invalidate the account, and stale/tampered
  branch submissions are rejected before registration.
- Browser contract tests assert account PAT, reauthorization PAT, import result secrets, and selection state
  are cleared on route changes and component unmount.
- Browser contract tests assert the selection action enters configuration review before
  `GitLabApi.importProjects`, all four code-onboarding fields are visible without collapsed disclosure
  widgets, requirement collection is absent, branch is a remote-backed stateful select, and back/clear
  paths do not submit an import.
- Browser contract tests assert explicit return commands exist on account list, account connect, account
  detail, and project detail views, and disabled project detail exposes `GitLabApi.enable`.
- Browser contract tests assert polling uses silent loaders, rejects overlapping refreshes, and does not
  route detail polling through the first-load state-reset path.
- Browser and service tests assert import requires one existing business project, sends it once at batch
  level, and creates repository catalog ownership without creating an isolated business project.
- Explicit catalog migrations use insert-or-verify semantics. Existing IDs, collections, or aliases must
  match the expected semantic record; otherwise the transaction rolls back and must not mark the migration
  completed.
- Public business-project repository DTOs never expose local `repositoryPath` or other server filesystem
  paths.

## 7. Wrong vs Correct

### Wrong

```java
if (running.containsKey(projectId)) {
    return; // accepted Push is silently lost
}
```

### Correct

```java
queue.requests.addLast(new SyncRequest(targetSha));
startWorkerWhenIdle(queue);
```

### Wrong

```java
queues.remove(projectId, queue); // an enqueuer may still hold this queue and start a second worker
enqueue(projectId, null);        // retry moves to the latest remote HEAD
```

### Correct

```java
queue.worker = null;             // retain the stable project queue until disable/shutdown
enqueue(projectId, project.targetSha());
```

### Wrong

```java
client.get().retrieve().body(RemoteProject[].class);
```

### Correct

```java
String body = client.get().retrieve().body(String.class);
JsonNode projects = supportedProjectArray(objectMapper.readTree(body));
```
