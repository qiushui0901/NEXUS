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
- Static projects have priority. A dynamic project cannot replace a static or different dynamic project.
- One worker drains each project's FIFO queue. A webhook accepted while another sync is running must not
  be dropped.
- Keep the per-project queue entry until the project is disabled or the service shuts down. Removing an
  idle queue creates a race where a stale enqueuer and a new map entry can start two workers.
- `lastIndexedSha` changes only after successful index publication. `targetSha` records the attempted target.
- `retry` must enqueue the persisted `targetSha`; it must not silently replace the failed target with a newer
  remote branch HEAD.
- Sync orchestration calls `CodeKnowledgeService.index` or
  `IncrementalCodeIndexService.indexWithResult` directly. It must not acquire the code index lock itself.
- `DISABLED` is terminal for background state updates. Use a conditional database update so an in-flight
  task cannot restore `CLONING`, `INDEXING`, `READY`, or `FAILED`.

## 4. Validation & Error Matrix

- Non-HTTPS URL, URL credentials/query/fragment, unsafe project ID/branch/path, or invalid SHA -> HTTP 400.
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
- URL, project ID, branch, SHA, repository-root containment, bounded Git output, and clean origin URL.
- SQLite restart persistence, migration-compatible `target_sha`, webhook deduplication, and disabled guard.
- Initial full index, fast-forward incremental index, non-fast-forward rejection, queued Push, retry, and
  disable race. Retry must assert that the recorded failed target is used even when remote HEAD has moved.
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
