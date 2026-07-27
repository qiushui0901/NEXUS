# Technical Design

## 1. Data contract

Extend `WikiModels.PageSource` and `WikiModels.Page` with optional structured fields while retaining all existing fields:

- `requirementSources`: requirement file/version/hash references
- `productRules`: normalized business-rule statements retained for legacy compatibility
- `processSteps`: ordered user/system flow steps
- `codeEntries`: file/symbol/role/commit evidence
- `dataImpacts`: configuration, state and data impacts stated by evidence
- `boundaryConditions`: exception, eligibility, idempotency and edge conditions
- `acceptanceCriteria`: verifiable acceptance statements
- `testKnowledge`: explicit real-execution state, optional reference, and reviewed cases
- `versionChange`: change type/base version/current version summary
- `quality`: coverage and review metadata

Jackson record fields are nullable for old artifacts. Service normalization converts null lists/objects to empty values for newly generated artifacts.

## 2. Generator

Refactor `tools/build-version-wiki.py` into two evidence paths:

1. Requirement path: load an optional ignored requirement snapshot for the selected version, convert each valid entry into one feature page, parse numbered headings and bullet statements into the structured sections, and preserve requirement evidence excerpts and content hashes.
2. Code path: search the target commit for conservative keywords derived from requirement titles and high-signal terms, then record only real matched files/symbols. Code matches enrich the corresponding feature page; unmatched features remain requirement-verified only.

The generator always creates one version overview. It no longer creates generic module pages. Existing manually authored fields are preserved only when they belong to the same stable feature and have evidence; auto Git-summary fields are replaced on rebuild.

## 3. Java draft pipeline

Upgrade `VersionKnowledgeBuildPipeline` so API-generated drafts use the same structured sections and explicit quality/test status. The draft remains under `data/wiki-drafts` and is not auto-published.

## 4. Browser

Rework `wiki.html` tabs around reader tasks:

- Overview: description, version change and knowledge completeness
- Requirements: source, business rules, process, data/config and boundaries
- Development: code entries with file/symbol/role and traceable evidence
- Testing: explicit execution state followed by acceptance criteria and suggestions
- Evidence: complete source cards

Legacy fields are mapped as fallbacks. Empty sections are hidden or show truthful missing-state messages.

## 5. Safety and compatibility

- Never serialize source absolute paths, vector payloads, Qdrant runtime data or credential-like fields.
- All browser interpolation goes through `esc`.
- Existing schema version 1 files stay readable; generated high-quality pages use schema version 2.
- Requirement snapshot input remains ignored/local. Published Wiki artifacts may contain bounded requirement excerpts and hashes, not the source documents.

## 6. Rollback

The new fields are additive. Rolling back the browser still leaves legacy summary/productRules/codeSymbols/testPoints/evidence fields populated. The generator writes each version atomically through staging.
