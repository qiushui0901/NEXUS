# Implementation plan: NEXUS 0.6 MCP contract matrix

## 1. Baseline and matrix

- [x] Inspect the exact six MCP method bodies, response records, dependency exception types, and existing test fixtures.
- [x] Add `NexusMcpV06ContractTest` with explicit nested sections for each tool and four contract categories.
- [x] Run the new test alone to capture genuine contract gaps.

## 2. Minimal production fixes

- [x] Centralize only genuinely shared required-string/range/version validation.
- [x] Add missing per-tool input validation before downstream invocation.
- [x] Add narrow expected-dependency degradation mappings with stable warning codes.
- [x] Correct per-tool truncation propagation and payload bounding without changing public signatures.

## 3. Regression verification

- [x] Run `NexusMcpV06ContractTest`.
- [x] Run `NexusMcpToolsTest`, `McpResponsePolicyTest`, and `McpHttpIntegrationTest` together with the new suite.
- [x] Run `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q verify`.
- [x] Run `git diff --check` and inspect only task-owned diffs.

## 4. Acceptance records

- [x] Update the 0.6 roadmap checkbox only after all gates pass.
- [x] Record the six-tool matrix, warning semantics, truncation assertions, and verification commands.
- [x] Update Trellis task status/journal without committing, pushing, or archiving unless explicitly requested.

## Review gates

- No broad exception swallowing.
- No authorization bypass or downstream call before access denial.
- No raw exception text/path leakage.
- No claim of 24-cell completion without an executable test for each cell.

## Verification record (2026-07-29)

- Dedicated matrix: `NexusMcpV06ContractTest` contains 52 passing tests, including the executable 6 × 4 input/permission/degradation/truncation cells and six single-field silent-truncation regressions.
- Policy regression: `McpResponsePolicyTest` contains 6 passing tests, including null, exact-boundary, over-count, and overlong-text coverage for the shared truncation helpers.
- Focused regression: `NexusMcpV06ContractTest`, `NexusMcpToolsTest`, `McpResponsePolicyTest`, and `McpHttpIntegrationTest` passed together.
- Full gate: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q clean verify` passed with 258 tests, 0 failures, 0 errors, and 0 skipped tests.
- Whitespace gate: `git diff --check` passed.
- Review result: authorization remains centralized, expected availability failures are caught narrowly, caller/not-found/programming errors are not degraded, warnings expose no dependency exception text, and no 0.7/0.8 acceptance behavior was changed.
- Delivery state: implementation and quality check are complete; task remains uncommitted and unarchived pending explicit user instruction.
