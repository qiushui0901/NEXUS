# Implementation Plan

1. Add typed conflict contracts with stable enums and empty-report factory.
2. Implement deterministic normalization, deduplication, version checks, source-pair classification and report aggregation.
3. Add permission-protected conflict analysis controller.
4. Append conflict report to non-streaming development-plan response and derive safe retrieval-evidence claims.
5. Add service, controller and RAG regression tests using generic data.
6. Update backend knowledge contract and README/CHANGELOG if public API behavior changes.
7. Run JDK 21 Maven verification and repository hygiene checks.

## Validation

```bash
JAVA_HOME=/Users/user/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
PATH=/Users/user/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home/bin:$PATH \
./mvnw -B verify

git diff --check
```

## Rollback

Remove the appended response field, controller and conflict package. No persistent data migration is involved.
