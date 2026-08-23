# 0.9.3 Phase A：统一目录与 Evidence

## Goal

在现有 `MultiSourceKnowledgeStore` 之上新增可审计的统一资料目录层：

- `knowledge_document`：登记逻辑资料（不重复存放正文）
- `knowledge_document_version`：不可变资料版本（幂等复用、不覆盖历史）
- `knowledge_evidence`：结构化原始位置证据（可查询、可审计、可回查）

并把现有四类来源表（参数/存疑/测试用例/测试结果）通过可空列关联到 `document_version_id` 与 `evidence_id`，保持 `evidenceLocation` 兼容读取。

## Requirements

- 新增三张 catalog 表，沿用当前 SQLite 库（`data/multi-source-knowledge.db`），开启外键。
- `knowledge_document` 唯一：`(project_id, source_type, logical_name)`。
- `knowledge_document_version` 唯一：`(document_id, business_version, content_hash, parser_version, extraction_version)`，同内容不同业务版本不得误复用。
- `knowledge_evidence` 唯一：`(document_version_id, locator, excerpt_hash)`。
- Evidence ID 服务端稳定生成：`ev:<projectId>:<documentVersionId>:<hash(locator|excerptHash)>`，禁止由调用方随意伪造。
- 现有 `multi_source_parameter / doubt / test_case / test_result` 增加可空列 `document_version_id`、`evidence_id`，并提供幂等关联方法。
- 新写入的 Claim 通过关联方法可定位到一份资料版本和原始位置；旧数据不强制重写。

## Acceptance Criteria

- [ ] 同内容同版本重复注册不创建重复 DocumentVersion/Evidence。
- [ ] 同内容不同 `business_version` 可创建不同 DocumentVersion（修复方案文档唯一约束）。
- [ ] 任一参数/存疑/测试用例/测试结果 Claim 可通过 `linkClaimToCatalog` 关联到 catalog 记录。
- [ ] `evidenceLocation` 字段继续兼容读取，不破坏现有 API。
- [ ] `git diff --check` 干净，全量测试通过。