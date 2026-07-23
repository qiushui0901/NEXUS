# Multi-Project Architecture Code Review

**Review range:** `cb997c1` (P0) → `0907e8e` (P1/P2/P3)  
**Date:** 2026-07-14  
**Stack:** Spring Boot 4.1, Spring AI 2.0, Java 21, Qdrant-only persistence

---

## Executive Summary

| Area | Verdict |
|------|---------|
| P0 Multi-project core | Largely complete, backward compatible |
| P1 Auth + RBAC | Implemented but **not production-safe** when enabled |
| P2 Query router + webhook | Partial; webhook spec mismatch |
| P3 Cross-project features | Backend done; frontend incomplete |
| Tests | Existing tests pass; **zero coverage** for new P1–P3 code |
| **Ready to merge?** | **No** (with `AUTH_ENABLED=true`); **Yes with fixes** for auth-disabled dev |

---

## Strengths

### P0 — Multi-Project Core

- **`ProjectRegistry`** is a clean central abstraction: lookup, group queries, collection resolution, and fallback from legacy single-project config (`ProjectRegistry.java:26-29`).
- **Collection-level isolation** in `QdrantHybridStore` and `CodeQdrantStore` via overloaded methods preserves backward compatibility.
- **`KnowledgeBootstrapService`** supports per-project and batch bootstrap with virtual-thread async execution.
- **Controllers** consistently accept optional `projectId`; `ProjectController` exposes project listing with chunk counts.
- **Frontend** project selector in `monitor.html` with `localStorage` persistence (`nexus_project_id`).
- **Java 21** idioms: records, switch expressions, virtual threads.

### P1–P3 Additions

- **`QueryRouter`** implements explicit → LLM → fallback routing chain; integrated into `DevelopmentPlanService` and `DevelopmentPlanStreamService`.
- **`CrossProjectSearchService`** uses `parallelStream()` fan-out with per-project failure isolation.
- **Cross-side graph** backend adds `projectId`/`side` to `CodeGraphNode` and namespaces node IDs when `crossSide=true`.
- **`IncrementalCodeIndexService`** deletes file-level chunks before upsert (correct incremental pattern).
- **Auth disabled by default** (`AUTH_ENABLED:false`) — no breaking change for existing deployments.

---

## Issues

### Critical (Must Fix)

#### C1 — Project authorization bypass via JSON body

| | |
|---|---|
| **File** | `src/main/java/com/example/requirementrag/web/ProjectAuthInterceptor.java:75-80` |
| **What's wrong** | `resolveProjectId()` only reads `request.getParameter("projectId")`. Most POST endpoints pass `projectId` in JSON body (`CodeSearchRequest`, `ReviewRequest`, `DevelopmentPlanRequest`, `CodeGraphRequest`). |
| **Why it matters** | When `AUTH_ENABLED=true`, a user scoped to project A can POST with `{"projectId":"projectB",...}` and access project B data. Interceptor sees `projectId=null` → `hasAccessTo(null)` returns `true`. |
| **How to fix** | 1) Add `ContentCachingFilter` for `/api/**`. 2) Parse JSON body for top-level `projectId` in interceptor. 3) Add `ProjectAccessGuard.requireAccess(user, projectId)` in services as defense-in-depth. |

#### C2 — API key comparison is not timing-safe

| | |
|---|---|
| **File** | `ProjectAuthInterceptor.java:59-65` |
| **What's wrong** | `apiKey.equals(user.apiKey())` is vulnerable to timing attacks. |
| **Why it matters** | Attackers can iteratively guess API keys by measuring response time differences. |
| **How to fix** | Use `MessageDigest.isEqual(apiKey.getBytes(UTF_8), user.apiKey().getBytes(UTF_8))`. |

#### C3 — Webhook uses plain token, not HMAC-SHA256

| | |
|---|---|
| **File** | `WebhookController.java:39-58` |
| **What's wrong** | Validates `X-Gitlab-Token` with plain `String.equals`. Spec requires HMAC-SHA256 signature verification. |
| **Why it matters** | Token can be intercepted/replayed; does not match GitLab's signature-based webhook security model. |
| **How to fix** | Accept `@RequestBody byte[] rawBody`, verify `X-Gitlab-Signature-256` header with `Mac(HmacSHA256)`, then deserialize JSON. Use `MessageDigest.isEqual` for signature comparison. |

#### C4 — RBAC role hierarchy not enforced; DEVELOPER blocked from all POST

