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
