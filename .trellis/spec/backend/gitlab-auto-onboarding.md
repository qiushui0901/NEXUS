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
DELETE /api/integrations/gitlab/projects/{projectId}
POST   /api/webhooks/gitlab/{projectId}
```

```text
gitlab_managed_project(project_id PK, ..., status, last_indexed_sha, target_sha, last_error, ...)
gitlab_webhook_event(project_id, event_id, received_at, PK(project_id, event_id))
```

## 3. Contracts

- Management endpoints require `Permission.ADMIN`; only `SUPER_ADMIN` satisfies it.
- The feature is absent unless `app.rag.gitlab.enabled=true`.
- `GITLAB_ENCRYPTION_KEY` is a Base64-encoded 32-byte AES key.
- PAT and webhook secrets are persisted only as AES-256-GCM ciphertext.
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

## 4. Validation & Error Matrix

- Non-HTTPS URL, non-allowlisted or unsafe host, URL credentials/query/fragment, unsafe project
  ID/branch/path, or invalid SHA -> HTTP 400.
- Missing or wrong `X-Gitlab-Token` -> HTTP 401.
- Missing/non-Push `X-Gitlab-Event`, malformed JSON, or mismatched project path -> HTTP 400.
- Different branch -> accepted request with `status=ignored`.
- Duplicate event ID -> accepted request with `status=duplicate`, no second queued task.
- Non-fast-forward target -> persisted `FAILED`, unchanged `lastIndexedSha`.
- Invalid encryption key while enabled -> application startup failure.

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
  disable race. Retry must cover both a recorded failed target and an early latest-HEAD failure after an
  older READY target.
- Startup recovery must cover all four interrupted states and prove stable/terminal states are not scheduled.
- Native token 401, malformed JSON 400, branch ignore, project mismatch, duplicate event, and ADMIN marker.
- Spring context with the feature both disabled and enabled.

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
