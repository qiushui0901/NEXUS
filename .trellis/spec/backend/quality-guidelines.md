# Quality Guidelines

> Code quality standards for backend development.

---

## Build Baseline

- The supported runtime is JDK 21.
- Use the repository Maven Wrapper: `./mvnw`.
- Maven Enforcer must reject unsupported Java and Maven versions early.
- Tests use an explicit Mockito Java agent configured through Surefire; do not rely on dynamic self-attachment.
- CI runs `./mvnw -B verify` on push and pull requests.

## Required Verification

Before completing backend work, run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify
git diff --check
```

New behavior and bug fixes require regression tests. Error-handling changes must test both the public safe response and the internal status classification where practical.

For a release verification report, run `./tools/verify-report.sh`. The script must:

- execute `clean verify` with Enforcer enabled so stale `target/` reports cannot inflate test counts;
- refuse staged, tracked, or untracked workspace changes before associating results with `HEAD`;
- derive the project version from parsed `pom.xml`, not a fixed line number;
- aggregate `target/surefire-reports/TEST-*.xml`, not human-readable console or text summaries;
- record malformed or missing Surefire XML as an explicit parse status without suppressing the Maven exit code;
- write matching versioned and `latest.json` reports containing commit, JDK, test totals, JaCoCo, jar, and exit status.

Do not report a release test count from a non-clean workspace. In August 2026, stale Surefire files overstated the suite by six tests even though the build itself was green.

## Repository Hygiene

Never commit:

- `target/` or local dependency caches
- `.env` or credentials
- Qdrant/vector database storage
- snapshots, PID files, runtime logs, or downloaded runtime archives

Keep wrapper scripts, source code, tests, configuration, documentation, and CI workflows versioned.

## Review Checklist

- Existing REST fields and SSE events remain compatible.
- `SUCCESS`, `NO_RESULTS`, `DEGRADED`, and `FAILED` are not conflated.
- Public diagnostics contain no internal exception text.
- New warning and metric tags use bounded, stable values.
- Java 21 verification passes without Mockito self-attach warnings.

## Scenario: `.env` Model Identifier Integrity

### 1. Scope / Trigger

Apply when adding or changing Spring AI provider/model environment variables loaded through
`optional:file:.env[.properties]`.

### 2. Signatures

```text
OPENAI_EMBEDDING_MODEL=<provider model identifier>
spring.ai.openai.embedding.options.model
```

### 3. Contracts

- Put comments on their own line. Java properties parsing treats a trailing ` # comment` as part of the value.
- Provider model identifiers must be non-blank and contain no whitespace.
- `RagConfigValidator` must reject malformed identifiers during startup without logging credentials.

### 4. Validation & Error Matrix

| Value | Result |
| --- | --- |
| `text-embedding-v4` | Startup succeeds |
| blank value | Startup fails with the existing missing-model error |
| `text-embedding-v4  # note` | Startup fails and instructs the operator to move the comment |

### 5. Good / Base / Bad Cases

- Good: a standalone comment line followed by `OPENAI_EMBEDDING_MODEL=text-embedding-v4`.
- Base: omit the key and use the `application.yml` default.
- Bad: append a comment or other whitespace to the model identifier.

### 6. Tests Required

- `RagConfigValidatorTest` accepts the exact provider model identifier.
- It rejects an identifier containing an inline comment and asserts only the safe configuration message.
- A live smoke test must confirm `/api/code/search` returns HTTP 200 through the configured API embedding provider.

### 7. Wrong vs Correct

```properties
# Wrong: the comment becomes part of the model name.
OPENAI_EMBEDDING_MODEL=text-embedding-v4  # API embedding

# Correct: keep the value exact.
# API embedding model
OPENAI_EMBEDDING_MODEL=text-embedding-v4
```

## Scenario: Runtime Dependency Health Snapshot

### 1. Scope / Trigger

Apply when changing `/api/runtime/status`, the home-page service cards, or an external dependency
that operators must diagnose before using retrieval and repository workflows.

### 2. Signatures

```http
GET /api/runtime/status
```

```text
GITLAB_HEALTH_URL=https://gitlab.com
GITLAB_HEALTH_PATH=/explore
```

### 3. Contracts

- The service list is ordered as `Qdrant`, `API 模型`, `GitLab`.
- API model health calls the configured OpenAI-compatible `/models` endpoint with authentication and
  verifies every distinct model currently referenced by embedding and LLM configuration.
- GitLab health probes the configured GitLab instance and path; do not substitute GitHub or another host.
- External probes run concurrently with 1-second connect and 2-second read timeouts.
- Public messages contain counts and safe status text only, never URLs, keys, raw provider responses, or exceptions.

### 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Qdrant and all configured API models available | `coreReady=true` |
| One configured API model absent | API model service unavailable and `coreReady=false` |
| GitLab unavailable while core dependencies are ready | Overall `DEGRADED`; retrieval remains usable |
| Probe throws or times out | Safe unavailable message; no raw exception |

### 5. Good / Base / Bad Cases

- Good: `/models` contains all five configured models and GitLab `/explore` responds successfully.
- Base: GitLab is unavailable, so the page reports degradation without blocking Qdrant/API-model workflows.
- Bad: show Ollama/BGE cards after those dependencies are no longer used, or label GitHub as GitLab health.

### 6. Tests Required

- `RuntimeStatusControllerTest` asserts service names, order, required flags, model-count message, missing-model
  failure, and degradable GitLab failure.
- `HomePageTest` asserts the UI displays backend messages and contains no obsolete Ollama/BGE labels.
- A live browser check confirms the three current cards render without console errors.

### 7. Wrong vs Correct

**Wrong:** infer current dependencies from an older architecture diagram and probe `Ollama`, `BGE`, or GitHub.

**Correct:** derive model IDs from active configuration, verify them through the API gateway, and probe the
configured GitLab instance with bounded timeouts.

## Scenario: Live Frontend Project Context

### 1. Scope / Trigger

Apply when a core page can change project or version without a full page reload.

### 2. Signatures

```javascript
NexusShell.setContext({projectId, version})
window event: nexus:context-changed
```

### 3. Contracts

- `app-shell.js` owns the current context, persists `projectId` in `nexus_project_id`, and rebuilds desktop
  and mobile navigation links plus the context bar on every context event.
- Knowledge, Wiki, and Monitor pages emit the event after selecting or restoring project/version state.
- GitLab links do not receive project/version query parameters.
- A blank project removes the persisted project instead of retaining stale navigation state.

### 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Project changes after shell initialization | All navigation links use the new project |
| Wiki version changes | Wiki/Monitor links use the new version |
| Knowledge filter selects all projects | Persisted project is removed |
| Event omits version | Existing version is preserved |

### 5. Good / Base / Bad Cases

- Good: select project B in Monitor and navigate to Wiki with `projectId=B`.
- Base: initial page load derives context from URL, then local storage.
- Bad: compute href values only once during shell initialization.

### 6. Tests Required

- Shared shell contract asserts the event, setter, navigation refresh, and project persistence.
- Page contracts assert Knowledge, Wiki, and Monitor call `NexusShell.setContext`.
- Browser smoke test changes context and checks desktop/mobile href values.

### 7. Wrong vs Correct

**Wrong:** cache `navLinks()` at boot and let each page maintain unrelated local storage behavior.

**Correct:** publish one context event and let the shared shell own persistence and link regeneration.
