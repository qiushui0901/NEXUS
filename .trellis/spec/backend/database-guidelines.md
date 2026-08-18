# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

<!--
Document your project's database conventions here.

Questions to answer:
- What ORM/query library do you use?
- How are migrations managed?
- What are the naming conventions for tables/columns?
- How do you handle transactions?
-->

(To be filled by the team)

---

## Query Patterns

<!-- How should queries be written? Batch operations? -->

(To be filled by the team)

---

## Migrations

<!-- How to create and run migrations -->

(To be filled by the team)

---

## Naming Conventions

<!-- Table names, column names, index names -->

(To be filled by the team)

---

## Common Mistakes

<!-- Database-related mistakes your team has made -->

(To be filled by the team)

## Scenario: Knowledge Base State Merge

### 1. Scope / Trigger

Apply when combining SQLite knowledge-management state with Qdrant-backed synthetic knowledge bases.

### 2. Signatures

```java
List<KnowledgeBaseView> SQLiteKnowledgeManagementStore.allBasesForProjects(List<String> projectIds)
Page<KnowledgeBaseView> KnowledgeManagementController.list(...)
```

### 3. Contracts

- Read all real bases for accessible projects without status/type/query filtering or the 200-row page cap.
- Real SQLite state wins by stable base ID (`<projectId>:requirement|code`).
- Add a synthetic READY base only when no real base with that ID exists.
- Requirement fallback counts the configured `documentId + version`; code fallback counts `projectId`.
- Apply status/type/query filters and normalized pagination only after merging.

### 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Real base is FAILED and READY is requested | Exclude it; never synthesize READY |
| Collection has another requirement version only | No synthetic current-version base |
| Shared code collection has another project only | No synthetic current-project base |
| More than 200 real bases | Preserve every real base before final pagination |
| Negative page or oversized size | Normalize to page `0` and size at most `200` |

### 5. Good / Base / Bad Cases

- Good: read 205 real bases, merge missing scoped Qdrant bases, filter, then return the requested page.
- Base: state store is empty and the current project/version has points, so one synthetic READY base appears.
- Bad: filter SQLite first or use collection `points_count` as proof that the current target is indexed.

### 6. Tests Required

- Store test proves `allBasesForProjects` returns more than 200 rows.
- Controller tests cover FAILED-vs-READY filtering, version/project-scoped counts, page normalization,
  and separate requirement/code rebuild dispatch.

### 7. Wrong vs Correct

**Wrong:** request `size=10000`, accept the store's capped 200 rows as complete, and synthesize missing IDs.

**Correct:** use the dedicated unpaged read for merge identity, then apply all user filters and pagination once.
