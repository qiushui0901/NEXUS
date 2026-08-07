# Journal - qiushui (Part 1)

> AI development session journal
> Started: 2026-07-14

---



## Session 1: 统一检索与版本知识草稿

**Date**: 2026-07-24
**Task**: 统一检索与版本知识草稿
**Branch**: `main`

### Summary

实现统一 RetrievalPipeline、版本增量知识草稿构建 API、NEXUS 0.2.0 版本记录及完整安全回归测试。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `fc5566c` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: 完成版本档案与多来源差异分析

**Date**: 2026-07-24
**Task**: 完成版本档案与多来源差异分析
**Branch**: `main`

### Summary

实现安全版本档案、需求代码测试Wiki四类差异、版本API及回归测试；Java 21 verify共98个测试通过。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `d91c3d8` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 3: 完成版本中心与差异浏览页面

**Date**: 2026-07-24
**Task**: 完成版本中心与差异浏览页面
**Branch**: `main`

### Summary

完成 0.4.0-SNAPSHOT 版本中心页面、需求/代码/测试/Wiki 四类差异浏览、Wiki 深链接、页面契约测试和安全降级展示；使用 Java 21 验证 100 条测试通过，并完成任务归档。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `899a33f` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 4: 版本 Wiki 实质内容与直接对比

**Date**: 2026-07-26
**Task**: 版本 Wiki 实质内容与直接对比
**Branch**: `main`

### Summary

版本中心改为直接读取 Wiki 版本；基于 immortal-game-service 的 64 个 Git 版本重建 200 个实质页面，补充代码边界、模块、符号和证据，并保持需求与测试缺失时明确降级。新增可重复构建工具、Wiki 无档案对比回退、页面与服务测试，Java 21 全量 101 项测试通过。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `2482396` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 5: 补齐需求版本链

**Date**: 2026-07-27
**Task**: 补齐需求版本链
**Branch**: `main`

### Summary

新增受控需求快照、版本档案解析与需求差异回退链路，整理 20 个可靠需求基线并完善测试、文档和版本记录。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `24445e2` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 6: 修正需求增量继承语义

**Date**: 2026-07-27
**Task**: 修正需求增量继承语义
**Branch**: `main`

### Summary

需求快照按基线链累计合成，未重复出现的历史需求继续有效，仅结构化 REMOVE 产生删除差异，并补充完整回归测试。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `6f4fc23` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 7: Unified knowledge conflict detection

**Date**: 2026-07-27
**Task**: Unified knowledge conflict detection
**Branch**: `main`

### Summary

Added deterministic project/version-scoped conflict analysis for requirement, code, test, and derived Wiki claims; integrated conflict reports into non-streaming development-plan responses; documented contracts and passed 133 tests.

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `75924ad` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 8: Release NEXUS 0.5

**Date**: 2026-07-28
**Task**: Release NEXUS 0.5
**Branch**: `main`

### Summary

Completed the NEXUS 0.5 release: unified requirement review retrieval, request-scoped evidence citations, reviewable Wiki draft lifecycle with atomic publish/rollback, fail-safe authentication, bounded BGE timeouts, visible degradation logging, repository hygiene, and full Java 21 verification.

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `8b9d8cb` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 9: NEXUS 0.6 MCP Server

**Date**: 2026-07-28
**Task**: NEXUS 0.6 MCP Server
**Branch**: `main`

### Summary

Implemented and verified the Spring AI Streamable HTTP MCP facade with six evidence-bound tools, shared REST/MCP authorization, bounded responses, Codex and Cursor project configs, stdio bridge, container delivery files, Inspector/client smoke tests, and source symlink escape protection.

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `0c00e95` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 10: NEXUS 0.7 多语言代码智能收口

**Date**: 2026-07-28
**Task**: NEXUS 0.7 多语言代码智能收口
**Branch**: `main`

### Summary

完成 Java/Go/Python/TypeScript Tree-sitter 索引、SQLite 符号图、调用与 commit 影响分析、REST/MCP 接口、Wiki Resource Template、权限与降级边界；补齐隔离、事务回滚、删除/重命名和客户端协议测试。JDK 21 verify 181 项通过；Codex 调用通过，Cursor 完成 MCP 初始化但工具调用受团队用量上限阻断。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `97cbf42` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete

## Session 11: NEXUS 0.6 六工具契约矩阵收口

**Date**: 2026-07-29
**Task**: NEXUS 0.6 MCP per-tool contract matrix
**Branch**: `main`

### Summary

补齐 6 个 NEXUS 0.6 MCP 工具的 6 × 4 契约矩阵，覆盖入参校验、认证/角色/项目白名单、预期依赖降级和数量/文本/总响应截断；增加逐工具单字段静默截断回归，并保持权限集中、窄异常降级和安全 warning 语义。

### Main Changes