| | |
|---|---|
| **File** | `UserContext.java:19-21`, `ProjectAuthInterceptor.java:68-72` |
| **What's wrong** | `canWrite()` only allows `SUPER_ADMIN` and `PROJECT_ADMIN`. All POST/PUT/DELETE are treated as write. `DEVELOPER` and `READONLY` have identical HTTP-level access. |
| **Why it matters** | Spec defines hierarchy `SUPER_ADMIN > PROJECT_ADMIN > DEVELOPER > READONLY`. DEVELOPER cannot use search (`POST /api/code/search`), graph, development plan, or review — core read workflows. |
| **How to fix** | Define per-endpoint permissions (e.g. `@RequiresPermission(READ)`). Map roles: READONLY=read POSTs; DEVELOPER=read+assistant; PROJECT_ADMIN=write; SUPER_ADMIN=all. Check `role == SUPER_ADMIN` explicitly, not only `projects: ["*"]`. |

---

### Important (Should Fix)

#### I1 — Incremental index reads working tree, not commit SHA

| | |
|---|---|
| **File** | `IncrementalCodeIndexService.java:71`, `JavaCodeScanner.scanFiles():75` |
| **What's wrong** | `scanFiles()` uses `Files.readString()` on disk. Webhook provides `before`/`after` SHAs but repo may not be at `newSha`. |
| **Why it matters** | Indexes stale/wrong content if deploy lags behind webhook; `commitSha` metadata is misleading. |
| **How to fix** | Use `git show <newSha>:<path>` to read file content at the target commit. Optionally `git fetch` before diff. |

#### I2 — Empty placeholder project breaks fallback

| | |
|---|---|
| **File** | `ProjectRegistry.java:23-25`, `application.yml:145-161` |
| **What's wrong** | YAML defines `projects: [{ id: ${PROJECT_1_ID:} }]` — when env unset, `id=""` is registered. `projectMap` is non-empty so fallback never runs. |
| **Why it matters** | Single-project backward compatibility breaks when placeholder entry exists with empty fields. |
| **How to fix** | Skip projects with blank `id` in registry constructor. Or default `projects: []` in YAML. |

#### I3 — Cross-project search scoring is inaccurate

| | |
|---|---|
| **File** | `CrossProjectSearchService.java:65-67` |
| **What's wrong** | Score assigned as `1.0 / (index + 1)` per project, not Qdrant relevance. |
| **Why it matters** | Merged ranking across projects is arbitrary; top results may not be most relevant globally. |
| **How to fix** | Return scores from `QdrantHybridStore.hybridSearch()` or normalize per-project before merge. |

#### I4 — QueryRouter not integrated broadly

| | |
|---|---|
| **File** | `DoubtReviewService.java`, `CodeKnowledgeService.java` |
| **What's wrong** | LLM routing only used in `DevelopmentPlanService` / `StreamService`. Review and code search still use default project when `projectId` omitted. |
| **Why it matters** | P2 "intelligent routing" requirement only partially met. |
| **How to fix** | Call `queryRouter.route(query, projectId)` in `DoubtReviewService.loadRetrievalContext()` when projectId blank. |

#### I5 — Frontend missing auth header and crossSide

| | |
|---|---|
| **File** | `monitor.html:1549-1552`, `1577`, `1605` |
| **What's wrong** | `api()` does not send `X-API-Key`. Graph requests omit `crossSide: true`. Plan graph omits `projectId`. |
| **Why it matters** | UI breaks when auth enabled; P3 cross-side graph unused. |
| **How to fix** | Add API key from localStorage/settings to headers. Pass `crossSide` and `projectId` in all graph/plan requests. |

#### I6 — CrossProjectSearchController defaultAdmin fallback

| | |
|---|---|
| **File** | `CrossProjectSearchController.java:37-40` |
| **What's wrong** | If `UserContext` is null, falls back to `defaultAdmin()` (full access). |
| **Why it matters** | Defense-in-depth failure; should never grant admin if interceptor bypassed. |
| **How to fix** | Throw `401 Unauthorized` when user is null and auth is enabled. |

#### I7 — Monitor status excluded from auth; not project-aware

| | |
|---|---|
| **File** | `WebMvcConfig.java:23`, `MonitorController.java:61-77` |
| **What's wrong** | `/api/monitor/status` excluded from interceptor. Status always shows global `properties.knowledge()` stats, not selected project. |
| **Why it matters** | Information leakage; misleading stats in multi-project mode. |
| **How to fix** | Require auth or limit exposed fields. Accept optional `projectId` for per-project stats. |

#### I8 — HistoricalDoubtService not project-scoped

| | |
|---|---|
| **File** | `HistoricalDoubtService.java`, `DoubtReviewService.java:215` |
| **What's wrong** | Always loads from global `properties.knowledge().xlsxPath()`. |
| **Why it matters** | Multi-project reviews may use wrong historical context for deduplication. |
| **How to fix** | Add per-project `xlsxPath` in `ProjectKnowledge`; pass `projectId` to historical loader. |

#### I9 — Git path → projectId mapping is fragile

| | |
|---|---|
| **File** | `ProjectRegistry.java:74-91` |
| **What's wrong** | Matches `path_with_namespace` to `project.id()` or repo name suffix only. No dedicated `gitPath` config field. |
| **Why it matters** | Collisions when multiple groups have same repo name; webhook routes to wrong project. |
| **How to fix** | Add `gitPath` (or `pathWithNamespace`) to `ProjectConfig`; match exactly. |

#### I10 — Zero tests for P1/P2/P3

| | |
|---|---|
| **Files** | Entire `src/test/` — no `ProjectAuth*`, `Webhook*`, `QueryRouter*`, `CrossProject*` tests |
| **What's wrong** | 818 lines of new production code with no targeted tests. |
| **Why it matters** | Regressions in auth/webhook/routing will not be caught in CI. |
| **How to fix** | Add unit + MockMvc integration tests (see Test Plan below). |

#### I11 — P0 initial commit repo hygiene (cb997c1)

| | |
|---|---|
| **Files** | `tools/qdrant` (71MB binary), `tools/qdrant-data/`, `tools/qdrant.tar.gz`, `产品文档.zip` (2.7GB), `tools/qdrant.log` |
| **What's wrong** | Runtime artifacts and large binaries committed to git. `.gitignore` only covers `target/`, `.env`. |
| **Why it matters** | Repo bloat, accidental secret leakage, non-reproducible deploys. |
| **How to fix** | Add `tools/qdrant-data/`, `tools/*.log`, `*.zip`, `tools/qdrant` to `.gitignore`; remove from git history if possible. |

---

### Minor (Nice to Have)

| ID | File | Issue | Fix |
|----|------|-------|-----|
| M1 | `QueryRouter.java:58` | Uses `rerankerModel` for routing LLM call | Add dedicated `routingModel` config |
| M2 | `WebhookController.java:47` | Error exposes full git path | Generic "unknown project" message |
| M3 | `BootstrapState` | Global lock blocks concurrent per-project bootstrap | Per-project or per-collection locks |
| M4 | `CrossProjectSearchService` | No timeout on parallel search | Add `CompletableFuture` with timeout |
| M5 | `UserRole` enum | No `ordinal()` hierarchy helpers | Add `implies(other)` method |
| M6 | README | No auth/webhook/multi-project setup docs | Document env vars and GitLab webhook setup |

---

## Requirements Traceability

