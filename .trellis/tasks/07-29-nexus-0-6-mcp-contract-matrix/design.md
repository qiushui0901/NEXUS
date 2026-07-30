# Design: NEXUS 0.6 MCP contract matrix

## Contract boundary

`NexusMcpTools` is the public MCP boundary. Each method must perform deterministic request normalization/validation, delegate authentication and authorization to `McpToolInvocationService`, invoke its domain dependency, map expected dependency failures to an explicit degraded response, and finally pass through `McpResponsePolicy` global bounding.

## Test architecture

Add `NexusMcpV06ContractTest` as a table-oriented unit contract suite. The suite constructs `NexusMcpTools` with mocked domain dependencies and the real invocation/policy components. Shared helpers create authenticated READONLY/OPERATOR contexts, allowed/denied project identities, large fixtures, and failure fixtures.

The test names and nested sections form the executable 6 × 4 matrix:

| Tool | Input | Permission | Degradation | Truncation |
|---|---|---|---|---|
| search requirements | blank query | READONLY/auth/project | degraded retrieval warnings | hit/evidence/total bounds |
| search code | blank query | READONLY/auth/project | code search unavailable | result/excerpt/total bounds |
| get source | path/range | READONLY/auth/project | source unavailable | line/total bounds |
| development plan | blank query | OPERATE/auth/project | plan warnings/unavailable | references/text/total bounds |
| wiki page | version/feature ID | READONLY/auth/project | repository unavailable | evidence/code/relation/total bounds |
| version diff | versions/same version | READONLY/auth/project | comparison warnings/unavailable | change/case/page/total bounds |

## Validation

Introduce small reusable policy helpers only when multiple methods share the rule:

- required non-blank string
- valid positive line range after the existing source-line cap
- distinct version values

Validation stays inside the invocation action after authentication/project checks unless an existing security rule requires denial to win over request validation. Tests assert the chosen ordering consistently and verify downstream services remain untouched.

## Degradation

Expected dependency failures are caught narrowly around the dependency call. Prefer typed exceptions (`IOException`, repository storage exception, or a dedicated availability exception). If a dependency currently exposes only an unchecked wrapper, match only the known wrapper/cause and rethrow unrelated failures.

Stable warnings:

- `NEXUS_SEARCH_REQUIREMENTS_UNAVAILABLE`
- `NEXUS_SEARCH_CODE_UNAVAILABLE`
- `NEXUS_GET_SOURCE_UNAVAILABLE`
- `NEXUS_DEVELOPMENT_PLAN_UNAVAILABLE`
- `NEXUS_WIKI_PAGE_UNAVAILABLE`
- `NEXUS_VERSION_DIFF_UNAVAILABLE`

Degraded responses retain scope and metadata, contain empty or minimal safe data, and never include the raw exception message.

Existing domain-level warnings remain authoritative when the dependency returns a successful degraded result.

## Truncation

Tool-level mapping records whether any collection, excerpt, source range, or textual field was reduced. This flag is combined with the existing global `McpResponsePolicy.enforceTotalLimit` result. Tests assert actual bounded sizes/content as well as the flag.

## Compatibility and rollback

No MCP tool names or method signatures change. Existing response envelope fields remain stable. Production changes are restricted to `NexusMcpTools` and reusable `McpResponsePolicy` helpers where tests demonstrate a gap. Rollback is limited to those changes plus the dedicated test and roadmap record.