- 新增 `NexusMcpV06ContractTest`，52 项测试覆盖六工具完整矩阵及错误不降级、安全信息不泄漏。
- 强化 `McpResponsePolicy` 和六工具映射的截断传播，并补充策略 helper 的 null、边界、数量和文本单测。
- 稳定降级 warning code 为 `NEXUS_*_UNAVAILABLE`，且不回显异常消息、绝对路径、凭据或私有端点。
- 更新 0.6 路线图验收项和任务验证记录；未修改 0.7/0.8 验收历史。

### Testing

- MCP 定向回归通过：契约矩阵 52 项、原工具测试 4 项、策略测试 6 项、HTTP 集成测试 1 项。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q clean verify`：258 项通过，0 failures/errors/skipped。
- `git diff --check`：通过。

### Status

[OK] **Implementation and quality check complete; awaiting explicit commit/archive instruction**

### Next Steps

- 用户确认后再提交或归档；本轮未自动 commit、push 或 archive。


## Session 11: Commit and archive 0.8 + MCP contract

**Date**: 2026-07-30
**Task**: Commit and archive 0.8 + MCP contract
**Branch**: `main`

### Summary

Committed MCP 0.6 contract matrix, 0.8 BGE/shiguang eval tooling, smoke/ecosystem docs, and Dockerfile 0.8 jar align; archived 07-28-nexus-0-8-quality-performance and 07-29-nexus-0-6-mcp-contract-matrix.

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `b9489ab` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 12: 完成 0.8.2 可信文档召回与 parent 代表项优化

**Date**: 2026-08-03
**Task**: 完成 0.8.2 可信文档召回与 parent 代表项优化
**Branch**: `main`

### Summary

扩展 document-v2-v2 至 18 个文档与 24 个结构化用例，新增分层召回评测；优化 child-first parent 代表项选择，使 Child Recall@10 从 0.916667 提升到 1.0，并通过 289 项 Java 测试和完整真实 calibration。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `fe171e2` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 13: Wiki Module 纵向闭环冒烟验证（0.8.4）

**Date**: 2026-08-07
**Task**: Wiki Module 纵向闭环冒烟验证（0.8.4）
**Branch**: `main`

### Summary

真实仓库验证 Module build/发布/重建/stale 闭环：Claim 引用类型精确、下标有效；修复 build.json 缺口

### Main Changes

# 冒烟验证结果：Module 页面纵向闭环（0.8.4）

- 日期：2026-08-07
- 环境：本机 `NEXUS-0.8.4-SNAPSHOT.jar`（local profile，端口 18080）+ shiguang 真实仓库
- 项目配置：`PROJECT_1_ID=shiguang-eval`、`PROJECT_1_REPO_PATH=/Users/user/Documents/qiushui-shiguang`（HEAD `d29f325`，与 SQLite 符号图快照 commit 一致）

## 验证链路

### 1. Module build API（POST /api/wiki/modules/build）

- `modulePath=.../domain/repository`：featureId=`module-repository`，10 条证据（CODE×4 / DEPENDENCY×2 / DATA×4），6 条 Claim。
  全部 evidenceIds 下标有效；`repository-dependencies`(INFERRED) 引用 2 条 DEPENDENCY 类型证据 ✓
- `modulePath=.../controller`：featureId=`module-controller`，24 条证据（CODE×8 / CODE_GRAPH×6 / ROUTE×10），6 条 Claim：
  - `controller-entry` **FULL** 引用 10 条 **ROUTE** 类型证据 ✓（类型精确匹配）
  - `controller-flow` PARTIAL 引用 6 条 **CODE_GRAPH** ✓
  - `controller-responsibility` PARTIAL 引用 8 条 **CODE** ✓

### 2. 发布链路（草稿 → 审核 → 发布）

- 冒烟发现并修复：模块草稿缺 `build.json` → `publish` 报 404「知识草稿构建产物不存在」。
  修复：两个构建服务补齐兼容 `BuildArtifact`（commit `9b051eb`）。
- 修复后：`transition DRAFT→IN_REVIEW→APPROVED` → `publish` → **PUBLISHED，1 页** ✓

### 3. rebuild（POST /api/wiki/modules/rebuild）

- 对已发布 `module-repository` 重建：6 条 Claim 全部 UNCHANGED（源码未变，行为正确）✓
- 生成新草稿并落盘 `claim-diff.json` ✓

### 4. staleness（GET /api/wiki/staleness）

- published commit `d29f32589c` == 当前 HEAD `d29f32589c` → `stale: false` ✓

## 结论

- 各类型 Claim 引用正确类型、有效下标的 Evidence（不只是单元测试）✓
- Module 草稿 → 审核 → 发布 → 重建 → 失效检测闭环在真实仓库上走通 ✓
- 产出 1 个集成修复（build.json）并入 0.8.4（Fixed 段）
- 回归：327 单元测试全绿

## 已知边界（后续迭代）

- 符号级 stale 传播尚未真实验收：本仓 HEAD 与发布 commit 一致，未触发 diff 命中路径；需在下一迭代用真实 git 变更验证「符号 → 调用关系 → Claim → 页面」的失效传播
- rebuild 的 MODIFIED/REMOVED 差异仅在源码实际变化时出现，本次验证为 UNCHANGED 基线


### Git Commits

(No commits - planning session)

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete
