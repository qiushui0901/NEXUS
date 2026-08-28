# 实体中心的多版本知识检索 — 设计

权威设计：`docs/entity-centric-knowledge-retrieval-implementation.md`（§3–§13）。
本文件记录**针对现有 NEXUS 代码的落地决策**，作为子任务实现时的约束。

## 分层

```text
权威事实层：SQLite Claim / 参数表 / 测试结果 / 代码索引与 commit
对齐层：BusinessConcept(跨版本实体) + 别名 + 成员 + alignment_relation
召回层：精确实体/别名 → 结构化成员 → 强制当前代码+参数表 → 向量补召回
解释层：事实优先级 + 版本时间轴 + 冲突/实现偏差
生成层：KnowledgeAnswerService（Evidence 校验后 LLM 回答）
```

## 复用与决策

| 主题 | 决策 |
|---|---|
| 实体身份 | 沿用 `conceptId = con:sha256(projectId\|canonicalKey)` 稳定派生，**版本与来源无关**；`unique(project_id, canonical_key)` 天然去重 |
| canonicalKey | **去掉 `param:/req:/test:/obs:/doubt:` 前缀**，改为 `<module>.<subject>` 规范化键（`AlignmentNaming.keySegment`）；同 subject 不同 module 不合并（防错误实体合并） |
| 构建入口 | `build(projectId, version)` 保留为版本级（只删该版本成员）；新增 `buildProject(projectId)`：枚举项目全部业务版本 + 代码符号，逐版本增量 upsert，**不删除其他版本成员** |
| 版本枚举 | `MultiSourceKnowledgeStore` 新增 `findBusinessVersions(projectId)`（`knowledge_document_version` distinct business_version） |
| 成员校验 | 成员 claimId 必须真实存在且属于同项目（LLM 提议的成员在 Phase 2 才出现，Phase 1 先加断言级校验） |
| 别名 | 保留 `normalization_method + confidence`（SOURCE_NAME），`origin/status/evidence_ids` 后续迁移（dev md §5.1 允许） |
| 索引 | 补 §5.2 五个索引：alias 查找 / member(version) / member(claim) / claim(fact,subject,predicate) / document_version(business,status) |
| 关系 | 复用 `alignment_relation`，关系生命周期 PROPOSED→CONFIRMED/REJECTED（Phase 2+ 落地 LLM 提议） |
| 冲突 | 复用 `MultiSourceConflictAnalyzer`（fact_key 聚合）；Phase 4 扩展 `CODE_PARAMETER_MISMATCH`/`REQUIREMENT_IMPLEMENTATION_GAP` |
| 向量 | Phase 1-3 仅经 `business_concept_member` 做跨版本聚合；向量仅问题实体发现（§10.3 第 3 种） |
| API | `POST /api/knowledge/entity-search`（Phase 3），响应按实体分组：currentFacts（code/parameterTables/testResults）+ timeline（按 businessVersion）+ relations + conflicts + warnings；第一版不接入现有 search |
| 回答 | `KnowledgeAnswerService` 只吃受限证据包；模型返回 evidenceId 必须过服务端校验；无证据/冲突/代码缺失走模板 |

## 数据流

```text
问题 → 规则/LLM 提取实体(mentions) + 意图 + 版本条件(可选)
     → EntityResolver（规范化精确→别名→成员名→factKey/列名→代码符号→向量候选→LLM 受限选择）
     → EntityEvidenceAggregator（全版本 Claim/参数/测试/代码/证据/关系/冲突 + 强制当前代码与参数表）
     → EntityFactPriorityService（按意图分区：CURRENT_BEHAVIOR / CURRENT_VALUE / VALIDATION / REQUIREMENT_TARGET / GAP）
     → KnowledgeAnswerService（可选）→ 带引用回答
```

## 状态与交付顺序

6 个子任务按 1→5 串行（Phase 6 评测驱动、可后置）。子任务 1（实体基础）先行：
实体身份、前缀去除、buildProject、历史保留、索引、测试。