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