| Requirement | Status | Notes |
|-------------|--------|-------|
| P0: Dynamic project config via YAML | ✅ | `RagProperties.projects`, env-driven |
| P0: ProjectRegistry | ✅ | Lookup, group, collection resolution |
| P0: Per-project Qdrant isolation | ✅ | Dynamic collection params |
| P0: Services accept optional projectId | ✅ | Controllers + services updated |
| P0: Backward compatibility fallback | ⚠️ | Broken when empty placeholder in YAML (I2) |
| P0: Frontend project selector | ✅ | `monitor.html` |
| P1: API Key auth | ⚠️ | Implemented; not timing-safe (C2) |
| P1: Role hierarchy | ❌ | Enum exists; hierarchy not enforced (C4) |
| P1: Project-level access control | ❌ | Bypass via JSON body (C1) |
| P1: HandlerInterceptor on /api/** | ⚠️ | Yes, but excludes monitor status + webhooks |
| P1: Static user list in config | ✅ | `AuthProperties` |
| P2: LLM query router | ⚠️ | Only in development plan services (I4) |
| P2: GitLab webhook HMAC-SHA256 | ❌ | Plain token instead (C3) |
| P2: Incremental code indexing | ⚠️ | Structure correct; reads wrong source (I1) |
| P3: Fan-out cross-project search | ⚠️ | Works; scoring weak (I3) |
| P3: Cross-side code graph | ⚠️ | Backend done; frontend not wired (I5) |
| No relational DB | ✅ | Qdrant only |
| Monolithic architecture | ✅ | Single Spring Boot app |
| Don't break single-project usage | ⚠️ | Works when auth off + no empty project entry |

---

## Test Plan (Recommended New Tests)

```
src/test/java/com/example/requirementrag/
├── web/ProjectAuthInterceptorTest.java
│   ├── rejectsMissingApiKey_whenAuthEnabled
│   ├── acceptsValidApiKey
│   ├── rejectsProjectInBody_whenUserLacksAccess    // C1
│   ├── developerCanSearch_butCannotIndex           // C4
│   └── readonlyCannotIndex
├── web/WebhookControllerTest.java
│   ├── rejectsInvalidHmacSignature                 // C3
│   ├── acceptsValidPushEvent
│   └── skipsZeroShaCommits
├── service/QueryRouterTest.java
│   ├── explicitRouting_whenProjectIdProvided
│   ├── llmRouting_whenAmbiguousQuery
│   └── fallbackToDefaultProject
├── service/CrossProjectSearchServiceTest.java
│   ├── fanOutMergesResultsByScore
│   └── singleProjectFailureDoesNotFailAll
├── code/IncrementalCodeIndexServiceTest.java
│   ├── indexesOnlyChangedJavaFiles
│   └── deletesChunksForRemovedFiles
└── config/ProjectRegistryTest.java
    ├── buildsFallbackWhenProjectsEmpty
    └── skipsBlankProjectIds                      // I2
```

**Current status:** `mvn test` passes (all existing tests green). No new tests added for P1–P3.

---

## Fix Plan (Implementation Order)

### Phase 1 — Security (1–2 days)

1. **C1** — Body `projectId` parsing + service-layer guard
2. **C4** — Endpoint-level permission annotations
3. **C2** — Timing-safe API key comparison
4. **C3** — HMAC-SHA256 webhook verification

### Phase 2 — Correctness (1–2 days)

5. **I1** — `git show` for incremental indexing
6. **I2** — Filter blank project IDs in registry
7. **I4** — Integrate QueryRouter into DoubtReviewService
8. **I3** — Real Qdrant scores in cross-project search

### Phase 3 — Completeness (1 day)

9. **I5** — Frontend API key + crossSide + projectId
10. **I6** — Remove defaultAdmin fallback
11. **I10** — Add test suite from Test Plan above

### Phase 4 — Hardening (optional)

12. **I7–I9** — Monitor scoping, historical doubts per project, gitPath config
13. **I11** — Repo hygiene / gitignore cleanup

---

## Code Snippets for Critical Fixes

### C2 — Timing-safe API key

```java
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

private UserContext resolveUser(String apiKey) {
    for (AuthProperties.AuthUser user : authProperties.users()) {
        if (MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                user.apiKey().getBytes(StandardCharsets.UTF_8))) {
            return new UserContext(user.username(), user.role(), List.copyOf(user.projects()));
        }
    }
    return null;
}
```

### C3 — HMAC webhook (sketch)

```java
@PostMapping("/gitlab")
public Map<String, String> gitlabPush(
        @RequestHeader("X-Gitlab-Signature-256") String signature,
        @RequestBody byte[] rawBody) throws Exception {
    validateHmacSha256(rawBody, signature);
    GitLabPushEvent event = objectMapper.readValue(rawBody, GitLabPushEvent.class);
    // ... existing logic
}
```

### I2 — Skip blank project IDs

```java
for (RagProperties.ProjectConfig project : properties.projects()) {
    if (project.id() == null || project.id().isBlank()) {
        continue;
    }
    projectMap.put(project.id(), project);
}
```

---

## Recommendations

1. **Do not enable `AUTH_ENABLED=true` in production** until C1–C4 are fixed and tested.
2. **Add a `@WebMvcTest` security test suite** before any auth-related merge.
3. **Document breaking API change:** `CodeGraphNode` now includes `projectId` and `side` fields — notify frontend consumers.
4. **Remove binary/large artifacts** from git (I11) before wider team adoption.
5. **Consider `gitPath` field** in project config before relying on webhooks in multi-repo setups.
6. **Add confidence threshold** to QueryRouter (e.g. skip LLM route when `confidence < 0.5`, prompt user to select project).

---

## Assessment

**Ready to merge?** **No** (production with auth); **With fixes** (dev/single-project, auth disabled)

**Reasoning:** P0 multi-project core is well-structured and backward compatible when auth is off. P1 auth/RBAC has critical bypass and hierarchy gaps that make `AUTH_ENABLED=true` unsafe. P2 webhook does not meet HMAC spec. P3 backend is sound but frontend and scoring need work. Zero test coverage for 800+ lines of new security/routing code is unacceptable for production.

---

## Git Commits Reviewed

| Commit | Description | Files |
|--------|-------------|-------|
| `cb997c1` | P0 multi-project architecture | ~240 files (initial project) |
| `0907e8e` | P1/P2/P3 capabilities | 26 files, +818 lines |

---

*Generated by code review agent. Reply with issue ID (e.g. `C1`) to begin implementation.*
