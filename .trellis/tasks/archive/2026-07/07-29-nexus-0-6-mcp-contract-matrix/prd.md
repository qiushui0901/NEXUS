# NEXUS 0.6 MCP per-tool contract matrix

## Goal

Complete executable contract coverage for all six NEXUS 0.6 MCP tools across input validation, authorization, dependency degradation, and response truncation.

## Scope

The 0.6 acceptance scope is limited to:

1. `nexus_search_requirements`
2. `nexus_search_code`
3. `nexus_get_source`
4. `nexus_development_plan`
5. `nexus_wiki_page`
6. `nexus_version_diff`

Tools introduced in NEXUS 0.7 or 0.8 are explicitly out of scope.

## Requirements

- Add an explicit, auditable 6 × 4 contract-test matrix covering every scoped tool.
- Validate required strings, safe paths, line ranges, limits, and version relationships at the MCP boundary rather than relying on incidental downstream failures.
- Verify authentication, declared permission, and project allow-list checks for every scoped tool, and prove denied requests do not invoke downstream services.
- Convert only expected dependency/IO unavailability into bounded degraded responses with stable warning codes; never downgrade caller errors, authorization failures, not-found semantics, or programming defects.
- Verify each tool's response is bounded and reports `truncated=true` whenever tool-specific or global limits remove data.
- Preserve existing MCP response envelopes and compatibility constructors.
- Do not modify 0.7/0.8 acceptance history or unrelated user changes.

## Constraints

- Keep authentication and project authorization centralized in `McpToolInvocationService`.
- Keep reusable validation and bounding rules centralized in `McpResponsePolicy` where appropriate.
- Do not expose exception messages, absolute filesystem paths, credentials, or private service endpoints in warnings.
- Avoid broad `RuntimeException` catches that hide validation or implementation defects.
- Tests must be deterministic and use mocks/fakes rather than live Ollama, Qdrant, Git, or network services.

## Acceptance Criteria

- [x] A dedicated NEXUS 0.6 contract test class contains named coverage for all 24 tool/category cells.
- [x] All six tools reject invalid input before invoking downstream services.
- [x] All five `PUBLIC_READ` tools allow an authenticated read-only actor with project access; `nexus_development_plan` requires `OPERATE`.
- [x] All six tools reject missing authentication and forbidden project access before invoking downstream services.
- [x] Every tool has a deterministic degradation contract with bounded stable warning codes for expected dependency unavailability.
- [x] Every tool has a truncation test that asserts both `truncated=true` and the resulting bounded payload.
- [x] Existing MCP unit and HTTP integration tests remain green.
- [x] `./mvnw -q verify` and `git diff --check` pass.
- [x] The NEXUS 0.6 roadmap item is checked only after all verification gates pass, with the matrix and commands recorded.
