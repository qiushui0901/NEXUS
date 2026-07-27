# Implementation Plan

1. Add backward-compatible structured Wiki model contracts and normalization in `WikiGenerationService`.
2. Refactor `tools/build-version-wiki.py` to ingest requirement snapshots, build stable feature pages, derive evidence-bound sections, conservatively map code, and stop generating module-list pages.
3. Upgrade `VersionKnowledgeBuildPipeline` draft output to populate the structured contract and explicit test/quality status.
4. Rebuild `wiki.html` rendering around requirements, process, development, testing and evidence while retaining deep links and legacy fallbacks.
5. Add Java and Python regression tests for compatibility, section extraction, safety, missing evidence and UI escaping.
6. Generate 5.1 locally, inspect representative pages and coverage metrics, then run Java 21 full verification, Python tests and `git diff --check`.
7. Confirm ignored requirement snapshots, vectors, runtime storage, credentials and local paths are absent from the Git diff.

## Review gates

- No inferred business statement without requirement evidence.
- No “test passed” state without a real test snapshot.
- No code entry unless the file/symbol exists at the selected commit.
- No repository-module pages in the 5.1 business Wiki.

## Rollback

Restore the previous generator and browser; additive page fields can remain unread without breaking legacy fields. Regenerate only the affected version directory from its source definition.
