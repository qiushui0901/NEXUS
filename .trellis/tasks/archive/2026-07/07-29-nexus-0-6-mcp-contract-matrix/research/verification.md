# NEXUS 0.6 MCP contract verification

Date: 2026-07-29

## Matrix

The six scoped tools are covered across input validation, authentication/authorization/project allow-list enforcement, expected dependency degradation, and response truncation. `NexusMcpV06ContractTest` reports 52 passing tests. The suite also proves that invalid or denied calls do not touch downstream services and that each tool reports single-field truncation rather than silently shortening mapped data.

## Stable degradation warnings

- `NEXUS_SEARCH_REQUIREMENTS_UNAVAILABLE`
- `NEXUS_SEARCH_CODE_UNAVAILABLE`
- `NEXUS_GET_SOURCE_UNAVAILABLE`
- `NEXUS_DEVELOPMENT_PLAN_UNAVAILABLE`
- `NEXUS_WIKI_PAGE_UNAVAILABLE`
- `NEXUS_VERSION_DIFF_UNAVAILABLE`

Only expected dependency/IO availability failures are converted. `IllegalArgumentException`, authorization failures, Wiki 404 responses, and unrelated runtime failures remain caller-visible according to their original semantics. Warning messages do not include raw exception messages, credentials, private endpoints, or absolute paths.

## Commands

```text
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dtest='NexusMcpV06ContractTest,NexusMcpToolsTest,McpResponsePolicyTest,McpHttpIntegrationTest' test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q clean verify
git diff --check
```

Results: focused regression passed; clean full gate passed with 258 tests, 0 failures, 0 errors, and 0 skipped tests; diff check passed.
