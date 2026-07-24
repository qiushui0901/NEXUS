# Implementation Plan

1. Add Wiki configuration and typed domain contracts.
2. Implement safe filesystem repository and deterministic generator with Markdown rendering.
3. Add browse/generate REST APIs and page route.
4. Add dependency-free `wiki.html` and monitor entry link.
5. Add 5.1 seed source, generate initial pages, and verify grow-fund/grow-discount isolation.
6. Add unit/controller/static-page tests for validation, generation, repository APIs and UI structure.
7. Run `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify` and `git diff --check`.

## Rollback

Remove the new wiki package/controller/models/static page/config block and generated `data/wiki`/`data/wiki-sources` artifacts. No migration or Qdrant rollback is required.
