# 多源需求知识统一管理与检索 — Implement

> 推荐的执行顺序（每步完成后再继续下一步）。

- [ ] 第 1 步：Phase 0 元数据兼容层（SourceType/Authority/Evidence ID 工具 + 兼容映射测试）
- [ ] 第 2 步：Phase 1 数值表与存疑结构化（ParameterTableLoader + DoubtClaim）
- [ ] 第 3 步：Phase 2 测试用例/结果导入（先 XML/JSON，后源码）
- [ ] 第 4 步：Phase 3 统一 Claim + factKey + 冲突扩展
- [ ] 第 5 步：Phase 4 意图路由 + 多源融合检索 + 解释
- [ ] 第 6 步：Phase 5 Golden Dataset + 灰度

## 当前进行中

- [x] 生成任务文档（PRD/design/implement）
- [x] Phase 0 元数据兼容层（SourceType/Authority + 兼容映射）
- [x] Phase 1 结构化解析层 + SQLite 存储 + 门禁接线
- [x] Phase 2 测试用例/结果导入（JSON/JSONL + JUnit XML）
- [x] Phase 3 统一 Claim（UnifiedKnowledgeClaim）+ factKey + 多源冲突分析器
- [x] Phase 4 意图分类 + 来源过滤 + 多源检索服务 + 解释
- [x] Phase 5 Golden Dataset 离线评估（`multi-source-golden.jsonl` + `MultiSourceGoldenEvalTest`）
- [x] Code Review 整改第二轮：
  - 跨来源关系接入生产链路（检索生成/持久化/响应返回）
  - 关系目标必须真实 Claim，未匹配输出 unresolved，不再伪造 req:xxx
  - REQUIREMENT 适配器保留 ClaimStatus 与 Evidence，仅 VERIFIED 可回查来源进入规范检索
  - CODE 适配器接入 CodeKnowledgeService
  - 测试用例/结果状态持久化 + schema 迁移
  - 参数生效版本按 claim.version 写入并校验，避免版本覆盖
  - 冲突惩罚改用冲突分组 Set，与 conflictGroups 对齐
  - 冲突范围与分页结果一致（按当前页 Claim 计算状态）
- [x] 生产加固第十四轮：按项目灰度开关（`app.rag.multi-source`）+ LLM 意图回退 + `POST /api/knowledge/multi-source/search` HTTP API
- [x] 生产加固第十五轮：旧 `TEST` 数据回填清洗（`SourceType.normalized()` + `KnowledgeConflictService` 规范化回填 `TEST→TEST_CASE`，warnings 可见）
- [ ] 生产加固（留待后续）：Qdrant payload 多源过滤、真实 Token usage、跨源关系 LLM 语义确认