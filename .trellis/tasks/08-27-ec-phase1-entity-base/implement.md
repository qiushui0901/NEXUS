# Phase 1 跨版本实体基础 — 实施清单

## 改动面

1. `BusinessConceptService.conceptFor`：canonicalKey 去前缀 → `keySegment(module) + "." + keySegment(subject)`
   （module 空时退化为 subject）。entityType 保留但仅作属性（PARAMETER/REQUIREMENT/TEST_MODULE/...）。
2. `MultiSourceKnowledgeStore`：新增 `findBusinessVersions(projectId)`（distinct business_version）；
   新库 DDL 补 `idx_claim_fact_subject`、`idx_document_version_business_status`。
3. `CodeCentricAlignmentStore`：补 `idx_concept_alias_lookup`、`idx_concept_member_entity_version`、
   `idx_concept_member_claim`（create index 幂等，老库自动生效）。
4. `BusinessConceptService.buildProject(projectId)`：
   - versions = findBusinessVersions(projectId)；空 → 空结果。
   - 对每个版本跑版本级成员重建（deleteMembersByVersion(该版本) → 该版本 claims → 成员 upsert）。
   - 代码符号仅挂一次（不随版本重复）；概念/别名 upsert 跨版本共享。
   - 成员 claim 校验：claimId 来自权威查询，天然存在（防御性断言保留）。
5. 测试更新 `BusinessConceptServiceTest`：canonicalKey 断言改无前缀；新增：
   - 跨版本同实体：5.0 + 5.1 同 subject/module → 同一 conceptId。
   - 多来源同实体：param + requirement + test 成员共存（无前缀拆分）。
   - 历史保留：build 5.1 后再 build 5.0，5.1 成员仍在；buildProject 不丢任何版本成员。
   - 防误合并：同 subject 不同 module → 不同实体。
6. CHANGELOG：`### Changed/Added` 记录实体基础改动。

## 验证

```bash
./mvnw -q -Dtest=BusinessConceptServiceTest test
./mvnw -q -Dtest=CodeCentricAlignmentStoreTest test
./mvnw test
```

## 评审点

- canonicalKey 变更是否影响其它查找（`findConceptByKey` 调用方）。
- buildProject 与版本级 build 混用是否产生成员重复（unique 约束兜底）。
- 索引补全是否在老库 create index 幂等。